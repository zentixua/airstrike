package ua.zentix.airstrike.nuclear.world;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.nuclear.model.CraterModel;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModSounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Ядерные процессы измерения во время игры: фронт ударной волны по сущностям и аппаратам, очередь
 * повреждений чанков и воронки под общим бюджетом времени. Сам не сохраняется: всё выводится из
 * {@link NuclearEvents} (подрывы, ход воронок) и отметок на чанках.
 */
public final class NuclearWorld {
    private static final Map<ServerLevel, NuclearWorld> WORLDS = new WeakHashMap<>();

    private final ScarQueue scars = new ScarQueue();
    private final List<CraterJob> craters = new ArrayList<>();
    /** Докуда (радиус, блоки) фронт уже прошёлся по сущностям: прямой фронт и обратный ветер. */
    private final Map<Integer, double[]> fronts = new HashMap<>();
    private long maxWorkNanos;
    private long lastFrontNanos, lastCraterNanos, lastScarNanos;
    /** Недорытые воронки из сохранения подхвачены (после загрузки мира). */
    private boolean restored;

    private NuclearWorld() {}

    public static NuclearWorld get(ServerLevel level) {
        return WORLDS.computeIfAbsent(level, l -> new NuclearWorld());
    }

    public static void forget(ServerLevel level) {
        NuclearWorld w = WORLDS.remove(level);
        if (w != null) w.craters.forEach(c -> c.release(level));
    }

    public int queuedChunks() {
        return scars.size();
    }

    public int craterJobs() {
        return craters.size();
    }

    /** Сколько заняли в последнем тике фронт по сущностям и аппаратам, воронки и очередь чанков, нс. */
    public long[] lastNanos() {
        return new long[]{lastFrontNanos, lastCraterNanos, lastScarNanos};
    }

    /** Самая долгая обработка очередей за один тик, нс (для проверки бюджета). */
    public long maxWorkNanos() {
        return maxWorkNanos;
    }

    // ---------------------------------------------------------------- события

    /** Все загруженные сейчас чанки — в очередь (дальше радиуса очередь их сама отсеет); остальные — при загрузке. */
    public void onDetonation(ServerLevel level, Detonation d) {
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(holder.getPos().x, holder.getPos().z);
            if (chunk != null) scars.offer(chunk, d);
        }
        if (d.surface() && AirstrikeConfig.SERVER.nukeCrater.get() && AirstrikeConfig.SERVER.nukeBlockDamage.get()
                && CraterModel.formsCrater(d.hobMetres(), d.yieldKt())) {
            craters.add(new CraterJob(d, 0));
            NuclearEvents.get(level).craterProgress(d.id(), 0, false);
        }
    }

    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        for (Detonation d : NuclearEvents.get(level).detonations()) scars.offer(chunk, d);
    }

    public void onChunkUnload(LevelChunk chunk) {
        scars.drop(chunk.getPos());
    }

    /** Отбой: очереди остановлены (разрушенное не возвращается). */
    public void clear(ServerLevel level) {
        scars.clear();
        craters.forEach(c -> c.release(level));
        craters.clear();
        fronts.clear();
    }

    // ---------------------------------------------------------------- тик

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        NuclearEvents events = NuclearEvents.get(level);
        if (!restored) restore(events);
        java.util.Set<Integer> active = new java.util.HashSet<>();
        long frontStart = System.nanoTime();
        for (Detonation d : events.detonations()) {
            long since = now - d.gameTime();
            if (since >= 0 && since <= d.arrivalTicks(d.radiusMax()) + positivePhaseTicks(d) + 2) {
                front(level, d, since);
                active.add(d.id());
            }
        }
        fronts.keySet().retainAll(active);
        lastFrontNanos = System.nanoTime() - frontStart;
        if (now % 1200 == 0) scars.retainBudgets(events.detonations().stream().map(Detonation::id).collect(java.util.stream.Collectors.toSet()));
        long start = System.nanoTime();
        long deadline = start + AirstrikeConfig.SERVER.nukeTimeBudgetMs.get() * 1_000_000L;
        try {
            while (!craters.isEmpty() && System.nanoTime() < deadline) {
                CraterJob job = craters.getFirst();
                CraterJob.Step s = job.step(level, level.random);
                events.craterProgress(job.detonation().id(), job.progress(), s == CraterJob.Step.DONE);
                if (s == CraterJob.Step.DONE) craters.removeFirst();
                else if (s == CraterJob.Step.WAIT) break;
            }
            long scarStart = System.nanoTime();
            lastCraterNanos = scarStart - start;
            scars.work(level, now, deadline, level.random);
            lastScarNanos = System.nanoTime() - scarStart;
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Ядерные разрушения упали с ошибкой; очереди сброшены", e);
            clear(level);
        }
        maxWorkNanos = Math.max(maxWorkNanos, System.nanoTime() - start);
    }

    /** После загрузки мира: недорытые воронки — дорыть с того чанка, где остановились. */
    private void restore(NuclearEvents events) {
        restored = true;
        events.craters().forEach((id, done) -> events.detonations().stream().filter(d -> d.id() == id).findFirst()
                .ifPresent(d -> craters.add(new CraterJob(d, done))));
    }

    private static boolean loadedAround(ServerLevel level, Vec3 c, double r) {
        for (int cx = Mth.floor(c.x - r) >> 4; cx <= Mth.floor(c.x + r) >> 4; cx++) {
            for (int cz = Mth.floor(c.z - r) >> 4; cz <= Mth.floor(c.z + r) >> 4; cz++) {
                if (!Terrain.ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    private static double positivePhaseTicks(Detonation d) {
        return BlastModel.positivePhaseSeconds(d.yieldKt()) * 20 * d.scale();
    }

    /**
     * Фронт по сущностям (DESIGN §4): кольцо между прошлым и текущим радиусом — урон по давлению, бросок от
     * эпицентра по скоростному напору; через положительную фазу — лёгкий обратный ветер к центру.
     * В укрытии (нет прямой видимости и крыша над головой) урон ×0.3, толчок ×0.2.
     */
    private void front(ServerLevel level, Detonation d, long since) {
        double[] done = fronts.computeIfAbsent(d.id(), k -> new double[]{0, 0});
        double r = d.frontRadius(since);
        double back = d.frontRadius(since - positivePhaseTicks(d));
        if (r <= done[0] && back <= done[1]) return;
        // снимок списка: удар может убить моба, и из него выпадет лут — живую карту сущностей трогать нельзя
        double reach = Math.max(r, back);
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(d.burst(), reach * 2, reach * 2, reach * 2))) {
            if (!living.isAlive() || living.isSpectator() || living.isPassenger()) continue;
            double dist = living.position().distanceTo(d.burst());
            if (dist >= done[0] && dist < r) hit(level, d, living, dist);
            else if (dist >= done[1] && dist < back) suck(d, living);
        }
        sweepAircraft(level, d, done[0], r);
        done[0] = Math.max(done[0], r);
        done[1] = Math.max(done[1], back);
    }

    /**
     * Удар волны. Смертельно с 12 psi (у людей в жизни — обрушение зданий и удар о препятствия; 20 psi — никто не
     * выживает), 5 psi — почти смертельно, 1–3 psi — ранения стеклом и броском. В укрытии урон ×0.3, бросок ×0.2.
     * Творческий режим урона не получает, но волна швыряет и его.
     */
    private static void hit(ServerLevel level, Detonation d, LivingEntity e, double dist) {
        double kpa = d.overpressureKpa(e.position());
        double psi = BlastModel.psi(kpa);
        if (psi < 0.3) return;
        boolean cover = !Detonation.underOpenSky(level, e.getEyePosition()) && !ua.zentix.airstrike.nuclear.NuclearWarhead.sees(level, d, e);
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

    /** С какого давления волна убивает сразу (вне укрытия). */
    private static final double LETHAL_PSI = 12;

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

    /**
     * Аппараты Create Aeronautics (DESIGN §3.6): где давление ≥ 3 psi, в ближайшей к эпицентру точке аппарата —
     * ванильный взрыв с силой по давлению: Sable сам ломает его блоки и толкает корпус.
     */
    private static void sweepAircraft(ServerLevel level, Detonation d, double from, double to) {
        if (to <= from) return;
        for (SubLevelAccess sub : SubLevels.near(level, d.burst(), to)) {
            AABB box = sub.boundingBox().toMojang();
            Vec3 nearest = new Vec3(Mth.clamp(d.burst().x, box.minX, box.maxX), Mth.clamp(d.burst().y, box.minY, box.maxY),
                    Mth.clamp(d.burst().z, box.minZ, box.maxZ));
            double dist = nearest.distanceTo(d.burst());
            if (dist < from || dist >= to) continue;
            double psi = d.psi(nearest);
            if (psi < 3) continue;
            float power = (float) Mth.clamp(6 + (psi - 3) * 1.15, 6, 60);
            // лучи ванильного взрыва читают блоки — все чанки вокруг должны быть уже загружены
            if (!loadedAround(level, nearest, power * 1.5 + 2)) continue;
            level.explode(null, ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_BLAST, null, null), null,
                    nearest.x, nearest.y, nearest.z, power, false, Level.ExplosionInteraction.TNT,
                    ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, ModSounds.SILENT);
        }
    }
}
