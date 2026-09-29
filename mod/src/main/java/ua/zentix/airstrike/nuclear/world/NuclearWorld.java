package ua.zentix.airstrike.nuclear.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.radiation.MobFallout;
import ua.zentix.airstrike.nuclear.model.CraterModel;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ядерные процессы измерения во время игры: фронт ударной волны по сущностям и аппаратам; световой импульс
 * и проникающая радиация по сущностям, очередь повреждений чанков, воронки и осадки у мобов — под общим бюджетом времени
 * (один на тик сервера, для всех измерений: {@link #clock(MinecraftServer)}).
 * В тике подрыва — ничего тяжёлого: и снимки (сущности и чанки в радиусе), и сама работа идут под бюджетом.
 * Сам не сохраняется: всё выводится из {@link NuclearEvents} (подрывы, ход воронок) и отметок на чанках;
 * импульс по сущностям после перезапуска не повторяется (он длится доли секунды).
 */
public final class NuclearWorld {
    /** Медленный ядерный тик пишется в лог не чаще, тиков. */
    private static final int SLOW_LOG_PERIOD = 100;

    private final ScarQueue scars = new ScarQueue();
    private final List<PulseJob> pulses = new ArrayList<>();
    private final List<CraterJob> craters = new ArrayList<>();
    private final MobFallout mobFallout = new MobFallout();
    private final BlastFront blast = new BlastFront();
    private long lastFrontNanos, lastPulseNanos, lastCraterNanos, lastScarNanos, lastFalloutNanos;
    /** Недорытые воронки из сохранения подхвачены (после загрузки мира). */
    private boolean restored;
    /** Игровое время мира, когда медленный тик в последний раз писался в лог. */
    private long lastSlowLog = Long.MIN_VALUE / 2;

    /** Для {@link ModAttachments#NUCLEAR_WORLD}: своё у каждого мира, живёт, пока мир загружен, не сохраняется. */
    public NuclearWorld() {}

    public static NuclearWorld get(ServerLevel level) {
        return level.getData(ModAttachments.NUCLEAR_WORLD);
    }

    public int queuedChunks() {
        return scars.size();
    }

    public int craterJobs() {
        return craters.size();
    }

    /** Подрывы, чей световой импульс ещё идёт по сущностям. */
    public int pulseJobs() {
        return pulses.size();
    }

    /** Сколько заняли в последнем тике фронт по сущностям и аппаратам, свет и радиация, воронки, очередь чанков и осадки у мобов, нс. */
    public long[] lastNanos() {
        return new long[]{lastFrontNanos, lastPulseNanos, lastCraterNanos, lastScarNanos, lastFalloutNanos};
    }

    /**
     * Часы бюджета ядерной работы — одни на сервер: бюджет {@code destruction_ms_per_tick} — на тик сервера, а не на
     * измерение. Живут в верхнем мире (как общие данные сервера в ванили): он есть, пока жив сервер.
     */
    public static WorkClock clock(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.NUCLEAR_CLOCK);
    }

    /** Подменить часы бюджета (проверки — считающими, {@link WorkClock#counting}). */
    public static void useClock(MinecraftServer server, WorkClock clock) {
        server.overworld().setData(ModAttachments.NUCLEAR_CLOCK, clock);
    }

    /** Медленный тик — в лог не чаще раза в 5 с (tools/logscan.py): true — пора писать, отметка поставлена. */
    public boolean slowLogDue(long now) {
        if (now - lastSlowLog < SLOW_LOG_PERIOD) return false;
        lastSlowLog = now;
        return true;
    }

    // ---------------------------------------------------------------- события

    /**
     * Подрыв: сущности в радиусе света и радиации и загруженные сейчас чанки в радиусе разрушений — к обработке
     * под бюджетом, даже их снимки (чанки, загруженные позже, — при загрузке). В тике подрыва — ничего тяжёлого.
     *
     * @param owner кто запустил (урон записывается на него)
     */
    public void onDetonation(ServerLevel level, Detonation d, @Nullable UUID owner) {
        pulses.add(new PulseJob(level, d, owner));
        scars.scanLoaded(d);
        if (d.surface() && AirstrikeConfig.SERVER.nukeCrater.get() && AirstrikeConfig.SERVER.nukeBlockDamage.get()
                && CraterModel.formsCrater(d.hobMetres(), d.yieldKt())) {
            craters.add(new CraterJob(d, 0));
            NuclearEvents.get(level).craterProgress(d.id(), 0, false);
        }
    }

    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        NuclearEvents events = NuclearEvents.get(level);
        for (Detonation d : events.past()) scars.offer(chunk, d);
        for (Detonation d : events.detonations()) scars.offer(chunk, d);
    }

    public void onChunkUnload(LevelChunk chunk) {
        scars.drop(chunk.getPos());
    }

    /** Отбой: очереди остановлены (разрушенное не возвращается). */
    public void clear(ServerLevel level) {
        scars.clear(level);
        pulses.clear();
        craters.forEach(c -> c.release(level));
        craters.clear();
        blast.clear();
        mobFallout.clear();
    }

    // ---------------------------------------------------------------- тик

    /** @param clock бюджет тика сервера, общий для всех измерений (уже запущен) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        NuclearEvents events = NuclearEvents.get(level);
        if (!restored) restore(events);
        // свет — раньше волны: он быстрее, и кого волна убьёт, тот уже получил свой импульс
        long pulseStart = System.nanoTime();
        try {
            while (!pulses.isEmpty() && pulses.getFirst().work(level, clock)) pulses.removeFirst();
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Световой импульс упал с ошибкой; сброшен", e);
            pulses.clear();
        }
        long frontStart = System.nanoTime();
        lastPulseNanos = frontStart - pulseStart;
        try {
            blast.advance(level, events.detonations(), now);
            blast.work(level, clock, id -> pulses.stream().noneMatch(p -> p.detonation().id() == id));
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Ударная волна по сущностям упала с ошибкой; сброшена", e);
            blast.clear();
        }
        lastFrontNanos = System.nanoTime() - frontStart;
        if (now % 1200 == 0) scars.retainBudgets(events.detonations().stream().map(Detonation::id).collect(java.util.stream.Collectors.toSet()));
        long start = System.nanoTime();
        try {
            while (!craters.isEmpty() && clock.canStart()) {
                CraterJob job = craters.getFirst();
                long u0 = clock.begin();
                CraterJob.Step s = job.step(level, level.random);
                clock.end(u0);
                events.craterProgress(job.detonation().id(), job.progress(), s == CraterJob.Step.DONE);
                if (s == CraterJob.Step.DONE) craters.removeFirst();
                else if (s == CraterJob.Step.WAIT) break;
            }
            long scarStart = System.nanoTime();
            lastCraterNanos = scarStart - start;
            scars.work(level, now, clock, level.random);
            long falloutStart = System.nanoTime();
            lastScarNanos = falloutStart - scarStart;
            mobFallout.work(level, events.detonations(), clock);
            lastFalloutNanos = System.nanoTime() - falloutStart;
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Ядерные разрушения упали с ошибкой; очереди сброшены", e);
            clear(level);
        }
    }

    /** После загрузки мира: недорытые воронки — дорыть с того чанка, где остановились. */
    private void restore(NuclearEvents events) {
        restored = true;
        events.craters().forEach((id, done) -> events.detonations().stream().filter(d -> d.id() == id).findFirst()
                .ifPresent(d -> craters.add(new CraterJob(d, done))));
    }
}
