package ua.zentix.airstrike.nuclear.world;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Фронт ударной волны по сущностям и аппаратам (DESIGN-nuke §4): кольцо между прошлым и текущим радиусом — урон
 * по давлению, бросок от эпицентра по скоростному напору; через положительную фазу — лёгкий обратный ветер к центру.
 * <p>
 * Где фронт за тик, считается каждый тик (снимок сущностей в круге — доли миллисекунды), а удары по тем, кого он
 * накрыл, — очередь под общим бюджетом ({@link WorkClock}), ближние первыми. Удар — это урон, а урон — смерть,
 * лут и обработчики смерти всех модов сборки: у эпицентра волна за один тик накрывает сотни мобов, и разом это
 * сотня миллисекунд тика (у хоста — 84 мс в тике подрыва). Очередь ждёт, пока по этому подрыву пройдёт световой
 * импульс: свет приходит раньше волны, и кого волна убьёт, тот уже получил свои ожоги и дозу.
 * <p>
 * Аппараты Create Aeronautics (DESIGN §3.6) волна бьёт ванильным взрывом в ближайшей к эпицентру точке аппарата:
 * лучи взрыва Sable проводит и по блокам аппарата — так ломается корпус и толкается тело. Взрыв — тоже в очереди
 * под бюджетом (свет им не нужен — не ждут его), ломает только блоки аппаратов (земля — дело {@link ScarQueue}
 * по давлению), сущностей не трогает (их волна бьёт сама, по давлению) и слушается {@code block_damage}.
 */
final class BlastFront {
    /** С какого давления волна убивает сразу (вне укрытия). */
    private static final double LETHAL_PSI = 12;
    /** С какого давления волна бьёт по аппарату. */
    private static final double AIRCRAFT_PSI = 3;
    /**
     * Наибольшая сила взрыва по аппарату. Лучи силы 16 проходят до ~20 блоков корпуса; каждый шаг луча Sable
     * проверяет пересечение с аппаратами, так что цена взрыва растёт с силой, а прежние 60 — это почти 100 блоков
     * лучей из 1352 направлений.
     */
    private static final float MAX_AIRCRAFT_POWER = 16;

    private record Hit(Detonation d, LivingEntity entity) {}

    /** Взрыв по аппарату: в ближайшей к эпицентру точке аппарата, когда его накрыл фронт. */
    private record AircraftHit(Detonation d, Vec3 at, float power) {}

    /**
     * Докуда (радиус, блоки) фронт уже прошёлся по сущностям: прямой фронт и обратный ветер. Не сохраняется: после
     * перезапуска (или новой {@code BlastFront}) посреди волны отсчёт — от фронта прошлого тика, кто уже внутри,
     * второй раз не бьётся.
     */
    private final Map<Integer, double[]> reached = new HashMap<>();
    /** Кого фронт накрыл, а удар ещё не нанесён: по тикам прихода, в тике — от ближних к дальним. */
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    /** Аппараты, которые фронт накрыл, а взрыва по ним ещё не было: по тикам прихода. */
    private final ArrayDeque<AircraftHit> aircraft = new ArrayDeque<>();

    /** Удары, которые ждут бюджета. */
    int pending() {
        return hits.size() + aircraft.size();
    }

    /** Где фронт этого тика: кого накрыл (сущности и аппараты) — в очередь, обратный ветер — сразу (он дёшев). */
    void advance(ServerLevel level, List<Detonation> detonations, long now) {
        Set<Integer> active = new HashSet<>();
        for (Detonation d : detonations) {
            long since = now - d.gameTime();
            if (since >= 0 && since <= d.arrivalTicks(d.radiusMax()) + positivePhaseTicks(d) + 2) {
                advance(level, d, since);
                active.add(d.id());
            }
        }
        reached.keySet().retainAll(active);
    }

    private void advance(ServerLevel level, Detonation d, long since) {
        double[] done = reached.computeIfAbsent(d.id(), k -> new double[]{d.frontRadius(since - 1), d.frontRadius(since - 1 - positivePhaseTicks(d))});
        double r = d.frontRadius(since);
        double back = d.frontRadius(since - positivePhaseTicks(d));
        if (r <= done[0] && back <= done[1]) return;
        // снимок списка: удар может убить моба, и из него выпадет лут — живую карту сущностей трогать нельзя
        double reach = Math.max(r, back);
        List<LivingEntity> ring = new ArrayList<>();
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(d.burst(), reach * 2, reach * 2, reach * 2))) {
            if (!living.isAlive() || living.isSpectator() || living.isPassenger()) continue;
            double dist = living.position().distanceTo(d.burst());
            if (dist >= done[0] && dist < r) ring.add(living);
            else if (dist >= done[1] && dist < back) suck(d, living);
        }
        ring.sort(Comparator.comparingDouble(e -> e.distanceToSqr(d.burst())));
        for (LivingEntity e : ring) hits.add(new Hit(d, e));
        sweepAircraft(level, d, done[0], r);
        done[0] = Math.max(done[0], r);
        done[1] = Math.max(done[1], back);
    }

    /**
     * Удары из очереди, сколько успеем за бюджет.
     *
     * @param lit по подрыву с этим номером световой импульс уже прошёл
     */
    void work(ServerLevel level, WorkClock clock, IntPredicate lit) {
        // аппараты — первыми: взрыв там, где аппарат был под фронтом, пока он не улетел
        while (!aircraft.isEmpty() && clock.canStart()) {
            long t0 = clock.begin();
            blast(level, aircraft.removeFirst());
            clock.end(t0);
        }
        while (!hits.isEmpty() && lit.test(hits.peekFirst().d().id()) && clock.canStart()) {
            Hit h = hits.removeFirst();
            // за время в очереди моб мог умереть, выгрузиться или сесть в лодку — как при снимке
            LivingEntity e = h.entity();
            if (!e.isAlive() || e.isSpectator() || e.isPassenger()) continue;
            long t0 = clock.begin();
            hit(level, h.d(), e);
            clock.end(t0);
        }
    }

    /** Отбой. */
    void clear() {
        reached.clear();
        dropHits();
    }

    /** Удары в очереди — сбросить; докуда фронт прошёл, остаётся (иначе он заново ударил бы всех внутри). */
    void dropHits() {
        hits.clear();
        aircraft.clear();
    }

    private static double positivePhaseTicks(Detonation d) {
        return BlastModel.positivePhaseSeconds(d.yieldKt()) * 20 * d.scale();
    }

    /**
     * Удар волны. Смертельно с 12 psi (у людей в жизни — обрушение зданий и удар о препятствия; 20 psi — никто не
     * выживает), 5 psi — почти смертельно, 1–3 psi — ранения стеклом и броском. В укрытии (нет прямой видимости
     * и крыша над головой) урон ×0.3, бросок ×0.2. Творческий режим урона не получает, но волна швыряет и его.
     */
    private static void hit(ServerLevel level, Detonation d, LivingEntity e) {
        double kpa = d.overpressureKpa(e.position());
        double psi = BlastModel.psi(kpa);
        if (psi < 0.3) return;
        boolean cover = !Detonation.underOpenSky(level, e.getEyePosition()) && !NuclearWarhead.sees(level, d, e);
        boolean immune = e instanceof Player p && p.getAbilities().invulnerable;
        float dmg = psi >= LETHAL_PSI ? Float.MAX_VALUE : (float) (1.6 * Math.pow(psi, 1.35));
        if (cover) dmg *= 0.3f;
        if (psi >= 0.7 && !immune) e.hurt(ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_BLAST, null, null), dmg);
        double q = BlastModel.dynamicPressureKpa(kpa);
        double v = Math.min(6.0, 0.8 * Math.sqrt(q)) * (cover ? 0.2 : 1) * (1 - e.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE));
        Vec3 away = new Vec3(e.getX() - d.burst().x, 0, e.getZ() - d.burst().z);
        away = away.lengthSqr() < 1e-4 ? Vec3.ZERO : away.normalize();
        e.push(away.x * v, 0.3 * v, away.z * v);
        e.hurtMarked = true;
    }

    /** Обратный ветер: воздух возвращается к эпицентру и тянет за собой. */
    private static void suck(Detonation d, LivingEntity e) {
        double psi = d.psi(e.position());
        if (psi < 1) return;
        Vec3 to = new Vec3(d.burst().x - e.getX(), 0, d.burst().z - e.getZ());
        if (to.lengthSqr() < 1e-4) return;
        to = to.normalize().scale(Math.min(0.6, 0.08 * psi));
        e.push(to.x, 0.05, to.z);
        e.hurtMarked = true;
    }

    /** Аппараты, чья ближайшая к эпицентру точка — в кольце фронта этого тика и под давлением от 3 psi, — в очередь. */
    private void sweepAircraft(ServerLevel level, Detonation d, double from, double to) {
        if (to <= from) return;
        for (SubLevelAccess sub : SubLevels.near(level, d.burst(), to)) {
            AABB box = sub.boundingBox().toMojang();
            Vec3 nearest = new Vec3(Mth.clamp(d.burst().x, box.minX, box.maxX), Mth.clamp(d.burst().y, box.minY, box.maxY),
                    Mth.clamp(d.burst().z, box.minZ, box.maxZ));
            double dist = nearest.distanceTo(d.burst());
            if (dist < from || dist >= to) continue;
            double psi = d.psi(nearest);
            if (psi < AIRCRAFT_PSI) continue;
            aircraft.add(new AircraftHit(d, nearest, (float) Mth.clamp(6 + (psi - AIRCRAFT_PSI) * 1.15, 6, MAX_AIRCRAFT_POWER)));
        }
    }

    /**
     * Взрыв по аппарату. Без {@code block_damage} — без разрушений ({@code NONE}): Sable всё равно толкает аппарат
     * по лучам взрыва. Лучи ванильного взрыва читают блоки — только по готовым чанкам.
     */
    private static void blast(ServerLevel level, AircraftHit a) {
        boolean blocks = AirstrikeConfig.SERVER.nukeBlockDamage.get();
        Warheads.whenReady(level, a.at(), Warheads.reach(a.power()), l -> l.explode(null,
                ModDamageTypes.source(l, ModDamageTypes.NUCLEAR_BLAST, null, null), new AircraftOnly(l),
                a.at().x, a.at().y, a.at().z, a.power(), false,
                blocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE,
                ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, ModSounds.SILENT));
    }

    /**
     * Взрыв по аппарату ломает только блоки аппаратов (они лежат в плотах Sable) и не трогает сущности: ни урона,
     * ни броска — сущности волна уже бьёт по давлению ({@link #hit}).
     */
    private static final class AircraftOnly extends ExplosionDamageCalculator {
        private final Level level;

        AircraftOnly(Level level) {
            this.level = level;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader, BlockPos pos, BlockState state, float power) {
            return !state.isAir() && SubLevels.isInPlot(level, pos.getCenter());
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 0;
        }
    }
}
