package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;
import ua.zentix.airstrike.util.Particles;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.UUID;

/**
 * Бетонобойная бомба (GBU-57-подобная). Падает по дуге с разгоном до 12.5 блока/тик и входит почти отвесно;
 * в грунте бурит: каждый блок тратит «энергию» по классу породы (грунт 12, камень 30, глубинный сланец 40,
 * бетон и кирпич 110, обсидиан 300, вода 5, прочее 25). Взрыватель: пустота после ≥4 блоков породы — подрыв
 * в полости через 3 тика; кончилась энергия, 70 блоков пути или непробиваемое — через 8 тиков.
 */
public class BunkerBusterEntity extends StrikeProjectile {
    public static final int PHASE_FALL = 0;
    public static final int PHASE_DRILL = 5;

    private static final EntityDataAccessor<Vector3f> DATA_ENTRY = SynchedEntityData.defineId(BunkerBusterEntity.class, EntityDataSerializers.VECTOR3);

    @Nullable
    private BlockPos goal;
    private int energy;
    private int traveled;
    private int fuse = -1;

    public BunkerBusterEntity(EntityType<? extends BunkerBusterEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    @Override
    protected double noseLength() {
        return 4.65;
    }

    @Override
    protected int maxAge() {
        return 300;
    }

    /** Точка входа в грунт (для дыма из скважины и выброса газов). */
    public Vec3 entry() {
        Vector3f v = entityData.get(DATA_ENTRY);
        return new Vec3(v.x, v.y, v.z);
    }

    public boolean isDrilling() {
        return phase() == PHASE_DRILL;
    }

    /** Сброс с бомбардировщика: нос на 10° вниз, 6 блоков/тик. */
    public void drop(Vec3 pos, float yaw, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        super.launch(pos, new Target.Point(surface), surface, owner);
        flight.set(yaw, 10);
        moveTo(pos.x, pos.y, pos.z, yaw, 10);
        this.goal = goal;
        this.speed = 6.0;
        setPhase(PHASE_FALL);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (phase() == PHASE_DRILL) {
            drillTick(level);
            return;
        }
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        flight.arcPitch(b.pitch(), speed, b.distance(), 7, 0.8);
        speed = Math.min(12.5, speed + 0.3);
        if (b.horizontal() > 8) flight.steerYaw(b.yaw(), 0.15, 3.0, 0.3);
        advance(level, aim, 5.3);
    }

    // ---------------------------------------------------------------- вход в грунт

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        if (phase() == PHASE_DRILL) return;
        setPos(point.x, point.y, point.z);
        setPhase(PHASE_DRILL);
        energy = AirstrikeConfig.SERVER.bunkerEnergy.get();
        traveled = 0;
        fuse = -1;
        speed = 0;
        entityData.set(DATA_ENTRY, new Vector3f((float) point.x, (float) point.y + 1, (float) point.z));
        // цель в пещере — пробиваемся к ней (не положе 45°), иначе почти отвесно вниз (не положе 70°)
        if (goal != null) {
            float[] a = FlightController.anglesTo(point, Vec3.atCenterOf(goal));
            flight.set(a[0], Math.max(45, a[1]));
        } else {
            flight.set(flight.yaw(), Math.max(70, flight.pitch()));
        }
        setYRot(flight.yaw());
        setXRot(flight.pitch());
        Warheads.bunkerEntry(level, point, this, ownerId());
    }

    // ---------------------------------------------------------------- бурение

    private void drillTick(ServerLevel level) {
        if (fuse >= 0) {
            if (--fuse <= 0) detonate(level);
            return;
        }
        // сколько блоков за тик: чем меньше осталось энергии, тем медленнее
        int steps = Math.min(4, energy / 300 + 1);
        for (int i = 0; i < steps && fuse < 0; i++) step(level);
    }

    private void step(ServerLevel level) {
        Vec3 dir = flight.forward();
        Vec3 next = position().add(dir);
        BlockPos pos = BlockPos.containing(next);
        BlockState state = level.getBlockState(pos);
        RockClass cls = RockClass.of(state);

        if (cls == RockClass.PASSABLE) {
            if (traveled >= 4) {
                // датчик пустот: вошла в полость после ≥4 блоков породы — подрыв внутри неё
                stepTo(next);
                Vec3 further = next.add(dir);
                if (RockClass.of(level.getBlockState(BlockPos.containing(further))) == RockClass.PASSABLE) stepTo(further);
                fuse = 3;
            } else {
                stepTo(next);
            }
            return;
        }
        if (cls == RockClass.STOP) {
            fuse = 8;
            return;
        }
        energy -= cls.cost;
        traveled++;
        carve(level, pos);
        stepTo(next);
        if (goal != null) home();
        wobble(level.random);
        if (energy <= 0 || traveled >= 70) fuse = 8;
    }

    private void stepTo(Vec3 p) {
        setPos(p.x, p.y, p.z);
        setYRot(flight.yaw());
        setXRot(flight.pitch());
    }

    /** Довод на цель под землёй: 30% поправки за блок. */
    private void home() {
        float[] a = FlightController.anglesTo(position(), Vec3.atCenterOf(goal));
        float yaw = flight.yaw() + Mth.wrapDegrees(a[0] - flight.yaw()) * 0.3f;
        float pitch = flight.pitch() + (a[1] - flight.pitch()) * 0.3f;
        flight.set(yaw, pitch);
    }

    /** В породе боеприпас «гуляет»: случайные отклонения до 2.5° по курсу и 2° по тангажу. */
    private void wobble(RandomSource r) {
        float yaw = flight.yaw() + (r.nextInt(501) - 250) / 100f;
        float pitch = Mth.clamp(flight.pitch() + (r.nextInt(401) - 200) / 100f, 45, 89);
        flight.set(yaw, pitch);
    }

    /**
     * Канал бомбы: столб 1×3 и соседние блоки (65%), изредка куб 3×3×3 (15%); стенки канала дробятся
     * (20%: камень → булыжник, сланец → булыжный сланец, грунт → гравий), сверху может осыпаться гравий (10%).
     * Кто оказался в канале — погибает.
     */
    private void carve(ServerLevel level, BlockPos c) {
        RandomSource r = level.random;
        for (int dy = -1; dy <= 1; dy++) clear(level, c.offset(0, dy, 0));
        for (BlockPos n : new BlockPos[]{c.east(), c.west(), c.south(), c.north()}) {
            if (r.nextFloat() < 0.65f) clear(level, n);
        }
        if (r.nextFloat() < 0.15f) {
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) clear(level, p);
        }
        if (r.nextFloat() < 0.2f) wall(level, c.offset(2, -1, -1), c.offset(2, 1, 1), ModTags.BB_ROCK, Blocks.COBBLESTONE.defaultBlockState());
        if (r.nextFloat() < 0.2f) wall(level, c.offset(-2, -1, -1), c.offset(-2, 1, 1), ModTags.BB_ROCK, Blocks.COBBLESTONE.defaultBlockState());
        if (r.nextFloat() < 0.2f) wall(level, c.offset(-1, -1, 2), c.offset(1, 1, 2), ModTags.BB_DEEP, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
        if (r.nextFloat() < 0.2f) wall(level, c.offset(-1, -1, -2), c.offset(1, 1, -2), ModTags.BB_SOIL, Blocks.GRAVEL.defaultBlockState());
        if (r.nextFloat() < 0.1f && level.getBlockState(c.above(2)).isAir()) {
            level.setBlock(c.above(2), Blocks.GRAVEL.defaultBlockState(), 3);
        }
        Vec3 cv = Vec3.atCenterOf(c);
        level.sendParticles(ParticleTypes.POOF, cv.x, cv.y, cv.z, 3, 0.4, 0.4, 0.4, 0.05);
        for (Player p : level.getEntitiesOfClass(Player.class, new AABB(c).inflate(1.8), p -> p.distanceToSqr(cv) <= 1.8 * 1.8)) {
            p.hurt(ModDamageTypes.source(level, ModDamageTypes.KINETIC, this, ownerPlayer()), 100);
        }
    }

    private static void clear(ServerLevel level, BlockPos p) {
        if (level.getBlockState(p).is(ModTags.DRILLABLE)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void wall(ServerLevel level, BlockPos from, BlockPos to, net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag, BlockState with) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockState(p).is(tag)) level.setBlock(p, with, 3);
        }
    }

    private void detonate(ServerLevel level) {
        Vec3 at = position();
        Vec3 entry = entry();
        discard();
        Warheads.bunker(level, at, entry, this, ownerId());
    }

    /** Классы пород из датапака (теги airstrike:bb_*). */
    enum RockClass {
        PASSABLE(0), SOFT(12), ROCK(30), DEEP(40), HARD(110), VERY_HARD(300), STOP(0), FLUID(5), OTHER(25);

        final int cost;

        RockClass(int cost) {
            this.cost = cost;
        }

        static RockClass of(BlockState s) {
            // порядок как в bunker/step: последний подходящий тег побеждает
            RockClass c = OTHER;
            if (s.is(ModTags.PASSABLE)) c = PASSABLE;
            if (s.is(ModTags.BB_SOFT)) c = SOFT;
            if (s.is(ModTags.BB_ROCK)) c = ROCK;
            if (s.is(ModTags.BB_DEEP)) c = DEEP;
            if (s.is(ModTags.BB_HARD)) c = HARD;
            if (s.is(ModTags.BB_VHARD)) c = VERY_HARD;
            if (s.is(ModTags.BB_STOP)) c = STOP;
            if (s.is(ModTags.BB_FLUID)) c = FLUID;
            return c;
        }
    }

    // ---------------------------------------------------------------- клиент

    @Override
    protected void clientTick() {
        Level l = level();
        if (phase() == PHASE_DRILL) {
            Vec3 e = entry();
            Particles.burst(l, ParticleTypes.CAMPFIRE_COSY_SMOKE, e.x, e.y + 0.3, e.z, 0.3, 0.2, 0.3, 0.02, 3);
            return;
        }
        Vec3 p = position();
        float yr = getYRot(), xr = getXRot();
        Particles.burst(l, ParticleTypes.CLOUD, Local.at(p, yr, xr, 0, 0, -5.2), 0.08, 0.08, 0.08, 0.004, 3);
        Particles.burst(l, ParticleTypes.CAMPFIRE_COSY_SMOKE, Local.at(p, yr, xr, 0, 0, -5.5), 0.05, 0.05, 0.05, 0.002, 1);
        // у звукового барьера — конденсационный «воротник» у головы
        if (speed() >= 8) Particles.burst(l, ParticleTypes.WHITE_SMOKE, Local.at(p, yr, xr, 0, 0, 2.5), 0.6, 0.6, 0.6, 0.02, 8);
    }

    // ---------------------------------------------------------------- данные

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ENTRY, new Vector3f());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
        energy = tag.getInt("energy");
        traveled = tag.getInt("traveled");
        fuse = tag.contains("fuse") ? tag.getInt("fuse") : -1;
        entityData.set(DATA_ENTRY, new Vector3f(tag.getFloat("entry_x"), tag.getFloat("entry_y"), tag.getFloat("entry_z")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
        tag.putInt("energy", energy);
        tag.putInt("traveled", traveled);
        tag.putInt("fuse", fuse);
        Vec3 e = entry();
        tag.putFloat("entry_x", (float) e.x);
        tag.putFloat("entry_y", (float) e.y);
        tag.putFloat("entry_z", (float) e.z);
    }
}
