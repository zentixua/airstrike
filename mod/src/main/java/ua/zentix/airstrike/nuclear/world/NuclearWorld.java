package ua.zentix.airstrike.nuclear.world;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModSounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Ядерные процессы измерения во время игры: фронт ударной волны по сущностям и аппаратам, очередь
 * повреждений чанков и воронки под общим бюджетом времени. Не сохраняется: всё выводится из
 * {@link NuclearEvents} и отметок на чанках.
 */
public final class NuclearWorld {
    private static final Map<ServerLevel, NuclearWorld> WORLDS = new WeakHashMap<>();

    private final ScarQueue scars = new ScarQueue();
    private final List<CraterJob> craters = new ArrayList<>();
    /** Докуда (радиус, блоки) фронт уже прошёлся по сущностям: прямой фронт и обратный ветер. */
    private final Map<Integer, double[]> fronts = new HashMap<>();
    private long maxWorkNanos;

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

    /** Самая долгая обработка очередей за один тик, нс (для проверки бюджета). */
    public long maxWorkNanos() {
        return maxWorkNanos;
    }

    // ---------------------------------------------------------------- события

    public void onDetonation(ServerLevel level, Detonation d) {
        int view = level.getServer().getPlayerList().getViewDistance() + 1;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (ServerPlayer p : level.players()) {
            int pcx = p.chunkPosition().x, pcz = p.chunkPosition().z;
            for (int cx = pcx - view; cx <= pcx + view; cx++) {
                for (int cz = pcz - view; cz <= pcz + view; cz++) offer(level, cx, cz, d, seen);
            }
        }
        for (long c : level.getForcedChunks()) offer(level, net.minecraft.world.level.ChunkPos.getX(c), net.minecraft.world.level.ChunkPos.getZ(c), d, seen);
        BlockPos spawn = level.getSharedSpawnPos();
        for (int cx = (spawn.getX() >> 4) - 2; cx <= (spawn.getX() >> 4) + 2; cx++) {
            for (int cz = (spawn.getZ() >> 4) - 2; cz <= (spawn.getZ() >> 4) + 2; cz++) offer(level, cx, cz, d, seen);
        }
        if (d.surface() && AirstrikeConfig.SERVER.nukeCrater.get() && AirstrikeConfig.SERVER.nukeBlockDamage.get()
                && ua.zentix.airstrike.nuclear.model.CraterModel.formsCrater(d.hobMetres(), d.yieldKt())) {
            craters.add(new CraterJob(level, d));
        }
    }

    private void offer(ServerLevel level, int cx, int cz, Detonation d, java.util.Set<Long> seen) {
        if (!seen.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) scars.offer(chunk, d);
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
    }

    // ---------------------------------------------------------------- тик

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Detonation d : NuclearEvents.get(level).detonations()) {
            long since = now - d.gameTime();
            if (since >= 0 && since <= d.arrivalTicks(d.radiusMax()) + positivePhaseTicks(d) + 2) front(level, d, since);
            else fronts.remove(d.id());
        }
        long start = System.nanoTime();
        long deadline = start + AirstrikeConfig.SERVER.nukeTimeBudgetMs.get() * 1_000_000L;
        try {
            while (!craters.isEmpty() && System.nanoTime() < deadline) {
                CraterJob.Step s = craters.getFirst().step(level, level.random);
                if (s == CraterJob.Step.DONE) craters.removeFirst();
                else if (s == CraterJob.Step.WAIT) break;
            }
            scars.work(level, now, deadline, level.random);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Ядерные разрушения упали с ошибкой; очереди сброшены", e);
            clear(level);
        }
        maxWorkNanos = Math.max(maxWorkNanos, System.nanoTime() - start);
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
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof LivingEntity living) || !e.isAlive() || e.isSpectator() || e.isPassenger()) continue;
            if (e instanceof Player p && p.getAbilities().invulnerable) continue;
            double dist = e.position().distanceTo(d.burst());
            if (dist >= done[0] && dist < r) hit(level, d, living, dist);
            else if (dist >= done[1] && dist < back) suck(d, living);
        }
        sweepAircraft(level, d, done[0], r);
        done[0] = Math.max(done[0], r);
        done[1] = Math.max(done[1], back);
    }

    private static void hit(ServerLevel level, Detonation d, LivingEntity e, double dist) {
        double kpa = d.overpressureKpa(e.position());
        double psi = BlastModel.psi(kpa);
        if (psi < 0.3) return;
        boolean cover = !level.canSeeSky(e.blockPosition().above()) && !ua.zentix.airstrike.nuclear.NuclearWarhead.sees(level, d, e);
        float dmg = psi >= 20 ? Float.MAX_VALUE : (float) (3.2 * psi);
        if (cover) dmg *= 0.3f;
        if (psi >= 0.5) e.hurt(ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_BLAST, null, null), dmg);
        double q = BlastModel.dynamicPressureKpa(kpa);
        double v = Math.min(4.0, 0.6 * Math.sqrt(q)) * (cover ? 0.2 : 1) * (1 - e.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE));
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
            level.explode(null, ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_BLAST, null, null), null,
                    nearest.x, nearest.y, nearest.z, power, false, Level.ExplosionInteraction.TNT,
                    ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, ModSounds.SILENT);
        }
    }
}
