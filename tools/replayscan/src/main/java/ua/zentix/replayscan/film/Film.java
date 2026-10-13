package ua.zentix.replayscan.film;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * план; не прошёл ни один — в итоге лучший из выведенных с причинами. Перед выводом зритель проходит путь камеры
 * (секции мира строятся там, куда камера посмотрит), стоит у начала, пока мир не построен, и повтор идёт по тику
 * {@code warm} тиков до начала — дым и огонь прежних ударов в кадре, как в игре.
 * <p>
 * Вывод: {@code <out>/<план>/<камера>/} — {@code video.mkv}, {@code state.json} (состояние редактора), {@code qa.jsonl},
 * {@code sounds.jsonl}, {@code verdict.json}; {@code <out>/report.jsonl} — каждый выведенный кандидат,
 * {@code <out>/best.json} — план → лучший. Планы, которые уже есть в {@code best.json}, не переснимаются: запуск после
 * падения идёт дальше. Строки лога — {@code Film: …}, в конце клиент выходит.
 */
public final class Film {
    static final Logger LOG = LogUtils.getLogger();
    /** Тиков: открытие повтора, после открытия, после перемотки, мир у места, стоять в точке пути, найти игрока. */
    private static final int OPEN_WAIT = 4800, SETTLE = 60, SEEK_SETTLE = 30, WORLD_WAIT = 600, POSE_MIN = 6, POSE_MAX = 300, PLAYER_WAIT = 400;

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
    private int poseAt, stepped, reachedAt;

    private record Scored(Cameras.Candidate c, Cameras.Score score) {}

    private Film(Plan plan) {
        this.plan = plan;
        aspect = (double) plan.width() / plan.height();
        best = readBest(plan.out().resolve("best.json"));
        for (Plan.Shot s : plan.shots()) {
            if (best.has(s.name())) continue;
            queue.add(s);
        }
        LOG.info("Film: {} -> {}, {} shots to film: {}", plan.replay(), plan.out(), queue.size(), queue.stream().map(Plan.Shot::name).toList());
        LOG.info("Film: smoke of the effects layer {}", smoke.available() ? "checked" : "not checked (Incoming effects layer not found)");
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onStage);
    }

    /** Съёмка по плану {@code file}: подписки на тик клиента и кадр; дальше всё идёт само. */
    public static void start(Path file) {
        try {
            Plan plan = Plan.read(file);
            Files.createDirectories(plan.out());
            new Film(plan);
        } catch (IOException | RuntimeException e) {
            LOG.error("Film: cannot read plan {}", file, e);
        }
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
                mc.options.onboardAccessibility = false;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(plan.distance());
                mc.options.save();
                Export.open(plan.replay());
                go(State.OPENING);
            }
            case OPENING -> {
                if (mc.level != null && mc.player != null && Export.inReplay()) {
                    // редактор Flashback не активен с интерфейсом — только так задание вывода начинается
                    mc.options.hideGui = true;
                    go(State.SETTLING);
                } else if (waited() > OPEN_WAIT) {
                    LOG.error("Film: replay {} did not open", plan.replay());
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
                Cameras.World w = new Cameras.World();
                if (k != null && !w.loaded(k) && waited() < WORLD_WAIT) {
                    standAt(Cameras.Pose.look(k.add(0, 30, -30), k, 70));
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
                    go(State.WARMING);
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
                go(State.WARMING);
            }
            case WARMING -> {
                // повтор вживую по тику до начала плана, как идёт сам вывод: события модов живые (дым, столбы, огонь);
                // вывод с того же места не перематывает и частиц не чистит
                if (current.c.player() != null) {
                    if (watch() == null) return;
                } else {
                    standAt(standing);
                }
                int at = Export.tick();
                if (at < shot.a()) {
                    if (at != stepped) {
                        stepped = at;
                        Export.seek(at + 1);
                    }
                    reachedAt = ticks;
                    return;
                }
                if (at > shot.a()) LOG.warn("Film: {}: replay went past the start ({} > {}), export will seek", shot.name(), at, shot.a());
                else if (!built() && ticks - reachedAt < POSE_MAX) return;
                export();
            }
            case EXPORTING -> {
                if (current.c.player() != null) watch();
                if (Export.exporting()) {
                    // интерфейс игрока в кадре: окно вывода начинает задание только без него, дальше его можно вернуть
                    if (current.c.gui()) mc.options.hideGui = false;
                    return;
                }
                if (!Export.jobs().isEmpty()) return;
                done();
            }
            default -> {
            }
        }
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

    /** Кандидаты всех камер плана и их оценка; камеры плана (глаза игрока, неподвижная) — первыми, дальше — по оценке. */
    private void solve(Cameras.World w) {
        List<Scored> designed = new ArrayList<>(), rest = new ArrayList<>();
        for (Plan.Camera cam : shot.cameras()) {
            for (Cameras.Candidate c : Cameras.of(cam, shot.subject(), shot.a(), shot.b(), aspect, w)) {
                Scored s = new Scored(c, Cameras.score(c, shot.subject(), shot, aspect, w));
                (c.designed() ? designed : rest).add(s);
            }
        }
        rest.sort(Comparator.comparingDouble(s -> -s.score.total()));
        StringBuilder sb = new StringBuilder();
        for (Scored s : rest.subList(0, Math.min(8, rest.size()))) {
            sb.append(String.format(Locale.ROOT, "\n  %-32s %.3f (approach %.2f, event %.2f, after %.2f, size %.3f, smoke %.2f) %s", s.c.name(),
                    s.score.total(), s.score.approach(), s.score.atKey(), s.score.after(), s.score.size(), s.score.smoke(), s.score.why()));
        }
        LOG.info("Film: {}: {} designed and {} scored cameras, best:{}", shot.name(), designed.size(), rest.size(), sb);
        candidates.clear();
        Stream.concat(designed.stream(), rest.stream()).filter(s -> s.score.total() > 0).limit(plan.tries()).forEach(candidates::add);
        if (candidates.isEmpty()) {
            fail("no camera scored above zero");
            return;
        }
        nextCandidate();
    }

    /** План не снят совсем (нет камеры, другое измерение): в итог с причиной. */
    private void fail(String why) {
        LOG.warn("Film: {}: {}", shot.name(), why);
        JsonObject o = new JsonObject();
        o.addProperty("pass", false);
        o.addProperty("why", why);
        best.add(shot.name(), o);
        writeBest();
        nextShot();
    }

    /** Следующий кандидат: перемотка до начала с запасом на живой повтор, путь камеры (секции мира строятся), вывод. */
    private void nextCandidate() {
        current = candidates.poll();
        if (current == null) {
            // ни один не прошёл: в итог — лучший из выведенных с причинами
            JsonObject o = new JsonObject();
            o.addProperty("pass", false);
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

    /** Задание вывода: состояние редактора, видео, проверка каждого кадра. */
    private void export() {
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
            JsonObject o = new JsonObject();
            o.addProperty("pass", true);
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

    private void writeBest() {
        try {
            Files.writeString(plan.out().resolve("best.json"), new GsonBuilder().setPrettyPrinting().create().toJson(best), StandardCharsets.UTF_8);
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
