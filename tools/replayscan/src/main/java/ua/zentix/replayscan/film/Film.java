package ua.zentix.replayscan.film;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Съёмка по плану ({@code -Dreplayscan.film=<план>}, README.md): клиент открывает повтор, и для каждого плана ставит
 * камеры-кандидаты (глаза игрока, неподвижная, штатив по кругу), оценивает их по миру повтора ({@link Cameras#score}),
 * выводит лучшие по очереди видео Flashback с проверкой каждого кадра ({@link FrameLog}); первый, прошедший проверку, —
 * план; не прошёл ни один — в итоге лучший из выведенных с причинами. Оценке нужен мир у камер: зритель сперва стоит у
 * глаза неподвижной камеры плана (дальний план — за чанками повтора, его камера — в них), без неё — у главного места.
 * Перед выводом зритель проходит путь камеры (секции мира строятся там, куда камера посмотрит), стоит у начала, пока мир
 * не построен, и повтор идёт по тику {@code warm} тиков до начала — дым и огонь прежних ударов в кадре, как в игре;
 * с Distant Horizons вывод ждёт ещё, пока текстуры DH не в размере кадра ({@link DhDepth}).
 * <p>
 * Вывод: {@code <out>/<план>/<камера>/} — {@code video.mkv}, {@code state.json} (состояние редактора), {@code qa.jsonl},
 * {@code sounds.jsonl}, {@code verdict.json}; {@code <out>/report.jsonl} — каждый выведенный кандидат,
 * {@code <out>/best.json} — план → лучший (с тиками плана). Планы, которые в {@code best.json} прошли проверку с теми же
 * тиками, не переснимаются: запуск после падения идёт дальше, а не прошедшие и изменённые планы снимаются снова. Строки
 * лога — {@code Film: …}, в конце клиент выходит; план не читается — выходит сразу.
 */
public final class Film {
    static final Logger LOG = LogUtils.getLogger();
    /**
     * Тиков: открытие повтора, после открытия, после перемотки, мир у места, стоять в точке пути, найти игрока, начало
     * задания вывода, повтор не идёт до начала плана.
     */
    private static final int OPEN_WAIT = 4800, SETTLE = 60, SEEK_SETTLE = 30, WORLD_WAIT = 600, POSE_MIN = 6, POSE_MAX = 300, PLAYER_WAIT = 400,
            EXPORT_WAIT = 1200, STALL = 600;

    private enum State { TITLE, OPENING, SETTLING, SEEKING, WALKING, WARMING, EXPORTING, DONE }

    private final Minecraft mc = Minecraft.getInstance();
    private final Plan plan;
    private final double aspect;
    private final ArrayDeque<Plan.Shot> queue = new ArrayDeque<>();
    private final JsonObject best;
    private final Smoke smoke = new Smoke();
    private State state = State.TITLE;
    private int ticks, stateAt;

    // план в работе
    private Plan.Shot shot;
    private final ArrayDeque<Scored> candidates = new ArrayDeque<>();
    private Scored current;
    @Nullable
    private Scored bestTry;
    @Nullable
    private FrameLog.Verdict bestVerdict;
    @Nullable
    private FrameLog log;
    private Path dir;
    private final ArrayDeque<Cameras.Pose> walk = new ArrayDeque<>();
    private Cameras.Pose standing;
    @Nullable
    private UUID povId;
    private int poseAt, stepped, reachedAt, seenAt, progressAt;
    /** Мир клиента до открытия повтора: открытие идёт задачей клиента, а тики могут идти ещё в прежнем мире. */
    private WeakReference<ClientLevel> levelBefore = new WeakReference<>(null);

    private record Scored(Cameras.Candidate c, Cameras.Score score) {}

    private Film(Plan plan, IEventBus modBus) {
        this.plan = plan;
        aspect = (double) plan.width() / plan.height();
        best = readBest(plan.out().resolve("best.json"));
        for (Plan.Shot s : plan.shots()) {
            if (filmed(s)) continue;
            queue.add(s);
        }
        LOG.info("Film: {} -> {}, {} shots to film: {}", plan.replay(), plan.out(), queue.size(), queue.stream().map(Plan.Shot::name).toList());
        LOG.info("Film: smoke of the effects layer {}", smoke.available() ? "checked" : "not checked (Incoming effects layer not found)");
        if (FrameLog.DH) {
            DhDepth.subscribe();
            LOG.info("Film: depth of Distant Horizons LODs checked in every frame");
        }
        // пакеты зрителю повтора после входа — когда у клиента есть мир (до onTick: отпуск и OPENING — в одном тике)
        ViewerHold.register(modBus);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onStage);
    }

    /** Съёмка по плану {@code file}: подписки на тик клиента и кадр; дальше всё идёт само. */
    public static void start(Path file, IEventBus modBus) {
        try {
            Export.check();
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.error("Film: Flashback 0.39 is required ({})", e.toString());
            exitWhenLoaded();
            return;
        }
        try {
            Plan plan = Plan.read(file);
            Files.createDirectories(plan.out());
            new Film(plan, modBus);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.error("Film: cannot start with plan {}", file, e);
            exitWhenLoaded();
        }
    }

    /** Клиент без окна сам не выйдет: выход, как только игра загрузилась. */
    private static void exitWhenLoaded() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post t) -> {
            if (Minecraft.getInstance().getOverlay() == null) Minecraft.getInstance().stop();
        });
    }

    /** План уже снят: в {@code best.json} прошёл проверку с теми же тиками и первым событием. */
    private boolean filmed(Plan.Shot s) {
        if (!best.has(s.name())) return false;
        JsonObject o = best.getAsJsonObject(s.name());
        return o.has("pass") && o.get("pass").getAsBoolean() && o.has("a") && o.get("a").getAsInt() == s.a()
                && o.has("b") && o.get("b").getAsInt() == s.b() && o.has("first") && o.get("first").getAsInt() == s.subject().firstKey();
    }

    private static JsonObject readBest(Path file) {
        try {
            return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void go(State s) {
        state = s;
        stateAt = ticks;
    }

    private int waited() {
        return ticks - stateAt;
    }

    private void onStage(RenderLevelStageEvent e) {
        if (log != null) log.onStage(e);
    }

    private void onTick(ClientTickEvent.Post e) {
        if (state == State.DONE) return;
        ticks++;
        try {
            tick();
        } catch (RuntimeException ex) {
            LOG.error("Film: {} failed", shot == null ? "-" : shot.name() + (current == null ? "" : " " + current.c.name()), ex);
            if (log != null) {
                log.close();
                log = null;
                Export.flawless(false);
            }
            if (state == State.TITLE || state == State.OPENING) {
                finish();
            } else if (current != null) {
                report(current, null, "error: " + ex);
                nextCandidate();
            } else {
                nextShot();
            }
        }
    }

    private void tick() {
        switch (state) {
            case TITLE -> {
                // загрузка кончилась: заглавие или экран поверх него; повтор их сменит
                if (mc.getOverlay() != null || mc.screen == null || mc.level != null) return;
                if (windowIsFrame()) {
                    finish();
                    return;
                }
                mc.options.onboardAccessibility = false;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(plan.distance());
                mc.options.save();
                levelBefore = new WeakReference<>(mc.level);
                Export.open(plan.replay());
                go(State.OPENING);
            }
            case OPENING -> {
                // мир повтора (не прежний), и его зритель прошёл вход — пакеты после входа ему
                if (mc.level != null && mc.level != levelBefore.get() && mc.player != null && Export.inReplay()
                        && ViewerHold.releasedFor(mc.getConnection())) {
                    // редактор Flashback не активен с интерфейсом — только так задание вывода начинается
                    mc.options.hideGui = true;
                    go(State.SETTLING);
                } else if (waited() > OPEN_WAIT) {
                    LOG.error("Film: replay {} did not open (viewer packets: {})", plan.replay(), ViewerHold.state());
                    finish();
                }
            }
            case SETTLING -> {
                if (waited() >= SETTLE) nextShot();
            }
            case SEEKING -> {
                if (waited() < SEEK_SETTLE) return;
                if (!mc.level.dimension().location().toString().equals(shot.dim())) {
                    fail("camera is in " + mc.level.dimension().location() + ", shot in " + shot.dim());
                    return;
                }
                Vec3 k = shot.subject().anchor(shot.a(), shot.b());
                Cameras.World w = new Cameras.World(Cameras.cloudHeight(plan));
                // мир — у глаза неподвижной камеры, а у ближнего плана и у места: дальнее место за чанками повтора не
                // появится, а у глаза оно не нужно
                Plan.Camera fixed = shot.cameras().stream().filter(c -> c.kind().equals("fixed") && c.eye() != null).findFirst().orElse(null);
                boolean ready = (fixed == null || w.loaded(fixed.eye())) && (k == null || shot.far() && fixed != null || w.loaded(k));
                if (!ready && waited() < WORLD_WAIT) {
                    if (fixed != null) standAt(Cameras.Pose.look(fixed.eye(), fixed.aim() != null ? fixed.aim() : k != null ? k : fixed.eye().add(0, 0, 1), 70));
                    else standAt(Cameras.Pose.look(k.add(0, 30, -30), k, 70));
                    return;
                }
                solve(w);
            }
            case WALKING -> {
                if (current.c.player() != null) {
                    Entity target = watch();
                    if (target == null) {
                        if (waited() > PLAYER_WAIT) {
                            report(current, null, "player " + current.c.player() + " not found");
                            nextCandidate();
                        }
                        return;
                    }
                    if (ticks - poseAt < Math.max(POSE_MIN, shot.hold()) || !built() && ticks - poseAt < POSE_MAX) return;
                    warm();
                    return;
                }
                standAt(standing);
                boolean last = walk.isEmpty();
                int at = ticks - poseAt;
                if (at < (last ? Math.max(POSE_MIN, shot.hold()) : POSE_MIN) || !built() && at < POSE_MAX) return;
                if (!built()) LOG.warn("Film: {}: world not built in {} ticks at {}", shot.name(), POSE_MAX, standing.pos());
                if (!last) {
                    standing = walk.poll();
                    poseAt = ticks;
                    return;
                }
                warm();
            }
            case WARMING -> {
                // повтор вживую по тику до начала плана, как идёт сам вывод: события модов живые (дым, столбы, огонь);
                // вывод с того же места не перематывает и частиц не чистит
                if (current.c.player() != null) {
                    if (watch() == null) {
                        // игрок записи ушёл из мира клиента (смерть, другое измерение, дальше прорисовки)
                        if (ticks - seenAt > PLAYER_WAIT) {
                            report(current, null, "player " + current.c.player() + " lost before the start");
                            nextCandidate();
                        }
                        return;
                    }
                } else {
                    standAt(standing);
                }
                int at = Export.tick();
                if (at < shot.a()) {
                    if (at != stepped) {
                        stepped = at;
                        progressAt = ticks;
                        Export.seek(at + 1);
                    } else if (ticks - progressAt > STALL) {
                        report(current, null, "replay stopped at tick " + at + " before the start");
                        nextCandidate();
                        return;
                    }
                    reachedAt = ticks;
                    return;
                }
                boolean past = at > shot.a(), waiting = ticks - reachedAt < POSE_MAX;
                if (waiting && (!past && !built() || dhAtFrame())) return;
                if (past) LOG.warn("Film: {}: replay went past the start ({} > {}), export will seek", shot.name(), at, shot.a());
                if (dhAtFrame()) LOG.warn("Film: {}: Distant Horizons textures are still {}x{}, the frame size, after {} ticks", shot.name(), plan.width(), plan.height(), POSE_MAX);
                export();
            }
            case EXPORTING -> {
                if (current.c.player() != null) watch();
                if (Export.exporting()) {
                    // интерфейс игрока в кадре: окно вывода начинает задание только без него, дальше его можно вернуть
                    if (current.c.gui()) mc.options.hideGui = false;
                    return;
                }
                if (!Export.jobs().isEmpty()) {
                    if (waited() > EXPORT_WAIT) {
                        Export.jobs().clear();
                        throw new IllegalStateException("export did not start in " + EXPORT_WAIT + " ticks");
                    }
                    return;
                }
                done();
            }
            default -> {
            }
        }
    }

    /** Живой повтор до начала плана; ожидание мира и текстур DH у начала считается отсюда. */
    private void warm() {
        reachedAt = ticks;
        go(State.WARMING);
    }

    /**
     * Текстуры Distant Horizons — в размере кадра плана: вывод в этом размере не пересоздаст их, и Iris оставит LOD глубину
     * мира ({@link DhDepth}). Они в нём после прошлого вывода, пока DH не нарисует LOD в окне.
     */
    private boolean dhAtFrame() {
        return FrameLog.DH && DhDepth.textureIs(plan.width(), plan.height());
    }

    // ================================================================ планы и кандидаты

    private void nextShot() {
        shot = queue.poll();
        current = null;
        bestTry = null;
        bestVerdict = null;
        if (shot == null) {
            finish();
            return;
        }
        int total = Export.total();
        if (shot.b() >= total) LOG.warn("Film: {} ends at {}, the replay has {} ticks", shot.name(), shot.b(), total);
        int key = shot.subject().firstKey();
        Export.seek(Math.max(1, key >= 0 ? Math.max(shot.a(), key - 5) : shot.a()));
        go(State.SEEKING);
        LOG.info("Film: shot {} ticks {}..{}, first event {}", shot.name(), shot.a(), shot.b(), key);
    }

    /**
     * Кандидаты всех камер плана и их оценка; сперва те, что по оценке пройдут проверку, в них — камеры плана (глаза
     * игрока, неподвижная), потом штативы, по итогу оценки; потом так же не прошедшие.
     */
    private void solve(Cameras.World w) {
        List<Scored> designed = new ArrayList<>(), rest = new ArrayList<>();
        for (Plan.Camera cam : shot.cameras()) {
            for (Cameras.Candidate c : Cameras.of(cam, shot.subject(), shot.a(), shot.b(), aspect, w)) {
                Scored s = new Scored(c, Cameras.score(c, shot.subject(), shot, aspect, w));
                (c.designed() ? designed : rest).add(s);
            }
        }
        Comparator<Scored> order = Comparator.<Scored, Boolean>comparing(s -> !s.score.pass()).thenComparingDouble(s -> -s.score.total());
        designed.sort(order);
        rest.sort(order);
        StringBuilder sb = new StringBuilder();
        for (Scored s : Stream.concat(designed.stream(), rest.stream().limit(8)).toList()) {
            sb.append(String.format(Locale.ROOT, "\n  %-32s %.3f %s (approach %.2f, near %.2f, events %.2f, after %.2f, size %.3f, smoke %.2f) %s",
                    s.c.name(), s.score.total(), s.score.pass() ? "pass" : "fail", s.score.approach(), s.score.near(), s.score.keys(), s.score.after(),
                    s.score.size(), s.score.smoke(), s.score.why()));
        }
        LOG.info("Film: {}: {} designed and {} scored cameras, best:{}", shot.name(), designed.size(), rest.size(), sb);
        candidates.clear();
        // неподвижная камера плана, которая по оценке не пройдёт, не занимает места штатива, который пройдёт
        Stream.concat(designed.stream(), rest.stream())
                .sorted(Comparator.<Scored, Boolean>comparing(s -> !s.score.pass()).thenComparing(s -> !s.c.designed()).thenComparingDouble(s -> -s.score.total()))
                .filter(s -> s.score.total() > 0).limit(plan.tries()).forEach(candidates::add);
        if (candidates.isEmpty()) {
            fail("no camera scored above zero");
            return;
        }
        nextCandidate();
    }

    /** План не снят совсем (нет камеры, другое измерение): в итог с причиной. */
    private void fail(String why) {
        LOG.warn("Film: {}: {}", shot.name(), why);
        JsonObject o = entry(false);
        o.addProperty("why", why);
        best.add(shot.name(), o);
        writeBest();
        nextShot();
    }

    /** Следующий кандидат: перемотка до начала с запасом на живой повтор, путь камеры (секции мира строятся), вывод. */
    private void nextCandidate() {
        // после чужого игрока или оборванного вывода: камера — снова зритель, интерфейс скрыт
        if (mc.player != null && mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
        mc.options.hideGui = true;
        current = candidates.poll();
        if (current == null) {
            // ни один не прошёл: в итог — лучший из выведенных с причинами
            JsonObject o = entry(false);
            if (bestTry != null) {
                o.addProperty("dir", dirOf(bestTry).toString());
                o.addProperty("candidate", bestTry.c.name());
                o.addProperty("why", bestVerdict.why());
            } else {
                o.addProperty("why", "no candidate was exported");
            }
            best.add(shot.name(), o);
            writeBest();
            nextShot();
            return;
        }
        dir = dirOf(current);
        Export.seek(Math.max(1, shot.a() - shot.warm()));
        stepped = -1;
        walk.clear();
        povId = null;
        seenAt = ticks;
        poseAt = ticks + SEEK_SETTLE;
        if (current.c.path() != null) {
            // места пути камеры, где мир должен быть построен; у неподвижной камеры это одно место
            Cameras.Path path = current.c.path();
            Cameras.Pose prev = path.at(shot.a());
            for (int i = 1; i <= 6; i++) {
                Cameras.Pose p = path.at(shot.a() + (shot.b() - shot.a()) * i / 6.0);
                if (p.pos().distanceTo(prev.pos()) > 2 || Math.abs(Mth.wrapDegrees(p.yaw() - prev.yaw())) > 10 || Math.abs(p.pitch() - prev.pitch()) > 10) {
                    walk.add(p);
                    prev = p;
                }
            }
            if (!walk.isEmpty()) walk.add(path.at(shot.a()));
            standing = path.at(shot.a());
        }
        go(State.WALKING);
        LOG.info("Film: {} candidate {} score {}", shot.name(), current.c.name(), String.format(Locale.ROOT, "%.3f", current.score.total()));
    }

    private Path dirOf(Scored s) {
        return plan.out().resolve(shot.name()).resolve(s.c.name());
    }

    // ================================================================ камера

    /** Мир у камеры построен (все секции в прорисовке); без проверки мира ({@code ready} 0) не ждём его. */
    private boolean built() {
        return plan.ready() <= 0 || mc.levelRenderer.hasRenderedAllSections();
    }

    /** Высота глаза зрителя повтора: место ключевого кадра Flashback — его ноги. */
    private double eyeHeight() {
        return mc.player == null ? 1.62 : mc.player.getEyeHeight();
    }

    private void standAt(Cameras.Pose p) {
        mc.player.moveTo(p.pos().x, p.pos().y - eyeHeight(), p.pos().z, p.yaw(), p.pitch());
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * План глазами игрока: камера клиента — этот игрок (как кнопка «Spectate» Flashback; команда {@code spectate} сервером
     * повтора не исполняется — игрока записи есть только у клиента), зритель повтора стоит у него. Сущность после
     * перемотки появляется не сразу и бывает новой — ищется каждый тик. null — игрока нет.
     */
    @Nullable
    private Entity watch() {
        if (povId == null) povId = resolve(current.c.player());
        if (povId == null) return null;
        Player target = null;
        for (Player p : mc.level.players()) {
            if (povId.equals(p.getUUID())) target = p;
        }
        if (target == null) return null;
        seenAt = ticks;
        if (mc.getCameraEntity() != target) mc.setCameraEntity(target);
        mc.player.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
        mc.player.setDeltaMovement(Vec3.ZERO);
        return target;
    }

    /** {@code recorder} — записавший игрок (его id в повторе), иначе UUID или ник игрока в мире повтора. */
    @Nullable
    private UUID resolve(String who) {
        if (who.equals("recorder")) {
            Entity e = mc.level.getEntity(Export.localPlayerId());
            return e instanceof Player ? e.getUUID() : null;
        }
        String name = who.startsWith("player:") ? who.substring(7) : who;
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException notUuid) {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p.getUUID();
            }
            return null;
        }
    }

    // ================================================================ вывод

    /**
     * Окно игры — размер кадра плана, а Distant Horizons стоит: вывод Flashback пересоздаёт кадр игры в размер вывода, в том
     * же размере Iris оставляет LOD глубину мира, и они ложатся поверх ближнего мира ({@link DhDepth}). Тогда не снимаем.
     */
    private boolean windowIsFrame() {
        var main = mc.getMainRenderTarget();
        if (!FrameLog.DH || main.width != plan.width() || main.height != plan.height()) return false;
        LOG.error("Film: the window is {}x{}, the frame size, and Distant Horizons is loaded: its LODs would be drawn over the near world; "
                + "open the client at another size", main.width, main.height);
        return true;
    }

    /** Задание вывода: состояние редактора, видео, проверка каждого кадра. */
    private void export() {
        if (windowIsFrame()) {
            finish();
            return;
        }
        try {
            deleteTree(dir);
            Files.createDirectories(dir);
            Path stateFile = dir.resolve("state.json");
            Files.writeString(stateFile, Export.editorState(current.c.path(), shot.a(), shot.b(), eyeHeight(), current.c.gui()), StandardCharsets.UTF_8);
            log = new FrameLog(shot.subject(), shot, plan, current.c.player() != null ? povId : null, smoke, dir);
            Export.flawless(true);
            if (current.c.player() != null) mc.options.fov().set((int) Math.round(current.c.fov()));
            Vec3 at = mc.player.position();
            Export.queue(shot.name(), stateFile, at, mc.player.getYRot(), mc.player.getXRot(), plan.width(), plan.height(), shot.a(), shot.b(), plan.fps(),
                    plan.bitrate(), current.c.gui(), dir.resolve("video.mkv"));
            go(State.EXPORTING);
            LOG.info("Film: export {} {}: ticks {}..{}, all sections {}", shot.name(), current.c.name(), shot.a(), shot.b(),
                    mc.levelRenderer.hasRenderedAllSections());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Вывод кончился: итог проверки; прошёл — план снят, нет — следующий кандидат. */
    private void done() {
        Export.flawless(false);
        mc.options.hideGui = true;
        if (current.c.player() != null) mc.setCameraEntity(mc.player);
        FrameLog l = log;
        log = null;
        l.close();
        FrameLog.Verdict v = l.verdict(current.c.player() != null);
        Path video = dir.resolve("video.mkv");
        try {
            if (!Files.exists(video) || Files.size(video) == 0) v = new FrameLog.Verdict(false, "no video", 0, v.numbers());
            JsonObject o = v.numbers().deepCopy();
            o.addProperty("pass", v.pass());
            o.addProperty("why", v.why());
            Files.writeString(dir.resolve("verdict.json"), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        LOG.info("Film: verdict {} {}: {} {} {}", shot.name(), current.c.name(), v.pass() ? "PASS" : "FAIL", v.numbers(), v.why());
        report(current, v, v.why());
        if (bestTry == null || v.rank() > bestVerdict.rank()) {
            bestTry = current;
            bestVerdict = v;
        }
        if (v.pass()) {
            JsonObject o = entry(true);
            o.addProperty("dir", dir.toString());
            o.addProperty("candidate", current.c.name());
            best.add(shot.name(), o);
            writeBest();
            nextShot();
            return;
        }
        nextCandidate();
    }

    private void report(Scored c, @Nullable FrameLog.Verdict v, String why) {
        JsonObject o = new JsonObject();
        o.addProperty("shot", shot.name());
        o.addProperty("a", shot.a());
        o.addProperty("b", shot.b());
        o.addProperty("candidate", c.c.name());
        o.addProperty("dir", dirOf(c).toString());
        o.addProperty("score", c.score.total());
        o.addProperty("pass", v != null && v.pass());
        if (v != null) o.add("numbers", v.numbers());
        o.addProperty("why", why);
        try {
            Files.writeString(plan.out().resolve("report.jsonl"), o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Запись итога плана: прошёл ли он и его тики (по ним запуск заново узнаёт тот же план). */
    private JsonObject entry(boolean pass) {
        JsonObject o = new JsonObject();
        o.addProperty("pass", pass);
        o.addProperty("a", shot.a());
        o.addProperty("b", shot.b());
        o.addProperty("first", shot.subject().firstKey());
        return o;
    }

    /** {@code best.json} — через временный файл: падение посреди записи не оставляет обрезанный файл. */
    private void writeBest() {
        try {
            Path file = plan.out().resolve("best.json"), tmp = plan.out().resolve("best.json.tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(best), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
        LOG.info("Film: done, {}", best);
        mc.stop();
    }
}
