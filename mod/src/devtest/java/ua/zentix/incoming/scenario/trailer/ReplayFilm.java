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
 * лучшие по очереди выводит Flashback по ключевым кадрам (кадр на тик, PNG) с проверкой каждого кадра ({@link FrameQa});
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

    private final Minecraft mc = Minecraft.getInstance();
    private final Path root, replays, out;
    private final int width, height, tries, hold;
    private final ArrayDeque<Coverage.Spec> queue = new ArrayDeque<>();
    private final List<String> best = new ArrayList<>();
    /** Метка этого вывода в строках report.jsonl: файл копит строки всех выводов, отчёт берёт последний вывод плана. */
    private final String run = Instant.now().toString();

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
    private Path candDir;
    private final ArrayDeque<Pose> walk = new ArrayDeque<>();
    private Pose standing;
    private int poseAt, stepped, reachedAt;

    private record Scored(Rig.Candidate c, Rig.Score score) {}

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
        hold = Integer.parseInt(System.getenv().getOrDefault("INCOMING_FILM_HOLD", "20"));
        String only = System.getenv("INCOMING_FILM_SHOTS");
        List<String> pick = only == null || only.isBlank() ? null : Arrays.asList(only.split(","));
        // снятые в прошлый запуск (render/best.txt) не переснимаются, если их не назвали: запуск после падения идёт дальше
        java.util.Set<String> done = new java.util.HashSet<>();
        try {
            if (Files.exists(out.resolve("best.txt"))) {
                for (String line : Files.readAllLines(out.resolve("best.txt"), StandardCharsets.UTF_8)) {
                    String shot = line.split(" ", 2)[0];
                    if (line.isBlank() || pick != null && pick.contains(shot)) continue;
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
        try {
            Files.createDirectories(out);
            // планы этого вывода (и снятые прошлым запуском): план без строки в report.jsonl отчёт называет невыведенным
            List<String> shots = new ArrayList<>(done);
            queue.forEach(q -> shots.add(q.shot()));
            Files.write(out.resolve("shots.txt"), shots.stream().sorted().toList(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // пакеты зрителю повтора после входа — когда у клиента есть мир (до onTick: отпуск и OPENING — в одном тике)
        ViewerHold.register(modBus);
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onStage);
        if (FrameQa.DH) DhFrameDepth.subscribe();
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
                if (FlashbackApi.exporting() || !FlashbackApi.jobs().isEmpty()) return;
                done();
            }
            default -> {
            }
        }
    }

    /** Повтор открыт: журнал, объект и окно, перемотка к событию. */
    private void startShot() {
        try {
            data = EventLog.read(replays.resolve(spec.replay() + ".events.jsonl"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        subject = spec.subject().apply(data);
        if (subject == null) {
            Incoming.LOG.error("SCENARIO film failed: {}: в журнале нет объекта", spec.shot());
            report(null, null, "в журнале нет объекта");
            next();
            return;
        }
        int total = FlashbackApi.totalTicks();
        // окно плана, как его снял сценарий (от «start» до «end»): камера сценария — в нём и в хвосте повтора после него
        EventLog.Mark start = data.mark("start"), end = data.mark("end");
        int sa = start == null ? 0 : start.t(), sb = end == null ? data.ticks : end.t();
        a = sa;
        b = sb;
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
        for (Rig.Kind k : spec.kinds()) {
            for (Rig.Candidate c : k.candidates(subject, a, b, w, data)) tallies.add(new Rig.Tally(c, subject, a, b, earlier, spec.minShare()));
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

    /** Оценки кандидатов готовы: лучшие — в очередь вывода. */
    private void solve() {
        List<Scored> all = new ArrayList<>();
        for (Rig.Tally t : tallies) all.add(new Scored(t.c, t.score()));
        tallies.clear();
        target = null;
        // камера сценария — первой (её задумали для плана), дальше — по оценке
        all.sort(Comparator.comparing((Scored s) -> !(s.c.designed() && s.score.total() > 0)).thenComparingDouble(s -> -s.score.total()));
        StringBuilder sb = new StringBuilder();
        for (Scored s : all) {
            sb.append(String.format(Locale.ROOT, "\n  %-36s %.3f (подлёт %.2f, удар %.2f, после %.2f, размер %.3f, дым %.2f) %s", s.c.name(), s.score.total(),
                    s.score.approach(), s.score.atKey(), s.score.after(), s.score.size(), s.score.smoke(), s.score.why()));
        }
        Incoming.LOG.info("SCENARIO film solve {}: {} candidates{}", spec.shot(), all.size(), sb);
        candidates.clear();
        all.stream().filter(s -> s.score.total() > 0).limit(tries).forEach(candidates::add);
        if (candidates.isEmpty()) {
            report(null, null, "нет камеры с оценкой выше нуля");
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

    /** Задание вывода: ключевые кадры камеры и угла на каждый тик окна, кадры PNG, проверка каждого кадра. */
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
            flawless(true);
            FlashbackApi.queuePng(spec.shot(), stateFile, new double[]{p0.pos().x, p0.pos().y - eyeHeight(), p0.pos().z, p0.yaw(), p0.pitch()}, width, height,
                    a, b, FPS, frames);
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
        qa.close();
        FrameQa q = qa;
        qa = null;
        int frames = renumber(candDir.resolve("frames").resolve(spec.shot()));
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

    private void report(@Nullable Scored c, @Nullable FrameQa.Verdict v, String why) {
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
        writeBest();
        Incoming.LOG.info("SCENARIO film done: {}", best);
        Incoming.LOG.info("SCENARIO done");
        mc.stop();
    }
}
