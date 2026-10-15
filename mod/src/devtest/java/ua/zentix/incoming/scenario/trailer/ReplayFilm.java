package ua.zentix.incoming.scenario.trailer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.scenario.HeapProbe;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Вывод планов из повторов (сценарий {@code film}): для каждого плана {@link Coverage} открывает его повтор, читает
 * журнал ({@link EventLog}), находит объект и окно, ставит камеры-кандидаты ({@link Rig}) и оценивает их по миру повтора,
 * лучшие (кроме тех, что поворачивают быстрее {@link #TURN_MAX}) по очереди выводит Flashback по ключевым кадрам (кадр на
 * тик, PNG) с проверкой каждого кадра ({@link FrameQa});
 * первый кандидат, прошедший проверку, — план; не прошёл ни один — в отчёте лучший с причинами. Перед выводом зритель
 * повтора проходит путь камеры (секции мира строятся там, куда камера посмотрит) и стоит в её начале, пока мир не
 * построен.
 * <p>
 * Папка съёмки — {@code INCOMING_TRAILER_DIR} (как у записи): повторы в {@code replays/}, вывод в {@code render/<план>/<кандидат>/}
 * (кадры {@code frames/<план>/00000.png…} и {@code timeline.jsonl} — как у живой записи, монтаж берёт папку через
 * {@code --rec}), {@code qa.jsonl} — проверка по кадрам, {@code render/report.jsonl} — итоги, {@code render/best.txt} —
 * папки планов для монтажа; {@code INCOMING_FILM_OUT} — другая папка вывода вместо {@code render} (варианты плана с другими
 * настройками шейдерпака из того же повтора). {@code INCOMING_FILM_SHOTS} — только эти планы; {@code INCOMING_FILM_TRIES} — сколько
 * кандидатов выводить (3), {@code INCOMING_FILM_HOLD} — тиков стоять в начале после постройки мира (20; Distant Horizons
 * строит LOD после перемотки ~60). Строки лога: {@code SCENARIO film …}, в конце {@code SCENARIO done}.
 * <p>
 * Память: после открытия каждого повтора — гистограмма живых объектов ({@link HeapProbe}, сама делает полную сборку),
 * строка {@code SCENARIO film heap N <повтор>: куча живых … МБ; миров сервера …} (файл — {@code <вывод>/heap/histo-N-<повтор>.txt}):
 * миров сервера больше, чем у открытого, — прежние повторы ещё в памяти; живого больше {@link #HEAP_SHARE} кучи — клиент
 * уходит на границе повторов ({@link #restart}). {@code INCOMING_FILM_LEAK=1} — ещё и выборка старых объектов JFR с путями до
 * корней GC (строки {@code … holder}, файлы {@code heap/old-N.*}; {@code build.gradle} даёт JVM нужный для путей стек потока VM).
 * Миры повтора, которые Flashback закрывает без события выгрузки, получают его от {@link ReplayLevelUnload}.
 */
public final class ReplayFilm {
    private static final double FPS = 60;
    /** Тиков после открытия повтора до работы, ожидания перемотки и мира; сколько стоять в точке пути. */
    private static final int OPEN_WAIT = 4800, SETTLE = 60, SEEK_SETTLE = 30, WORLD_WAIT = 600, POSE_MIN = 6, POSE_MAX = 300;
    /** Оценка у камеры кандидата: тиков стоять там до первой проверки и сколько ждать её мира. */
    private static final int STOP_SETTLE = 10, STOP_WAIT = 200;
    /** Сколько ждать до вывода, пока DH нарисует LOD в окне и пересоздаст текстуры не в размер вывода ({@link DhFrameDepth#recreatedBy}). */
    private static final int DH_WAIT = 600;
    /** Ключи камеры — с запасом до начала (вывод ставит камеру и чуть раньше). */
    private static final int EXPORT_LEAD = 40;
    /**
     * Камера, что поворачивает быстрее, °/с (курс или тангаж за кадр вывода), не выводится: Артём, 14.10 — камеры
     * дёргаются. Меряется путь с тряской целиком: толчок {@link Operator} от одного близкого удара сам даёт до
     * ~70°/с, залп двух — до ~100°/с, под предел; быстрее — это уже рывок камеры, а не тряска оператора.
     */
    static final double TURN_MAX = 120;
    /**
     * Доля кучи, живого больше которой после открытия повтора клиент заменяется новым после этого повтора: вывод повтора
     * сверх замера берёт ещё до 1,5 ГБ (чанки у камер, кадры), закрытый повтор остаётся в памяти на 0,4–0,6 ГБ.
     */
    private static final double HEAP_SHARE = 0.6;

    private final Minecraft mc = Minecraft.getInstance();
    private final Path root, replays, out;
    private final int width, height, tries, hold;
    /** Только кандидаты с такими именами (INCOMING_FILM_CAMERA), null — все. */
    private final @Nullable java.util.regex.Pattern cameraFilter;
    /** Журналы записаны до очереди мест мода (film-war2 и раньше): место снаряда в кадре — {@link Shown}. */
    private final boolean beforeQueue;
    private final ArrayDeque<Coverage.Spec> queue = new ArrayDeque<>();
    private final List<String> best = new ArrayList<>();
    /** Метка этого вывода в строках report.jsonl: файл копит строки всех выводов, отчёт берёт последний вывод плана. */
    private final String run = Instant.now().toString();
    /** Замеры памяти после открытия повторов: папка, номер открытия, выборка JFR ({@code INCOMING_FILM_LEAK=1}). */
    private final Path heapDir;
    private int opened;
    @Nullable
    private HeapProbe.OldObjects jfr;
    /**
     * Почему клиент заменяется новым на границе повторов ({@code null} — не заменяется): живого больше {@link #HEAP_SHARE}
     * кучи. Закрытые повторы держат в памяти и моды (Create, Aeronautics — свои карты по миру сервера, без снятия), поэтому
     * клиент уходит раз в несколько повторов. Оставшиеся планы снимает новый клиент ({@code film.sh} запускает его, пока
     * есть файл {@code <вывод>/restart}), по {@code best.txt} — с того же места.
     */
    @Nullable
    private String restart;

    private enum State { TITLE, OPENING, SETTLING, SEEKING, SCORING, WALKING, WARMING, EXPORTING, DONE }

    private State state = State.TITLE;
    private int ticks, stateAt;

    // план в работе
    private Coverage.Spec spec;
    private String openReplay;
    /** Мир клиента до открытия повтора: открытие идёт задачей клиента, а тики могут идти ещё в прежнем мире. */
    private WeakReference<ClientLevel> levelBefore = new WeakReference<>(null);
    private EventLog.Data data;
    private Rig.Subject subject;
    private int a, b;
    private final ArrayDeque<Scored> candidates = new ArrayDeque<>();
    /** Оценка кандидатов; образец, ради мира которого зритель стоит у его камеры, и с какого тика. */
    private final List<Rig.Tally> tallies = new ArrayList<>();
    private Rig.Tally target;
    private int targetTick, stopAt;
    private Pose stop;
    private Scored current, bestTry;
    private FrameQa.Verdict bestVerdict;
    private FrameQa qa;
    /**
     * Журнал этого вывода — тем же {@link EventLog}, что у записи, но клиентом повтора, который рисует кадры
     * ({@code <кандидат>/<план>.events.jsonl}): где снаряды у него, где они взорвались. Журнал записи пишет записывающий
     * клиент, и если тот показывал снаряд иначе (film-war4: очередь мест 52d661a при отстающем сервере шла прошлым
     * шагом), камера по нему смотрит мимо; этот журнал с кадрами совпадает по построению — по нему сверка меры
     * ({@code tools/shot check}) и камера следующего вывода.
     */
    @Nullable
    private EventLog shown;
    private Path candDir;
    /** Вывод кандидата — MOV без потерь ({@link FrameUnpack}), а не PNG Flashback; задание вывода — ради его счётчиков. */
    private boolean mov;
    @Nullable
    private Object job;
    private final ArrayDeque<Pose> walk = new ArrayDeque<>();
    private Pose standing;
    private int poseAt, stepped, reachedAt;

    /** Оценённый кандидат и самый быстрый поворот его камеры за окно, °/с ({@link ReplayFilm#turnRate}). */
    private record Scored(Rig.Candidate c, Rig.Score score, double turn) {
        /** В очередь вывода: оценка выше нуля, поворот не быстрее {@link ReplayFilm#TURN_MAX}. */
        boolean exportable() {
            return score.total() > 0 && turn <= TURN_MAX;
        }
    }

    public ReplayFilm(IEventBus modBus) {
        try {
            // члены Flashback, которые зовёт вывод, на месте
            FlashbackApi.check();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Flashback: нет " + e.getMessage(), e);
        }
        Path game = mc.gameDirectory.toPath();
        // пути полные: best.txt читают монтаж и скрипты не из каталога игры
        root = game.resolve(System.getenv().getOrDefault("INCOMING_TRAILER_DIR", "trailer")).toAbsolutePath().normalize();
        replays = root.resolve("replays");
        out = root.resolve(System.getenv().getOrDefault("INCOMING_FILM_OUT", "render"));
        String[] size = System.getenv().getOrDefault("INCOMING_SIZE", "1920x1080").split("x");
        width = Integer.parseInt(size[0]);
        height = Integer.parseInt(size[1]);
        tries = Integer.parseInt(System.getenv().getOrDefault("INCOMING_FILM_TRIES", "3"));
        // правка одного плана другой камерой: имена кандидатов (регулярное выражение, список — render/<план>/candidates.txt)
        String camera = System.getenv("INCOMING_FILM_CAMERA");
        cameraFilter = camera == null || camera.isBlank() ? null : java.util.regex.Pattern.compile(camera);
        beforeQueue = System.getenv("INCOMING_FILM_SHOWN") != null;
        hold = Integer.parseInt(System.getenv().getOrDefault("INCOMING_FILM_HOLD", "20"));
        String only = System.getenv("INCOMING_FILM_SHOTS");
        List<String> pick = only == null || only.isBlank() ? null : Arrays.asList(only.split(","));
        // снятые в прошлый запуск (render/best.txt) не переснимаются, если их не назвали: запуск после падения идёт дальше;
        // новый клиент того же вывода (film.sh, INCOMING_FILM_RESUME) не переснимает и названные
        boolean resume = System.getenv("INCOMING_FILM_RESUME") != null;
        java.util.Set<String> done = new java.util.HashSet<>();
        try {
            if (Files.exists(out.resolve("best.txt"))) {
                for (String line : Files.readAllLines(out.resolve("best.txt"), StandardCharsets.UTF_8)) {
                    String shot = line.split(" ", 2)[0];
                    if (line.isBlank() || pick != null && pick.contains(shot) && !resume) continue;
                    best.add(line);
                    done.add(shot);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Coverage.Spec s : Coverage.all().values()) {
            if (pick != null && !pick.contains(s.shot()) || done.contains(s.shot())) continue;
            if (!Files.exists(zip(s.replay()))) {
                Incoming.LOG.warn("SCENARIO film {}: нет повтора {}", s.shot(), zip(s.replay()));
                continue;
            }
            queue.add(s);
        }
        // планы одного повтора — подряд: повтор открывается один раз
        List<Coverage.Spec> sorted = new ArrayList<>(queue);
        sorted.sort(Comparator.comparing(Coverage.Spec::replay));
        queue.clear();
        queue.addAll(sorted);
        Incoming.LOG.info("SCENARIO film {} shots: {}", queue.size(), queue.stream().map(Coverage.Spec::shot).toList());
        heapDir = out.resolve("heap");
        try {
            Files.createDirectories(out);
            Files.createDirectories(heapDir);
            // планы этого вывода (и снятые прошлым запуском): план без строки в report.jsonl отчёт называет невыведенным
            List<String> shots = new ArrayList<>(done);
            queue.forEach(q -> shots.add(q.shot()));
            Files.write(out.resolve("shots.txt"), shots.stream().sorted().toList(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // пакеты зрителю повтора после входа — когда у клиента есть мир (до onTick: отпуск и OPENING — в одном тике)
        ViewerHold.register(modBus);
        // миры повтора, которые Flashback закрывает без события выгрузки, — с событием: иначе каждый повтор оставался бы в памяти
        ReplayLevelUnload.register();
        if (System.getenv("INCOMING_FILM_LEAK") != null) startJfr();
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onStage);
        if (FrameQa.DH) DhFrameDepth.subscribe();
    }

    /** Выборка старых объектов JFR с первого повтора: держатели прежних повторов — в замерах следующих. */
    private void startJfr() {
        long stack = HeapProbe.vmThreadStackKb();
        boolean paths = stack >= HeapProbe.PATH_STACK_KB;
        if (!paths) {
            Incoming.LOG.warn("SCENARIO film heap: стек потока VM {} КБ — пути до корней GC выключены (поиск на стеке по умолчанию роняет JVM), "
                    + "нужно -XX:VMThreadStackSize={} или больше", stack, HeapProbe.PATH_STACK_KB);
        }
        try {
            jfr = new HeapProbe.OldObjects(paths);
            Incoming.LOG.info("SCENARIO film heap: выборка JFR пишет{}", paths ? ", с путями до корней" : ", без путей до корней");
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("SCENARIO film heap: запись JFR не началась — выборки старых объектов не будет", ex);
        }
    }

    /**
     * Замер после открытия повтора (прежний закрыт): живое после полной сборки, миры сервера и клиента, чанки; держатели —
     * в выборке JFR, если она пишет. Живого больше {@link #HEAP_SHARE} кучи — клиент уйдёт на следующей границе повторов.
     */
    private void probeHeap() {
        String tag = "SCENARIO film heap " + ++opened;
        try {
            HeapProbe.Histogram h = HeapProbe.histogram(tag, heapDir, opened + "-" + openReplay, false);
            var server = mc.getSingleplayerServer();
            long own = server == null ? 1 : server.levelKeys().size();
            long servers = h.count("net.minecraft.server.level.ServerLevel"), clients = h.count("net.minecraft.client.multiplayer.ClientLevel"),
                    integrated = h.count("net.minecraft.client.server.IntegratedServer");
            long live = HeapProbe.heapUsedMb(), max = Runtime.getRuntime().maxMemory() >> 20;
            Incoming.LOG.info("{} {}: куча живых {} МБ из {}{}; миров сервера {} (у повтора {}), серверов {}, миров клиента {}, чанков {}, держателей чанков {}",
                    tag, openReplay, live, max, HeapProbe.pools(), servers, own, integrated, clients,
                    h.count("net.minecraft.world.level.chunk.LevelChunk"), h.count("net.minecraft.server.level.ChunkHolder"));
            if (live > max * HEAP_SHARE) {
                restart = "живого " + live + " МБ из " + max + (servers > own ? ", прежних миров сервера в памяти " + (servers - own) : "");
            }
        } catch (Exception | LinkageError ex) {
            Incoming.LOG.warn("{}: гистограммы нет", tag, ex);
        }
        if (jfr != null) {
            try {
                jfr.dump(tag, heapDir, String.valueOf(opened));
            } catch (Exception | LinkageError ex) {
                Incoming.LOG.warn("{}: выборка JFR не сброшена", tag, ex);
            }
        }
    }

    private Path zip(String replay) {
        return replays.resolve(replay + ".zip");
    }

    private void onScreen(ScreenEvent.Init.Post e) {
        if (state != State.TITLE || !(e.getScreen() instanceof TitleScreen)) return;
        if (FrameQa.DH) DhGenerator.require();
        next();
    }

    private void onStage(RenderLevelStageEvent e) {
        if (qa != null) qa.onStage(e);
        if (shown != null && e.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL && FlashbackApi.exporting()) {
            double t = FlashbackApi.exportTick();
            if (!Double.isNaN(t)) shown.tick((int) Math.floor(t));
        }
    }

    private void closeShown() {
        if (shown != null) {
            shown.close();
            shown = null;
        }
    }

    private void go(State s) {
        state = s;
        stateAt = ticks;
    }

    private int waited() {
        return ticks - stateAt;
    }

    /** Следующий план: открыть его повтор (если открыт другой) и перейти к разбору. */
    private void next() {
        spec = queue.poll();
        subject = null;
        a = b = -1;
        if (spec == null) {
            finish();
            return;
        }
        if (spec.replay().equals(openReplay) && FlashbackApi.inReplay()) {
            go(State.SEEKING);
            startShot();
            return;
        }
        if (restart != null) {
            // граница повторов: остальное — новому клиенту, этот уходит, не набирая памяти дальше
            queue.addFirst(spec);
            try {
                Files.writeString(out.resolve("restart"), restart + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Incoming.LOG.warn("SCENARIO film restart: {} — планов осталось {}, их снимет новый клиент (film.sh)", restart, queue.size());
            finish();
            return;
        }
        openReplay = spec.replay();
        Path zip = zip(spec.replay());
        // вне события: открытие повтора меняет экран и мир
        levelBefore = new WeakReference<>(mc.level);
        mc.tell(() -> FlashbackApi.openReplay(zip));
        go(State.OPENING);
        Incoming.LOG.info("SCENARIO film open {}", zip);
    }

    private void onTick(ClientTickEvent.Post e) {
        if (state == State.TITLE || state == State.DONE) return;
        ticks++;
        try {
            tick();
        } catch (RuntimeException ex) {
            Incoming.LOG.error("SCENARIO film failed: {}", spec == null ? "-" : spec.shot(), ex);
            fail("ошибка вывода: " + ex);
        }
    }

    /** План не снят: строка в отчёт (план не теряется), дальше — следующий. */
    private void fail(String why) {
        if (qa != null) {
            qa.close();
            qa = null;
        }
        closeShown();
        flawless(false);
        if (spec != null) report(null, null, why);
        next();
    }

    private void tick() {
        if (state != State.OPENING && !FlashbackApi.inReplay()) {
            // клиент отключился от повтора (ошибка разбора пакета повтора и т. п.; причина — в логе выше)
            Incoming.LOG.error("SCENARIO film failed: {}: повтор {} закрылся", spec.shot(), openReplay);
            openReplay = null;
            fail("повтор закрылся: клиент отключился от него");
            return;
        }
        switch (state) {
            case OPENING -> {
                // мир нового повтора (не прежний: задача открытия ещё не прошла, а тиков за кадр бывает несколько),
                // и его зритель прошёл вход — пакеты после входа ему
                if (mc.level != null && mc.level != levelBefore.get() && mc.player != null && FlashbackApi.inReplay()
                        && ViewerHold.releasedFor(mc.getConnection())) {
                    // редактор Flashback не активен без интерфейса — только так задание вывода начинается
                    mc.options.hideGui = true;
                    probeHeap();
                    go(State.SETTLING);
                } else if (waited() > OPEN_WAIT) {
                    Incoming.LOG.error("SCENARIO film failed: повтор {} не открылся (пакеты зрителя: {})", openReplay, ViewerHold.state());
                    openReplay = null;
                    fail("повтор не открылся");
                }
            }
            case SETTLING -> {
                if (waited() >= SETTLE) {
                    go(State.SEEKING);
                    startShot();
                }
            }
            case SEEKING -> {
                // мир у места события: чанки повтора пришли
                if (waited() < SEEK_SETTLE) return;
                Vec3 k = seekPoint();
                Rig.World w = new Rig.World();
                if (k != null && !w.loaded(k) && waited() < WORLD_WAIT) {
                    standAt(Pose.look(k.add(0, 30, -30), k, 0, 70));
                    return;
                }
                plan(w);
            }
            case SCORING -> {
                // зритель у камеры образца, который ждёт мира: в счёт — всё, чей мир пришёл; ждём мир этого образца
                standAt(stop);
                int at = ticks - stopAt;
                if (at < STOP_SETTLE) return;
                Rig.World w = new Rig.World();
                for (Rig.Tally t : tallies) t.evaluate(w, null, 0);
                if (target.waiting() == targetTick && at < STOP_WAIT) return;
                if (target.waiting() == targetTick) {
                    // мир у зрителя пришёл весь, сколько придёт: чего рядом нет, того нет в повторе
                    // (до края прорисовки без двух чанков; луч образца у камеры, где стоит зритель, — не дальше FOG прорисовки)
                    double rd = Minecraft.getInstance().gameRenderer.getRenderDistance(), radius = Math.max(rd - 32, Rig.FOG * rd);
                    Incoming.LOG.warn("SCENARIO film {}: мир у {} не пришёл за {} тиков — чего в {} блоках нет, нет в повторе", spec.shot(),
                            War.xyz(stop.pos()), STOP_WAIT, Math.round(radius));
                    for (Rig.Tally t : tallies) t.evaluate(w, stop.pos(), radius);
                }
                nextStop();
            }
            case WALKING -> {
                if (standing != null) standAt(standing);
                boolean ready = built();
                int at = ticks - poseAt;
                boolean last = walk.isEmpty();
                if (at < (last ? Math.max(POSE_MIN, hold) : POSE_MIN) || !ready && at < POSE_MAX) return;
                if (!ready) Incoming.LOG.warn("SCENARIO film {}: мир не построен за {} тиков у {}", spec.shot(), POSE_MAX, standing.pos());
                if (!last) {
                    standing = walk.poll();
                    poseAt = ticks;
                    return;
                }
                go(State.WARMING);
            }
            case WARMING -> {
                // повтор вживую по тику до начала плана, как идёт сам вывод: события мода — живые (дым, столбы, огонь);
                // вывод с того же места не перематывает и частиц не чистит
                standAt(standing);
                int at = FlashbackApi.replayTick();
                if (at < a) {
                    if (at != stepped) {
                        stepped = at;
                        FlashbackApi.step(at + 1);
                    }
                    reachedAt = ticks;
                    return;
                }
                if (at > a) {
                    Incoming.LOG.warn("SCENARIO film {}: повтор ушёл за начало ({} > {}), вывод перемотает", spec.shot(), at, a);
                } else if (!built() && ticks - reachedAt < POSE_MAX) {
                    return;
                }
                if (FrameQa.DH && !DhFrameDepth.recreatedBy(width, height)) {
                    // текстуры LOD DH ещё размера вывода: вывод их не пересоздаст, и Iris оставит LOD глубину мира
                    if (waited() < DH_WAIT) return;
                    String why = "текстуры LOD DH размера вывода: DH не нарисовал LOD в окне за " + DH_WAIT + " тиков";
                    Incoming.LOG.warn("SCENARIO film {} {}: {}", spec.shot(), current.c.name(), why);
                    report(current, null, why);
                    nextCandidate();
                    return;
                }
                export();
            }
            case EXPORTING -> {
                if (FlashbackApi.exporting() || !FlashbackApi.jobs().isEmpty()) {
                    // вывод тикает клиент изнутри своего задания: счётчики — у задания, пока оно есть
                    Object j = FlashbackApi.job();
                    if (j != null) job = j;
                    return;
                }
                done();
            }
            default -> {
            }
        }
    }

    /** Повтор открыт: журнал, объект и окно, перемотка к событию. */
    private void startShot() {
        try {
            data = EventLog.read(replays.resolve(spec.replay() + ".events.jsonl"), beforeQueue);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        subject = spec.subject().apply(data);
        if (subject == null) {
            Incoming.LOG.error("SCENARIO film failed: {}: в журнале нет объекта", spec.shot());
            report(null, null, "в журнале нет объекта");
            noTake("в журнале нет объекта");
            next();
            return;
        }
        int total = FlashbackApi.totalTicks();
        // окно плана, как его снял сценарий (от «start» до «end»): камера сценария — в нём и в хвосте повтора после него
        EventLog.Mark start = data.mark("start"), end = data.mark("end");
        int sa = (start == null ? 0 : start.t()) + spec.offset(), sb = end == null ? data.ticks : end.t();
        a = sa;
        b = spec.span() > 0 ? Math.min(sb, sa + spec.span()) : sb;
        if (subject.key >= 0) {
            // удар — то, ради чего план: окно держит его с запасом, даже если сценарий кончил план раньше удара
            a = Math.min(a, subject.key - Coverage.HIT_MARGIN);
            b = Math.max(b, subject.key + Coverage.HIT_MARGIN);
        }
        a = Math.max(a, 1);
        b = Math.min(b, Math.min(total, data.ticks) - 2);
        Incoming.LOG.info(String.format(Locale.ROOT, "SCENARIO film shot %s: %s, ticks %d..%d (plan %d..%d, event %d at %s), replay %d ticks", spec.shot(),
                subject.what, a, b, sa, sb, subject.key, subject.keyPos, total));
        FlashbackApi.seek(subject.key >= 0 ? Math.max(a, subject.key - 5) : a);
        bestTry = null;
        bestVerdict = null;
    }

    /**
     * Где нужен мир повтора для оценки камер: у события; у объекта, который мод рисует поверх мира (ядерный подрыв в ~900
     * блоках от крыши), — у камеры сценария: у самого подрыва мир камеры за прорисовкой выгружен, и оценка сочла бы её
     * в блоках и вид закрытым.
     */
    @Nullable
    private Vec3 seekPoint() {
        if (subject.overlay && !data.camera.isEmpty()) {
            var e = data.camera.ceilingEntry(a);
            return (e != null ? e : data.camera.lastEntry()).getValue().pos();
        }
        return subject.keyPos != null ? subject.keyPos : subject.at.apply((a + b) / 2.0);
    }

    /**
     * Кандидаты всех типов камеры плана (мир повтора — у события) и их оценка: что уже у клиента, сразу; образцы, чьих
     * чанков нет, — со зрителем у их камеры ({@link #nextStop}).
     */
    private void plan(Rig.World w) {
        List<EventLog.Flight> earlier = data.flights.stream().filter(f -> f.impact >= 0 && f.impact < (subject.key >= 0 ? subject.key : b)).toList();
        tallies.clear();
        List<Rig.Kind> kinds = new ArrayList<>(spec.kinds());
        // камера, спланированная по сцене до вывода (tools/shot), — если для плана она есть
        kinds.add(Rig.planned(root.resolve("planned").resolve(spec.shot() + ".camera.jsonl")));
        for (Rig.Kind k : kinds) {
            for (Rig.Candidate c : k.candidates(subject, a, b, w, data)) {
                // в вывод — как снял бы человек (Operator); камера сценария и спланированная как есть — запасной, если с
                // руками объект уходит
                tallies.add(new Rig.Tally(Operator.human(c, subject, data, a, b), subject, a, b, earlier, spec.minShare()));
                if (c.designed() && (c.name().startsWith("own") || c.name().endsWith("-planned"))) {
                    tallies.add(new Rig.Tally(Operator.raw(c, subject, data, a, b), subject, a, b, earlier, spec.minShare()));
                }
            }
        }
        for (Rig.Tally t : tallies) t.evaluate(w, null, 0);
        nextStop();
    }

    /** Зритель — к камере первого образца, который ждёт мира; ждать некого — выбор кандидатов. */
    private void nextStop() {
        for (Rig.Tally t : tallies) {
            int tick = t.waiting();
            if (tick < 0) continue;
            target = t;
            targetTick = tick;
            stop = t.c.path().at(tick);
            stopAt = ticks;
            go(State.SCORING);
            Incoming.LOG.info("SCENARIO film {}: оценка у камеры {} (тик {}) {}", spec.shot(), t.c.name(), tick, War.xyz(stop.pos()));
            return;
        }
        solve();
    }

    /**
     * Самый быстрый поворот камеры за окно {@code [a, b]}, °/с: путь, который вывод отдаёт Flashback ({@link #editorState},
     * у кандидата — уже снятый человеком, {@link Operator}), по кадрам вывода (1/{@link #FPS} с) — наибольший шаг курса
     * (кратчайшим поворотом) или тангажа за кадр.
     */
    static double turnRate(CineCamera.Path path, int a, int b) {
        int perTick = (int) Math.round(FPS / 20), n = (b - a) * perTick;
        double most = 0;
        Pose prev = path.at(a);
        for (int k = 1; k <= n; k++) {
            Pose p = path.at(a + k / (double) perTick);
            most = Math.max(most, Math.max(Math.abs(Mth.wrapDegrees(p.yaw() - prev.yaw())), Math.abs(p.pitch() - prev.pitch())));
            prev = p;
        }
        return most * FPS;
    }

    /** Оценки кандидатов готовы: лучшие — в очередь вывода. */
    private void solve() {
        List<Scored> all = new ArrayList<>();
        for (Rig.Tally t : tallies) {
            if (cameraFilter == null || cameraFilter.matcher(t.c.name()).matches()) all.add(new Scored(t.c, t.score(), turnRate(t.c.path(), a, b)));
        }
        tallies.clear();
        target = null;
        // камера сценария — первой (её задумали для плана), дальше — по оценке
        all.sort(Comparator.comparing((Scored s) -> !(s.c.designed() && s.score.total() > 0)).thenComparingDouble(s -> -s.score.total()));
        StringBuilder sb = new StringBuilder();
        for (Scored s : all) {
            sb.append(String.format(Locale.ROOT, "\n  %-36s %.3f (подлёт %.2f, удар %.2f, после %.2f, размер %.3f, дым %.2f, поворот %.0f°/с%s) %s", s.c.name(),
                    s.score.total(), s.score.approach(), s.score.atKey(), s.score.after(), s.score.size(), s.score.smoke(), s.turn,
                    s.turn > TURN_MAX ? " — не выводится" : "", s.score.why()));
        }
        Incoming.LOG.info("SCENARIO film solve {}: {} candidates{}", spec.shot(), all.size(), sb);
        // все оценённые камеры плана — для правки по замечанию другой камерой (INCOMING_FILM_CAMERA)
        try {
            Files.createDirectories(out.resolve(spec.shot()));
            Files.writeString(out.resolve(spec.shot()).resolve("candidates.txt"), sb.toString().strip() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            Incoming.LOG.warn("SCENARIO film {}: candidates.txt не записан: {}", spec.shot(), e.toString());
        }
        candidates.clear();
        all.stream().filter(Scored::exportable).limit(tries).forEach(candidates::add);
        if (candidates.isEmpty()) {
            Scored top = all.isEmpty() ? null : all.get(0);
            // с оценкой выше нуля есть, но все поворачивают быстрее предела: лучшая из них — по тому же порядку
            Scored fast = all.stream().filter(s -> s.score.total() > 0).findFirst().orElse(null);
            String why = fast != null ? String.format(Locale.ROOT, "камеры поворачивают быстрее %.0f°/с: лучшая %s %.0f°/с", TURN_MAX, fast.c.name(), fast.turn)
                    : "нет камеры с оценкой выше нуля" + (top == null ? ": кандидатов нет"
                    : String.format(Locale.ROOT, ", лучшая %s %.3f (подлёт %.2f, удар %.2f) %s", top.c.name(), top.score.total(),
                    top.score.approach(), top.score.atKey(), top.score.why()).trim());
            report(null, null, why);
            noTake(why);
            next();
            return;
        }
        nextCandidate();
    }

    /** Следующий кандидат: пройти его путь (секции мира строятся), потом вывод. */
    private void nextCandidate() {
        current = candidates.poll();
        if (current == null) {
            // ни один не прошёл: в монтаж — лучший из выведенных, с причинами в отчёте
            if (bestTry != null) {
                best.add(spec.shot() + " " + dirOf(bestTry).toString() + " FAIL " + bestVerdict.why());
                writeBest();
            } else {
                noTake("ни один кандидат не выведен: " + lastWhy);
            }
            next();
            return;
        }
        candDir = dirOf(current);
        FlashbackApi.seek(Math.max(0, a - spec.warm()));
        stepped = -1;
        walk.clear();
        // места пути камеры, где мир должен быть построен; у штатива это одно место
        int n = 6;
        Pose prev = current.c.path().at(a);
        for (int i = 1; i <= n; i++) {
            Pose p = current.c.path().at(a + (b - a) * i / (double) n);
            if (p.pos().distanceTo(prev.pos()) > 2 || Math.abs(Mth.wrapDegrees(p.yaw() - prev.yaw())) > 10 || Math.abs(p.pitch() - prev.pitch()) > 10) {
                walk.add(p);
                prev = p;
            }
        }
        if (!walk.isEmpty()) walk.add(current.c.path().at(a));
        standing = current.c.path().at(a);
        poseAt = ticks + SEEK_SETTLE;  // перемотка: мир повтора приходит заново
        go(State.WALKING);
        Incoming.LOG.info("SCENARIO film candidate {} {} score {}", spec.shot(), current.c.name(), String.format(Locale.ROOT, "%.3f", current.score.total()));
    }

    /** Мир у камеры построен; без проверки мира ({@link FrameQa#READY} = 0, облако) не ждём его. */
    private boolean built() {
        return FrameQa.READY <= 0 || mc.levelRenderer.hasRenderedAllSections();
    }

    private Path dirOf(Scored s) {
        return out.resolve(spec.shot()).resolve(s.c.name());
    }

    /**
     * Камера Flashback — игрок-зритель повтора: место ключевого кадра и задания вывода — его ноги, глаз выше на рост
     * глаз. Позы плана — места глаза, поэтому зрителю — ниже на рост глаз.
     */
    static double eyeHeight() {
        var player = Minecraft.getInstance().player;
        return player == null ? 1.62 : player.getEyeHeight();
    }

    private void standAt(Pose p) {
        mc.player.moveTo(p.pos().x, p.pos().y - eyeHeight(), p.pos().z, p.yaw(), p.pitch());
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    /** Задание вывода: ключевые кадры камеры и угла на каждый тик окна, кадры (MOV или PNG), проверка каждого кадра. */
    private void export() {
        try {
            deleteTree(candDir);
            Path frames = candDir.resolve("frames").resolve(spec.shot());
            Files.createDirectories(frames);
            Path stateFile = candDir.resolve("state.json");
            Files.writeString(stateFile, editorState(current.c.path(), a, b), StandardCharsets.UTF_8);
            Pose p0 = current.c.path().at(a);
            List<EventLog.Mark> marks = new ArrayList<>(data.marks);
            if (subject.key >= 0) marks.add(new EventLog.Mark(subject.key, "impact"));
            // вывод пересоздаёт кадр игры в размер вывода; в том же размере Iris оставляет LOD DH глубину мира (DhFrameDepth)
            var main = mc.getMainRenderTarget();
            if (FrameQa.DH && main.width == width && main.height == height) {
                Incoming.LOG.error("SCENARIO film failed: окно {}x{} — размер вывода, LOD DH лягут поверх ближнего мира (окно — INCOMING_WINDOW, film.sh render)",
                        width, height);
                Runtime.getRuntime().halt(3);
            }
            qa = new FrameQa(subject, candDir, spec.shot(), a, marks);
            shown = new EventLog(candDir.resolve(spec.shot() + ".events.jsonl"));
            for (EventLog.Mark m : data.marks) shown.mark(m.t(), m.what());
            // тряска мода от взрывов (5° с частотой ~10 Гц) — не в вывод: толчки камеры даёт Operator; у ядерных планов
            // волна трясёт камеру моделью мода (давление у камеры), её Operator не повторяет
            IncomingConfig.CLIENT.cameraShake.set(data.nukes.isEmpty() ? 0.0 : 1.0);
            flawless(true);
            mov = FrameUnpack.enabled(out);
            job = null;
            FlashbackApi.queue(spec.shot(), stateFile, new double[]{p0.pos().x, p0.pos().y - eyeHeight(), p0.pos().z, p0.yaw(), p0.pitch()}, width, height,
                    a, b, FPS, mov ? candDir.resolve("frames.mov") : frames, mov);
            go(State.EXPORTING);
            // люди у камеры у клиента повтора: статисты плана должны быть в мире повтора
            String people = mc.level.players().stream().filter(pl -> pl.position().distanceTo(p0.pos()) < 128)
                    .map(pl -> pl.getGameProfile().getName() + " " + War.xyz(pl.position()) + (pl.isSpectator() ? " spectator" : "")
                            + (pl.isInvisible() ? " invisible" : "")).toList().toString();
            Incoming.LOG.info("SCENARIO film export {} {}: ticks {}..{}, all sections {}, people {}", spec.shot(), current.c.name(), a, b,
                    mc.levelRenderer.hasRenderedAllSections(), people);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Вывод кончился: кадры по порядку с нуля (как у живой записи), итог проверки. */
    private void done() {
        flawless(false);
        closeShown();
        qa.close();
        FrameQa q = qa;
        qa = null;
        Incoming.LOG.info("SCENARIO film export stats {} {} ({}): {}", spec.shot(), current.c.name(), mov ? "mov" : "png",
                job == null ? "задания не видел" : FlashbackApi.stats(job));
        Path dir = candDir.resolve("frames").resolve(spec.shot());
        int frames;
        if (mov) {
            Path file = candDir.resolve("frames.mov");
            frames = job == null ? -1 : FlashbackApi.writtenFrames(job);
            if (!Files.isRegularFile(file) || frames == 0) {
                // тот же кандидат ещё раз, кадрами PNG
                FrameUnpack.disable("Flashback не записал " + file);
                candidates.addFirst(current);
                nextCandidate();
                return;
            }
            FrameUnpack.submit(file, dir, frames, spec.shot() + " " + current.c.name());
        } else {
            frames = renumber(dir);
        }
        FrameQa.Verdict v = q.verdict(spec.minShare(), spec.need(), current.c.close());
        Incoming.LOG.info(String.format(Locale.ROOT, "SCENARIO film verdict %s %s: %s, %d frames (%d checked): мир %.2f, подлёт %.2f, перед ударом %.2f, удар %.2f, мотор %.2f, в дыму %.2f %s",
                spec.shot(), current.c.name(), v.pass() ? "PASS" : "FAIL", frames, q.frames().size(), v.ready(), v.approach(), v.nearHit(), v.atKey(),
                v.engine(), v.smoky(), v.why()));
        report(current, v, v.why());
        if (bestTry == null || rank(v) > rank(bestVerdict)) {
            bestTry = current;
            bestVerdict = v;
        }
        if (v.pass()) {
            best.add(spec.shot() + " " + candDir + " PASS");
            writeBest();
            next();
            return;
        }
        nextCandidate();
    }

    /** Переключатель «flawless frames» Sodium для этого сценария; null — ещё не искали, пустой — Sodium нет. */
    @Nullable
    private static java.util.Optional<java.util.function.Consumer<Boolean>> flawless;

    /**
     * Кадр — только когда мир в нём построен: «flawless frames» Sodium (открытый {@code FlawlessFrames.getProvider()},
     * так его включает вывод Flashback на Fabric; на NeoForge этой связи нет — включаем сами). Без Sodium (ванильная
     * отрисовка) — ничего: недостроенные кадры видит проверка.
     */
    @SuppressWarnings("unchecked")
    private static void flawless(boolean on) {
        if (flawless == null) {
            try {
                var provider = (java.util.function.Function<String, java.util.function.Consumer<Boolean>>) Class
                        .forName("net.caffeinemc.mods.sodium.client.util.FlawlessFrames").getMethod("getProvider").invoke(null);
                flawless = java.util.Optional.of(provider.apply("incoming film"));
            } catch (ClassNotFoundException e) {
                flawless = java.util.Optional.empty();
                Incoming.LOG.info("SCENARIO film: без Sodium — кадры не ждут мира, недостроенное видит проверка");
            } catch (ReflectiveOperationException | RuntimeException e) {
                flawless = java.util.Optional.empty();
                Incoming.LOG.warn("SCENARIO film: flawless frames Sodium не включились", e);
            }
        }
        flawless.ifPresent(c -> c.accept(on));
    }

    private static double rank(FrameQa.Verdict v) {
        return v.ready() + v.approach() + v.nearHit() + v.atKey() + v.engine() * 0.5 + (1 - v.smoky()) * 0.5;
    }

    /** Flashback нумерует кадры с 1, живая запись и монтаж — с 0. */
    private static int renumber(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> pngs = s.filter(p -> p.getFileName().toString().endsWith(".png")).sorted().toList();
            for (Path p : pngs) {
                int k = Integer.parseInt(p.getFileName().toString().replace(".png", ""));
                Files.move(p, dir.resolve(String.format(Locale.ROOT, "%05d.png", k - 1)));
            }
            return pngs.size();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Состояние редактора Flashback (как его хранит Flashback): одна сцена, дорожки камеры и угла кадра — ключ на каждый
     * тик окна с запасом (курс без скачка через ±180°), вывод — от {@code a} до {@code b}.
     */
    static String editorState(CineCamera.Path path, int a, int b) {
        JsonObject cam = new JsonObject(), fov = new JsonObject();
        float yaw = Float.NaN;
        double eye = eyeHeight();
        for (int t = a - EXPORT_LEAD - 2; t <= b + 2; t++) {
            Pose p = path.at(t);
            yaw = Float.isNaN(yaw) ? p.yaw() : yaw + Mth.wrapDegrees(p.yaw() - yaw);
            JsonObject k = new JsonObject();
            JsonArray pos = new JsonArray();
            pos.add(p.pos().x);
            pos.add(p.pos().y - eye);
            pos.add(p.pos().z);
            k.add("position", pos);
            k.addProperty("yaw", yaw);
            k.addProperty("pitch", p.pitch());
            k.addProperty("roll", p.roll());
            k.addProperty("type", "camera");
            k.addProperty("interpolation_type", "SMOOTH");
            cam.add(Integer.toString(t), k);
            JsonObject f = new JsonObject();
            f.addProperty("fov", p.fov());
            f.addProperty("type", "fov");
            f.addProperty("interpolation_type", "SMOOTH");
            fov.add(Integer.toString(t), f);
        }
        JsonArray tracks = new JsonArray();
        tracks.add(track("CAMERA", cam));
        tracks.add(track("FOV", fov));
        JsonObject visuals = new JsonObject();
        for (String k : List.of("showChat", "showBossBar", "showTitleText", "showScoreboard", "showActionBar", "showHotbar")) visuals.addProperty(k, false);
        visuals.addProperty("renderNametags", false);
        JsonObject scene = new JsonObject();
        scene.addProperty("name", "film");
        scene.add("keyframeTracks", tracks);
        scene.addProperty("exportStartTicks", a);
        scene.addProperty("exportEndTicks", b);
        JsonObject history = new JsonObject();
        history.add("entries", new JsonArray());
        history.addProperty("position", 0);
        scene.add("history", history);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        JsonObject state = new JsonObject();
        state.add("replayVisuals", visuals);
        state.addProperty("hideAllSpectators", true);
        state.add("scenes", scenes);
        state.addProperty("sceneIndex", 0);
        return new GsonBuilder().create().toJson(state);
    }

    private static JsonObject track(String type, JsonObject keys) {
        JsonObject t = new JsonObject();
        t.addProperty("enabled", true);
        t.add("customName", null);
        t.addProperty("customColour", 0);
        t.addProperty("keyframeType", type);
        t.add("keyframesByTick", keys);
        return t;
    }

    /**
     * План не снят (нет объекта, ни одной камеры с оценкой выше нуля, ни один кандидат не выведен): строка FAIL без папки
     * ({@code -}) в {@code best.txt}. Без неё план пропадал из итога молча: монтаж ставил заглушку, отчёт не знал почему,
     * а продолжение вывода снимало его заново.
     */
    private void noTake(String why) {
        best.add(spec.shot() + " - FAIL " + why);
        writeBest();
    }

    /** Причина последнего кандидата, который не дошёл до вывода или не прошёл. */
    private String lastWhy = "";

    private void report(@Nullable Scored c, @Nullable FrameQa.Verdict v, String why) {
        lastWhy = why;
        JsonObject o = new JsonObject();
        o.addProperty("shot", spec.shot());
        o.addProperty("run", run);
        o.addProperty("replay", spec.replay());
        o.addProperty("a", a);
        o.addProperty("b", b);
        o.addProperty("event", subject == null ? -1 : subject.key);
        if (c != null) {
            o.addProperty("candidate", c.c.name());
            o.addProperty("dir", dirOf(c).toString());
            o.addProperty("score", c.score.total());
        }
        if (v != null) {
            o.addProperty("pass", v.pass());
            o.addProperty("ready", v.ready());
            o.addProperty("approach", v.approach());
            o.addProperty("near_hit", v.nearHit());
            o.addProperty("at_key", v.atKey());
            o.addProperty("engine", v.engine());
            o.addProperty("smoky", v.smoky());
        } else {
            o.addProperty("pass", false);
        }
        o.addProperty("why", why);
        try {
            Files.writeString(out.resolve("report.jsonl"), o + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void writeBest() {
        try {
            Files.write(out.resolve("best.txt"), best, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private void finish() {
        go(State.DONE);
        if (jfr != null) {
            try {
                jfr.close();
            } catch (Exception | LinkageError ex) {
                Incoming.LOG.warn("SCENARIO film heap: запись JFR не закрылась", ex);
            }
            jfr = null;
        }
        // кадры MOV раскладываются в фоне: монтаж и проверки дубля возьмут PNG, когда клиент выйдет
        FrameUnpack.awaitAll();
        writeBest();
        Incoming.LOG.info("SCENARIO film done: {}", best);
        Incoming.LOG.info("SCENARIO done");
        mc.stop();
    }
}
