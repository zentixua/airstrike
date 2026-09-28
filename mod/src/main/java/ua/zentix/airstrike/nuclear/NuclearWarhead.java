package ua.zentix.airstrike.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.model.FireballModel;
import ua.zentix.airstrike.nuclear.model.PromptRadiationModel;
import ua.zentix.airstrike.nuclear.model.ThermalModel;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.ThermalShadow;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModEffects;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.util.Terrain;

import java.util.UUID;

/**
 * Ядерная боеголовка: подрыв (DESIGN-nuke §0, §4). Сервер записывает событие и шлёт его всем в измерении
 * одним пакетом. Всё остальное идёт по тикам под бюджетом ({@link NuclearWorld}): световой импульс и проникающая
 * радиация по сущностям (ближние первыми), ударная волна, разрушения и воронка.
 */
public final class NuclearWarhead {
    private NuclearWarhead() {}

    /**
     * @param target   точка прицеливания (земля под эпицентром берётся по карте высот)
     * @param airBurst воздушный подрыв на оптимальной высоте; иначе — наземный
     */
    public static Detonation detonate(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner) {
        return detonate(level, target, yieldKt, airBurst, owner, AirstrikeConfig.SERVER.nukeEffectsScale.get().floatValue());
    }

    /** @param scale масштаб радиусов (1 — как в жизни); обычно из настройки effects_scale, GameTest задаёт свой */
    public static Detonation detonate(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner, float scale) {
        long started = System.nanoTime();
        NuclearEvents events = NuclearEvents.get(level);
        int groundY = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(target.x), Mth.floor(target.z));
        double ground = Terrain.ready(level, Mth.floor(target.x) >> 4, Mth.floor(target.z) >> 4) ? Math.min(groundY, target.y) : target.y;
        if (ground <= level.getMinBuildHeight()) ground = target.y;
        double hob = airBurst ? Yield.optimalBurstHeight(yieldKt) * scale : 0;
        boolean surface = hob / scale < FireballModel.maxRadius(yieldKt, true);
        float visibility = (float) (level.isThundering() ? ThermalModel.VISIBILITY_THUNDER : level.isRaining() ? ThermalModel.VISIBILITY_RAIN : ThermalModel.VISIBILITY_CLEAR);
        Detonation d = new Detonation(events.nextId(), new Vec3(target.x, ground + hob, target.z), ground, yieldKt, surface,
                level.getGameTime(), (float) (level.random.nextDouble() * Math.PI * 2), 5 + level.random.nextFloat() * 10,
                visibility, level.random.nextLong(), scale, surface && AirstrikeConfig.SERVER.nukeFallout.get());
        events.add(d);
        PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeDetonation(d));

        NuclearWorld.get(level).onDetonation(level, d, owner);
        Airstrike.LOG.info("Ядерный подрыв №{}: {} кт, {}, {} {} {}, масштаб {}, {} мс", d.id(), Math.round(yieldKt), surface ? "наземный" : "воздушный",
                Mth.floor(d.burst().x), Mth.floor(d.burst().y), Mth.floor(d.burst().z), scale, (System.nanoTime() - started) / 1_000_000);
        return d;
    }

    /** Дальше этого (блоки) ни свет, ни проникающая радиация сущностей не трогают: ожоги 1-й степени или 50 бэр. */
    public static double exposureRange(Detonation d) {
        double burnRange = d.blocks(ThermalModel.rangeForFluence(ThermalModel.BURN_1, d.yieldKt(), d.surface(), d.visibility()));
        double radRange = d.blocks(radiusForDose(d.yieldKt(), 50));
        return Math.max(burnRange, radRange);
    }

    /**
     * Свет и проникающая радиация по одной сущности в пределах {@link #exposureRange} (её отбирает вызывающий).
     * Идёт не в тике подрыва, а под бюджетом ({@link NuclearWorld}).
     *
     * @return задело ли (жива и не в творческом режиме)
     */
    public static boolean expose(ServerLevel level, Detonation d, LivingEntity living, @Nullable Entity owner) {
        if (!living.isAlive()) return false;
        // творческий режим и наблюдатели: ни ожогов, ни дозы (как и у урона)
        if (living instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        Vec3 eye = living.getEyePosition();
        if (sees(level, d, living)) burn(level, d, living, d.fluence(eye), owner);
        double rem = PromptRadiationModel.doseRem(Math.max(1, d.metres(eye.distanceTo(d.burst()))), d.yieldKt()) * shielding(level, eye, d.burst());
        if (rem < 1) return true;
        if (living instanceof ServerPlayer player) {
            if (AirstrikeConfig.SERVER.nukeRadiation.get()) RadiationTicker.addDose(player, (float) (rem / PromptRadiationModel.REM_PER_GY));
        } else if (rem >= 1000) {
            living.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, owner), Float.MAX_VALUE);
        } else if (rem >= 400) {
            living.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, owner), living.getMaxHealth() * 0.5f);
        }
        return true;
    }

    /**
     * Ожоги по кал/см² (DESIGN §1.4): с 3 — поджог и эффект ожогов, с 8 (ожоги 3-й степени) — тяжёлый урон,
     * с 10 — смертельно: обожжено всё открытое тело. Тень (стена, холм, крыша) спасает целиком.
     */
    private static void burn(ServerLevel level, Detonation d, LivingEntity e, double q, @Nullable Entity owner) {
        if (q < ThermalModel.BURN_1) return;
        e.igniteForSeconds((float) Math.min(20, q));
        // без частиц вокруг (в первом лице они лезут в глаза), только значок
        e.addEffect(new MobEffectInstance(ModEffects.BURNS, (int) Math.min(20 * 600, q * 20 * 30), q >= ThermalModel.BURN_3 ? 1 : 0, false, false, true));
        if (q >= ThermalModel.BURN_3) {
            float dmg = q >= 10 ? Float.MAX_VALUE : (float) ((q - ThermalModel.BURN_3) * 4 + 8);
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_THERMAL, null, owner), dmg);
        }
    }

    /**
     * Видит ли сущность огненный шар: рядом — честный луч по блокам (стены, крыша), дальше — по карте высот
     * (холмы, дома), чтобы не гнать луч на километры.
     */
    public static boolean sees(Level level, Detonation d, LivingEntity e) {
        Vec3 eye = e.getEyePosition();
        Vec3 dir = d.burst().subtract(eye);
        double len = dir.length();
        Vec3 near = eye.add(dir.scale(Math.min(1, 48 / Math.max(len, 1e-3))));
        // луч по блокам — только по загруженным чанкам: сервер не должен грузить мир ради проверки
        if (loadedAlong(level, eye, near) && level.clip(new ClipContext(eye, near, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, e)).getType() != HitResult.Type.MISS) return false;
        return ThermalShadow.visible(level, d.burst(), near);
    }

    /** Все чанки вдоль луча готовы (шаг 4 блока — луч не проскочит угол чужого чанка незамеченным). */
    private static boolean loadedAlong(Level level, Vec3 from, Vec3 to) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 4));
        for (int i = 0; i <= steps; i++) {
            if (!Terrain.ready(level, BlockPos.containing(from.lerp(to, i / (double) steps)))) return false;
        }
        return true;
    }

    /**
     * Экранирование проникающей радиации (DESIGN §1.5): шагаем по лучу к точке подрыва на 64 блока
     * (не дальше загруженных чанков), каждый встреченный блок ослабляет по материалу.
     */
    public static double shielding(Level level, Vec3 from, Vec3 burst) {
        Vec3 dir = burst.subtract(from).normalize();
        int stone = 0, earth = 0, water = 0, wood = 0, glass = 0;
        BlockPos last = null;
        for (double s = 0.5; s < 64 && stone + earth + water + wood + glass < 16; s += 0.5) {
            BlockPos p = BlockPos.containing(from.add(dir.scale(s)));
            if (p.equals(last)) continue;
            last = p;
            if (!Terrain.ready(level, p)) break;
            BlockState b = level.getBlockState(p);
            if (b.isAir()) continue;
            if (!b.getFluidState().isEmpty()) water++;
            else if (b.is(BlockTags.LEAVES) || b.is(ModTags.SHATTERS)) glass++;
            else if (b.is(BlockTags.LOGS) || b.is(BlockTags.PLANKS)) wood++;
            else if (b.is(BlockTags.DIRT) || b.is(BlockTags.SAND) || b.is(net.minecraft.world.level.block.Blocks.GRAVEL)) earth++;
            else if (!b.getCollisionShape(level, p).isEmpty()) stone++;
        }
        return PromptRadiationModel.shielding(stone, earth, water, wood, glass);
    }

    /** Дальность, где доза проникающей радиации падает до {@code rem}, м (бисекция по монотонной модели). */
    private static double radiusForDose(double yieldKt, double rem) {
        double lo = 1, hi = 100_000;
        for (int i = 0; i < 60; i++) {
            double m = Math.sqrt(lo * hi);
            if (PromptRadiationModel.doseRem(m, yieldKt) > rem) lo = m;
            else hi = m;
        }
        return lo;
    }
}
