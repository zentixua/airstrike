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
 * импульс по сущностям после перезапуска не повторяется (он длится доли секунды), фронт идёт дальше с того места, где был.
 */
public final class NuclearWorld {
    /** Медленный ядерный тик пишется в лог не чаще, тиков. */
    private static final int SLOW_LOG_PERIOD = 100;

    private final ScarQueue scars = new ScarQueue();
    /** Руины подрывов по номеру ({@link RuinContext}): пока у подрыва есть работа в очереди. */
    private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<RuinContext> ruins = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /**
     * Раз в сколько тиков забывать руины подрывов без работы, и сколько тиков руины без работы держатся (работа — план
     * или подмена по ним; чанки, ждущие соседей, работой не считаются: край мира ждёт игрока часами, а руины подрыва —
     * десятки МБ). Чанк, дождавшийся соседей потом, строит руины с новыми.
     */
    private static final int RUINS_SWEEP = 100, RUINS_IDLE = 600;
    private final NuclearPrep prep = new NuclearPrep();
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

    /** Руины подрыва по всем чанкам: общие у подготовки заранее (после подрыва), очереди и проверок. */
    RuinContext ruins(Detonation d, long now) {
        RuinContext ctx = ruins.get(d.id());
        if (ctx == null) ruins.put(d.id(), ctx = new RuinContext(d));
        ctx.used = now;
        return ctx;
    }

    /** Давно не брали и нет работы, кроме ждущих соседей, — забыть (память разломов и старых блоков руин). */
    private void sweepRuins(long now) {
        if (now % RUINS_SWEEP != 0) return;
        ruins.int2ObjectEntrySet().removeIf(e -> {
            if (now - e.getValue().used <= RUINS_IDLE || scars.pending(e.getIntKey())) return false;
            e.getValue().cancelTasks();
            return true;
        });
        // ни руин, ни подготовки: буферы обхода (окно высокого города — десятки МБ) не держатся
        if (ruins.isEmpty() && prep.idle()) releaseBuffers();
    }

    private static void releaseBuffers() {
        Collapse.releaseBuffers();
        Blast.releaseBuffers();
    }

    /**
     * Есть ли работа потока сервера у очередей ядерки (полоса NUCLEAR {@code WorkScheduler}: пока есть, попадания
     * берут не весь общий бюджет).
     */
    public boolean busy() {
        return !pulses.isEmpty() || !craters.isEmpty() || scars.size() > 0 || !prep.idle() || blast.busy();
    }

    public int queuedChunks() {
        return scars.size();
    }

    /** Руины заранее: сколько готово (по всем летящим ударам). */
    public int plannedChunks() {
        return prep.plannedChunks();
    }

    /** Квадраты чанков, которые держит подготовка руин. */
    public int prepTiles() {
        return prep.heldTiles();
    }

    /** По готовому плану, построенных на месте, устаревших планов. */
    public int[] ruinStats() {
        return scars.ruinStats();
    }

    /** Чанки очереди, держащие тикет с соседями (проверки). */
    public long[] scarHolds() {
        return scars.heldChunks();
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
        blast.onDetonation(d, owner);
        NuclearPrep.Handoff ready = prep.handOff(level, d, level.getGameTime());
        if (ready != null) {
            // руины заранее и на месте — одни: разломы и стоящие руины подготовки переходят подрыву
            ready.ruins().used = level.getGameTime();
            ruins.put(d.id(), ready.ruins());
            scars.scanLoaded(d, ready.plans(), ready.order());
        }
        else scars.scanLoaded(d);
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

    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        scars.drop(level, chunk.getPos());
        // фоновые планы выгруженного чанка: снимки не того чанка, которым он загрузится
        for (RuinContext ctx : ruins.values()) ctx.forget(chunk.getPos().toLong());
        prep.forget(chunk.getPos().toLong());
    }

    /** Чанк ушёл игроку (сводка подрыва: ушло игроку до руин). */
    public void onChunkSent(ServerLevel level, LevelChunk chunk) {
        scars.sent(chunk.getPos().toLong(), level.getGameTime());
    }

    /** Не отдавать чанк игроку, пока в нём не встали руины, до которых уже дошла волна ({@code PlayerChunkSenderMixin}). */
    public boolean withholds(ServerLevel level, long chunk) {
        return scars.withholds(chunk, level.getGameTime());
    }

    /** Есть ли что не отдавать игрокам (быстрая проверка). */
    public boolean mayWithhold() {
        return scars.mayWithhold();
    }

    /**
     * Остановка сервера (выход из одиночной игры): вся работа руин — подготовка (чтения с диска, квадраты зоны за
     * волной), очереди, фоновые планы — бросается, фоновые потоки останавливаются и ждутся ({@link RuinWorkers#shutdown}):
     * после выхода ничего из руин не должно ни считать, ни держать память. Ничего из этого не сохраняется и так.
     * Тикеты зоны и подготовки отпускаются здесь же, до ванильного цикла выгрузки (он и сам снимает все тикеты, кроме
     * {@code UNKNOWN}, но только в своих кругах, а чтения с диска и задачи руин так не останавливаются). Сам цикл
     * выгрузки без предела времени — {@code mixin/server/StopServerChunksMixin}.
     *
     * @return фоновые потоки остановились за срок
     */
    public static boolean onServerStopping(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.hasData(ModAttachments.NUCLEAR_WORLD)) continue;
            try {
                get(level).clear(level);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Руины: остановка в {} упала с ошибкой", level.dimension().location(), e);
            }
        }
        return RuinWorkers.shutdown();
    }

    /** Отбой: очереди остановлены (разрушенное не возвращается). */
    public void clear(ServerLevel level) {
        prep.clear(level, scars);
        scars.clear(level);
        ruins.values().forEach(RuinContext::cancelTasks);
        ruins.clear();
        pulses.clear();
        craters.forEach(c -> c.release(level));
        craters.clear();
        blast.clear();
        mobFallout.clear();
        releaseBuffers();
    }

    // ---------------------------------------------------------------- тик

    /** @param clock бюджет тика сервера, общий для всех измерений (уже запущен) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        NuclearEvents events = NuclearEvents.get(level);
        if (!restored) restore(events);
        sweepRuins(now);
        // фоновые планы: зависшие — в поток сервера; потоков в работе — по тику сервера
        for (RuinContext ctx : ruins.values()) ctx.expire(now);
        RuinWorkers.adapt(level.getServer());
        // свет — раньше волны: он быстрее, и кого волна убьёт, тот уже получил свой импульс
        long pulseStart = System.nanoTime();
        while (!pulses.isEmpty()) {
            PulseJob job = pulses.getFirst();
            try {
                if (!job.work(level, clock)) break;
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Световой импульс подрыва №{} упал с ошибкой; снят", job.detonation().id(), e);
            }
            pulses.removeFirst();
        }
        long frontStart = System.nanoTime();
        lastPulseNanos = frontStart - pulseStart;
        try {
            blast.advance(level, events.detonations(), now);
            blast.work(level, clock, id -> pulses.stream().noneMatch(p -> p.detonation().id() == id));
        } catch (RuntimeException e) {
            // докуда фронт уже прошёл, остаётся: иначе он заново ударил бы всех внутри
            Airstrike.LOG.error("Ударная волна по сущностям упала с ошибкой; удары в очереди сброшены", e);
            blast.dropHits();
        }
        lastFrontNanos = System.nanoTime() - frontStart;
        if (now % 1200 == 0) scars.retainBudgets(events.detonations().stream().map(Detonation::id).collect(java.util.stream.Collectors.toSet()));
        long craterStart = System.nanoTime();
        digCraters(level, events, clock);
        long scarStart = System.nanoTime();
        lastCraterNanos = scarStart - craterStart;
        scars.work(level, now, clock);
        // руины заранее — из того, что осталось от бюджета: волна уже идущего подрыва важнее
        try {
            prep.tick(level, events, scars, clock);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Подготовка руин упала с ошибкой; снята", e);
            prep.clear(level, scars);
        }
        long falloutStart = System.nanoTime();
        lastScarNanos = falloutStart - scarStart;
        try {
            mobFallout.work(level, events.detonations(), clock);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Осадки у мобов упали с ошибкой; обход сброшен", e);
            mobFallout.clear();
        }
        lastFalloutNanos = System.nanoTime() - falloutStart;
    }

    /**
     * Воронки по очереди, сколько успеем за бюджет. Воронка, упавшая с ошибкой, снимается одна (со своими тикетами);
     * её ход в сохранении остаётся — после перезапуска она дороется с того же чанка.
     */
    private void digCraters(ServerLevel level, NuclearEvents events, WorkClock clock) {
        while (!craters.isEmpty() && clock.canStart()) {
            CraterJob job = craters.getFirst();
            CraterJob.Step s;
            long u0 = clock.begin();
            try {
                s = job.step(level, level.random);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Воронка подрыва №{} упала с ошибкой; снята", job.detonation().id(), e);
                job.release(level);
                craters.removeFirst();
                continue;
            } finally {
                clock.end(u0);
            }
            events.craterProgress(job.detonation().id(), job.progress(), s == CraterJob.Step.DONE);
            if (s == CraterJob.Step.DONE) craters.removeFirst();
            else if (s == CraterJob.Step.WAIT) break;
        }
    }

    /** После загрузки мира: недорытые воронки — дорыть с того чанка, где остановились. */
    private void restore(NuclearEvents events) {
        restored = true;
        events.craters().forEach((id, done) -> events.detonations().stream().filter(d -> d.id() == id).findFirst()
                .ifPresent(d -> craters.add(new CraterJob(d, done))));
    }
}
