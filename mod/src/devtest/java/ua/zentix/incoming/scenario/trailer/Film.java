package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.scenario.trailer.CineCamera.Path;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Съёмка в игре по планам, как у оператора: план ставит камеру ({@link CineCamera}), ждёт прогрузки мира (игра стоит)
 * и своего момента (снаряд подлетел, подрыв), потом пишется — игра замедляется под скорость отрисовки ({@link Recorder}),
 * кадры идут по времени игры. Сценарий — в наследнике ({@link Trailer}): шаги {@link #then}, {@link #run},
 * {@link #onServer} и планы {@link #shot}. Кадры и журнал звуков — в {@code run/scenario/<INCOMING_TRAILER_DIR>/},
 * монтаж — {@code tools/trailer/edit.py}.
 */
abstract class Film {
    private static final Set<ResourceLocation> ALWAYS_LAYERS = Set.of(Incoming.id("flash"), Incoming.id("nuke_flash"));

    final Minecraft mc = Minecraft.getInstance();
    final ArrayDeque<Step> steps = new ArrayDeque<>();
    Recorder rec;
    boolean started;

    /** Интерфейс мода в кадре (прицел, камера снаряда, счётчик Гейгера); вспышки видны всегда. */
    boolean hud;
    /** Игрок виден как наводчик; иначе он невидимкой ходит за камерой, чтобы вокруг неё был загружен мир. */
    boolean actor = true;
    @Nullable
    Shot recording;
    float tickRate = 20;
    long frameClock;
    /** Этот кадр отрисовки — кадр видео (доля тика поставлена под его момент). */
    boolean frameReady;
    /** Попыток поставить окно в размер кадра ({@link #exactFrame}). */
    int frameTries;
    int frameCount;

    /** Время игры подрыва (-1 — ещё не было): мир замирает на подготовку планов, тики клиента идут и тогда. */
    long detonationTime = -1;
    /** Время, на котором закончился прошлый план: камера между планами стоит там. */
    double idleTime;
    /** Камера снаряда показывала видео с борта на прошлом тике (переход с карты — отметка «video» для монтажа). */
    boolean wasViewing, cameraWasActive, closeMarked;
    /** С борта ближе этого к цели земля видна и под шейдерами: монтаж берёт видео с отметки «close». */
    static final double CLOSE_RANGE = 150;
    java.util.Map<Integer, String> seen = java.util.Map.of();
    final java.util.Set<Integer> released = new java.util.HashSet<>();

    /**
     * INCOMING_FILM_REPLAY=1 — план пишется не кадрами, а повтором Flashback с журналом ({@link EventLog}):
     * {@code <папка записи>/replays/<план>.zip} и {@code <план>.events.jsonl}. Камеры по журналу и кадры выводит потом
     * {@link ReplayFilm} из повтора. Запись идёт от конца подготовки (мир прогружен) до конца плана и ещё {@link #TAIL}
     * тиков: момент плана ({@code when}) и его конец — отметки «start» и «end» журнала. Планы с интерфейсом ({@code hud})
     * снимаются кадрами вживую, как без повторов: интерфейс мода в повтор не пишется.
     */
    static final boolean REPLAY = System.getenv("INCOMING_FILM_REPLAY") != null;
    static final int TAIL = 100;
    /** План, который сейчас пишется повтором, и его журнал. */
    @Nullable
    Shot replayShot;
    @Nullable
    EventLog events;

    interface Step {
        /** @return шаг закончен */
        boolean tick();
    }

    Film() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        // журнал — после путей мода (FlightTracks.tick в тике клиента)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::logTick);
        NeoForge.EVENT_BUS.addListener(this::beforeFrame);
        NeoForge.EVENT_BUS.addListener(this::afterFrame);
        NeoForge.EVENT_BUS.addListener(CineCamera::angles);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, CineCamera::fov);
        NeoForge.EVENT_BUS.addListener(this::layer);
        NeoForge.EVENT_BUS.addListener(this::hideActor);
        NeoForge.EVENT_BUS.addListener(this::hideHand);
        NeoForge.EVENT_BUS.addListener(this::hideName);
    }

    // ================================================================ кто в кадре

    /** Самый молодой снаряд этого типа (только что сошёл с пусковой). */
    @Nullable
    <T extends StrikeProjectile> T newest(Class<T> type) {
        T best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (type.isInstance(e) && !e.isRemoved() && ((StrikeProjectile) e).isActive() && (best == null || e.tickCount < best.tickCount)) {
                best = type.cast(e);
            }
        }
        return best;
    }

    @Nullable
    <T extends Entity> T nearest(Class<T> type, Vec3 p, double radius) {
        T best = null;
        double bd = radius * radius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!type.isInstance(e) || e.isRemoved()) continue;
            if (e instanceof StrikeProjectile s && !s.isActive()) continue;
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = type.cast(e);
            }
        }
        return best;
    }

    /** Бот-статист (или любой игрок) по имени, как его видит клиент; нет рядом — null. */
    @Nullable
    Entity bot(String name) {
        for (var p : mc.level.players()) if (p.getGameProfile().getName().equals(name)) return p;
        return null;
    }

    /** Тиков игры после подрыва (-1 — подрыва ещё не было). */
    long sinceDetonation() {
        return detonationTime < 0 ? -1 : mc.level.getGameTime() - detonationTime;
    }

    /** Камера снаряда включена и показывает карту оператора (снаряд дальше зоны видео). */
    static boolean onMap() {
        return ProjectileCamera.isActive() && !ProjectileCamera.isViewing() && !ProjectileCamera.isFilming();
    }

    /** До ближайшего снаряда в полёте от игрока, блоков (по данным сервера — и для снарядов вне мира). */
    double missileRange() {
        double best = Double.MAX_VALUE;
        for (var f : ua.zentix.incoming.client.hud.ClientFlights.all()) best = Math.min(best, f.position(1).distanceTo(mc.player.position()));
        return best;
    }

    /** До подрыва по тревоге, тиков (много — если тревоги нет). */
    long warningTicks() {
        long now = mc.level.getGameTime();
        long left = Long.MAX_VALUE;
        for (var w : ClientNuclear.warnings()) left = Math.min(left, w.detonateTime() - now);
        return left;
    }

    Path chaseOf(@Nullable Entity e, double back, double up, double side, double lead, float roll, double fov) {
        final Entity target = e;
        return CineCamera.chase(() -> target != null && !target.isRemoved() ? target : null, back, up, side, lead, roll, fov);
    }

    /**
     * Куда смотрит камера: на то, что вернул {@code focus} (снаряд, стая), иначе на {@code rest}; переход сглажен
     * по времени игры ({@code rate} — доля пути за тик), чтобы камера не дёргалась при смене цели.
     */
    Supplier<Vec3> smoothFocus(Supplier<?> focus, Vec3 rest, double rate) {
        final Vec3[] look = {rest};
        final double[] lastT = {Double.NaN};
        return () -> {
            Object f = focus.get();
            Vec3 want = f instanceof Entity e ? e.getPosition(CineCamera.partial()) : f instanceof Vec3 v ? v : rest;
            double t = shotTime();
            double dt = Double.isNaN(lastT[0]) ? 1e9 : Math.max(0, t - lastT[0]);
            lastT[0] = t;
            double k = 1 - Math.pow(1 - rate, dt);
            look[0] = look[0].lerp(want, Mth.clamp(k, 0, 1));
            return look[0];
        };
    }

    /**
     * Поле зрения, при котором снаряд {@code focus} виден с {@code from} одного размера: {@code wide} ближе
     * {@code near} блоков, дальше — уже (не меньше {@code narrow}); без снаряда (взрыв) — снова {@code wide}.
     * Сглажено по времени игры, как {@link #smoothFocus}.
     */
    DoubleSupplier rangeFov(Vec3 from, Supplier<? extends Entity> focus, double wide, double narrow, double near) {
        final double[] fov = {Double.NaN};
        final double[] lastT = {Double.NaN};
        return () -> {
            Entity e = focus.get();
            double want = e == null ? wide
                    : Mth.clamp(wide * near / Math.max(near, e.getPosition(CineCamera.partial()).distanceTo(from)), narrow, wide);
            double t = shotTime();
            double dt = Double.isNaN(lastT[0]) ? 1e9 : Math.max(0, t - lastT[0]);
            lastT[0] = t;
            fov[0] = Double.isNaN(fov[0]) ? want : Mth.lerp(Mth.clamp(1 - Math.pow(0.85, dt), 0, 1), fov[0], want);
            return fov[0];
        };
    }

    // ================================================================ шаги

    void then(Step s) {
        steps.add(s);
    }

    void run(Runnable r) {
        then(() -> {
            r.run();
            return true;
        });
    }

    void waitTicks(int n) {
        final int[] left = {n};
        then(() -> --left[0] <= 0);
    }

    /** Ждать условия (проверка — в потоке клиента), не дольше {@code max} тиков. */
    void waitUntil(BooleanSupplier ok, int max) {
        final int[] left = {max};
        then(() -> ok.getAsBoolean() || --left[0] <= 0);
    }

    /** Работа на сервере (поиск мест, высоты): сценарий ждёт, пока она закончится. */
    void onServer(Consumer<ServerLevel> work) {
        final boolean[] done = {false};
        final boolean[] sent = {false};
        then(() -> {
            if (!sent[0]) {
                sent[0] = true;
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    work.accept(server.overworld());
                    mc.execute(() -> done[0] = true);
                });
            }
            return done[0];
        });
    }

    void withPlayer(java.util.function.BiConsumer<ServerLevel, ServerPlayer> work) {
        MinecraftServer server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) work.accept(server.overworld(), p);
        });
    }

    Shot shot(String name) {
        Shot s = new Shot(name);
        then(s);
        return s;
    }

    /** План: подготовка (камера на месте, мир прогружен), ожидание момента, запись. */
    final class Shot implements Step {
        private record Cue(int at, Runnable action) {}

        final String name;
        private int length = 100;
        private double speed = 1;
        private Supplier<Path> camera;
        /** Вид глазами игрока: путь задаёт только взгляд. */
        private Path playerView;
        private boolean projectileCamera;
        private boolean hudOn;
        private boolean hiddenActor;
        private boolean prep = true;
        /** Ждать прогрузки почти на всю дальность прорисовки (общий план с высоты), а не только рядом с камерой. */
        private int readyChunks = 4;
        private BooleanSupplier after, when, end;
        private int afterTimeout, whenTimeout, endDelay;
        private final List<Cue> cues = new ArrayList<>();
        private Runnable atEnd, atPrepare, atReady;

        private int phase, waited, readyFor, t, endAt = -1, liveEnd = -1;
        private Path path;

        Shot(String name) {
            this.name = name;
        }

        Shot length(int ticks) {
            length = ticks;
            return this;
        }

        Shot speed(double s) {
            speed = s;
            return this;
        }

        Shot camera(Supplier<Path> c) {
            camera = c;
            return this;
        }

        Shot player(Path view) {
            playerView = view;
            return this;
        }

        Shot projectileCamera() {
            projectileCamera = true;
            return this;
        }

        Shot hud() {
            hudOn = true;
            return this;
        }

        Shot hidden() {
            hiddenActor = true;
            return this;
        }

        Shot farView() {
            return readyChunks(10);
        }

        /** Перед съёмкой ждать мир на {@code chunks} чанков вокруг камеры (не дальше прорисовки). */
        Shot readyChunks(int chunks) {
            readyChunks = chunks;
            return this;
        }

        Shot noPrep() {
            prep = false;
            return this;
        }

        /** Условие до постановки камеры (например, пусковая уже появилась — от неё строится путь). */
        Shot after(BooleanSupplier c, int timeout) {
            after = c;
            afterTimeout = timeout;
            return this;
        }

        /** Момент начала записи (камера уже на месте). */
        Shot when(BooleanSupplier c, int timeout) {
            when = c;
            whenTimeout = timeout;
            return this;
        }

        /** Конец раньше длины: через {@code delay} тиков после того, как условие стало верным. */
        Shot endWhen(BooleanSupplier c, int delay) {
            end = c;
            endDelay = delay;
            return this;
        }

        Shot cue(int at, Runnable action) {
            cues.add(new Cue(at, action));
            return this;
        }

        /** Действие, когда камера ставится на место (до ожидания момента). */
        Shot prepare(Runnable action) {
            atPrepare = action;
            return this;
        }

        /** Действие, когда камера на месте и мир прогружен (пуск, чей подлёт снимаем, — чтобы не прозевать). */
        Shot onReady(Runnable action) {
            atReady = action;
            return this;
        }

        Shot cueEnd(Runnable action) {
            atEnd = action;
            return this;
        }

        /**
         * INCOMING_TRAILER_PREVIEW=1 — проверка кадров: без ударов, ожидания момента и реплик, по три кадра на план
         * (начало, середина, конец); план, который ждёт сущность ({@code after}), пропускается.
         */
        static final boolean PREVIEW = System.getenv("INCOMING_TRAILER_PREVIEW") != null;

        /** INCOMING_TRAILER_SHOTS=a,b — снимать только эти планы (остальные проигрываются без записи, для отладки). */
        boolean selected() {
            String only = System.getenv("INCOMING_TRAILER_SHOTS");
            return only == null || only.isBlank() || java.util.Arrays.asList(only.split(",")).contains(name);
        }

        boolean player() {
            return playerView != null;
        }

        /** Время плана с долей тика: кадр после тика n показывает мир между тиками n−1 и n. */
        double time() {
            if (phase < 4) return 0;
            return recording == this ? rec.frames() * rec.step() : Math.max(0, t - 1 + CineCamera.partial());
        }

        @Override
        public boolean tick() {
            switch (phase) {
                case 0 -> {
                    if (PREVIEW && after != null) return true;
                    if (after != null && !after.getAsBoolean() && waited++ < afterTimeout) return false;
                    if (after != null && waited > afterTimeout) {
                        Incoming.LOG.warn("TRAILER {}: не дождались условия, план пропущен", name);
                        return true;
                    }
                    waited = 0;
                    hud = hudOn;
                    if (hiddenActor && actor) {
                        actor = false;
                        // без вещей: интерфейс оружия модов (Point Blank) рисуется и у невидимки с оружием в руке
                        withPlayer((level, p) -> {
                            p.setGameMode(GameType.SPECTATOR);
                            p.getInventory().clearContent();
                        });
                    }
                    mc.getToasts().clear();  // «New Recipes Unlocked» и прочие всплывашки не в кадр
                    if (camera != null) {
                        path = camera.get();
                        CineCamera.use(path);
                    } else {
                        CineCamera.release();
                    }
                    if (atPrepare != null) atPrepare.run();
                    setTickRate(20);
                    phase = prep ? 1 : 2;
                    // мир стоит, пока камера ждёт прогрузки: снаряд не долетает до цели раньше, чем начнётся запись
                    if (prep) setFrozen(true);
                    if (!prep && atReady != null && !PREVIEW) atReady.run();
                    Incoming.LOG.info("TRAILER {}: подготовка", name);
                }
                case 1 -> {
                    followCamera();
                    // повтор: в снимок начала записи — все чанки прорисовки (дальняя цель телевика не догружается в кадре),
                    // а строить секции не нужно (кадры строит вывод); вживую — секции вокруг камеры построены
                    boolean replay = REPLAY && !hudOn;
                    readyFor = (replay ? worldReady(Integer.MAX_VALUE, false) : worldReady(readyChunks, true)) ? readyFor + 1 : 0;
                    if (readyFor >= 10 && waited >= 40 || waited > 1200) {
                        setFrozen(false);
                        phase = 2;
                        waited = 0;
                        if (atReady != null && !PREVIEW) atReady.run();
                    } else {
                        waited++;
                    }
                }
                case 2 -> {
                    followCamera();
                    // планы с интерфейсом (глаза игрока, экраны мода) снимаются кадрами вживую и при записи повторов
                    boolean replay = REPLAY && !hudOn;
                    if (replay && selected() && replayShot != this) startReplay(this);
                    if (!PREVIEW && when != null && !when.getAsBoolean()) {
                        if (++waited % 200 == 0) Incoming.LOG.info("TRAILER {}: ждём момент ({} тиков)", name, waited);
                        if (waited < whenTimeout) return false;
                        Incoming.LOG.warn("TRAILER {}: момент не наступил, снимаем как есть", name);
                    }
                    if (replay) {
                        if (replayShot == this) mark("start");
                    } else if (selected()) {
                        rec.start(name, PREVIEW ? Math.max(1, 1.5 * (length - 1)) : speed, hudOn);
                        recording = this;
                        frameClock = 0;
                        setTickRate(Math.max(1, (float) (8 * rec.step())));
                    }
                    phase = 4;
                    t = 0;
                    runCues();
                }
                case 4 -> {
                    followCamera();
                    t++;
                    runCues();
                    if (!PREVIEW && end != null && endAt < 0 && end.getAsBoolean()) endAt = t + endDelay;
                    if (liveEnd < 0 && (t >= length || endAt >= 0 && t >= endAt)) {
                        liveEnd = t;
                        if (replayShot == this) mark("end");
                    }
                    if (liveEnd >= 0 && (replayShot != this || t >= liveEnd + TAIL)) {
                        if (replayShot == this) finishReplay();
                        rec.stop();
                        recording = null;
                        idleTime = time();
                        if (atEnd != null) atEnd.run();
                        setTickRate(20);
                        return true;
                    }
                }
                default -> throw new IllegalStateException();
            }
            return false;
        }

        private void runCues() {
            if (PREVIEW) return;
            for (Cue c : cues) if (c.at == t) c.action.run();
        }

        /** Камера на кадр: кинокамера — по пути, вид игрока — поворот головы. */
        void applyCamera() {
            double time = time();
            if (path != null) CineCamera.apply(time);
            if (playerView != null && mc.player != null) {
                Pose p = playerView.at(time);
                CineCamera.viewFov = p.fov();
                mc.player.setYRot(p.yaw());
                mc.player.yRotO = p.yaw();
                mc.player.setXRot(p.pitch());
                mc.player.xRotO = p.pitch();
                mc.player.setYHeadRot(p.yaw());
                mc.player.yHeadRotO = p.yaw();
            }
        }
    }

    // ================================================================ повтор плана

    /** Папка повторов и журналов этой съёмки. */
    java.nio.file.Path replayDir() {
        return mc.gameDirectory.toPath().resolve(System.getenv().getOrDefault("INCOMING_TRAILER_DIR", "trailer")).resolve("replays");
    }

    /** Начать повтор плана: мир вокруг камеры прогружен, момент плана впереди. */
    void startReplay(Shot shot) {
        if (replayShot != null) finishReplay();
        FlashbackApi.startRecording();
        events = new EventLog(replayDir().resolve(shot.name + ".events.jsonl"));
        replayShot = shot;
        frameClock = 0;
        Incoming.LOG.info("TRAILER {}: повтор пишется", shot.name);
    }

    void finishReplay() {
        Shot shot = replayShot;
        replayShot = null;
        events.close();
        events = null;
        java.nio.file.Path zip = replayDir().resolve(shot.name + ".zip");
        FlashbackApi.finishRecording(zip, shot.name);
        Incoming.LOG.info("TRAILER {}: повтор записан, {}", shot.name, zip);
        setTickRate(20);
    }

    /** Идёт запись плана (кадрами или повтором): отметки для монтажа и камер пишутся. */
    boolean marking() {
        return rec.recording() || events != null;
    }

    /** Отметка в текущий момент: в журнал кадров и в журнал повтора. */
    void mark(String what) {
        if (rec.recording()) rec.mark(what);
        if (events != null) events.mark(FlashbackApi.writtenTicks(), what);
    }

    /** Тик журнала повтора: после всего тика клиента, под номером тика, который запишет повтор. */
    void logTick(ClientTickEvent.Post e) {
        if (events != null) events.tick(FlashbackApi.writtenTicks());
    }

    /**
     * Повтор пишет тик на каждый тик клиента, а клиент между двумя кадрами разбирает пакеты один раз: тиков сервера
     * в секунду не больше кадров (иначе за кадр — несколько тиков без новых пакетов, и в повторе всё движется рывками)
     * и не больше, чем сервер успевает.
     */
    void adaptReplayRate() {
        long now = System.nanoTime();
        if (frameClock == 0) {
            frameClock = now;
            frameCount = 0;
            return;
        }
        if (++frameCount < 10) return;
        double fps = frameCount / ((now - frameClock) / 1e9);
        frameClock = now;
        frameCount = 0;
        double mspt = mc.getSingleplayerServer().getAverageTickTimeNanos() / 1e6;
        double capacity = mspt <= 0 ? 20 : 1000 / mspt;
        float want = (float) Mth.clamp(Math.min(fps * 0.85, capacity * 0.8), 2, 20);
        if (Math.abs(want - tickRate) / tickRate > 0.1) setTickRate(want);
    }

    // ================================================================ события игры

    void onTick(ClientTickEvent.Post e) {
        if (mc.player == null || mc.level == null) return;
        if (!started) {
            started = true;
            // папка записи: дубль отдельных планов (INCOMING_TRAILER_SHOTS) — в свою папку, монтаж берёт их поверх основной
            rec = new Recorder(mc.gameDirectory.toPath().resolve(System.getenv().getOrDefault("INCOMING_TRAILER_DIR", "trailer")));
            mc.getSoundManager().addListener(rec);
            mc.options.hideGui = false;
        }
        if (detonationTime < 0 && !ClientNuclear.detonations().isEmpty()) {
            detonationTime = mc.level.getGameTime();
            if (marking()) mark("detonation");
        }
        boolean viewing = ProjectileCamera.isViewing(), camera = ProjectileCamera.isActive();
        markImpacts(marking());
        if (marking() && viewing && !wasViewing) mark("video");
        if (!viewing) {
            closeMarked = false;
        } else if (marking() && !closeMarked && mc.getCameraEntity() instanceof StrikeProjectile p
                && ua.zentix.incoming.client.hud.ClientFlights.find(p.getUUID()) instanceof ua.zentix.incoming.client.hud.ClientFlights.Tracked f
                && p.position().distanceTo(f.target()) < CLOSE_RANGE) {
            mark("close");
            closeMarked = true;
        }
        // камера снаряда вернулась к игроку: дальше в плане — вид от первого лица, монтаж режет до этой отметки
        if (marking() && !camera && cameraWasActive) mark("exit");
        wasViewing = viewing;
        cameraWasActive = camera;
        while (!steps.isEmpty()) {
            if (!steps.peek().tick()) break;
            steps.poll();
        }
    }

    /**
     * Снаряды, что были в кадре и пропали (взрыв), и сброс бомбы B-2: отметки для монтажа — звук приходит позже
     * картинки. Список обновляется и между планами, иначе снаряды, пропавшие до записи, отмечались бы в начале
     * следующего плана.
     */
    void markImpacts(boolean mark) {
        java.util.Map<Integer, String> now = new java.util.HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof BomberEntity b) {
                if (b.flightPhase() == FlightPhase.EGRESS && released.add(b.getId()) && mark) mark("release");
            } else if (e instanceof StrikeProjectile p && p.isActive()) {
                now.put(e.getId(), e.getType().toShortString());
            }
        }
        for (var e : seen.entrySet()) {
            if (mark && !now.containsKey(e.getKey())) mark("gone:" + e.getValue());
        }
        seen = now;
    }

    /**
     * Перед кадром: при записи — доля тика ровно под момент кадра (кадр после тика n показывает мир между тиками
     * n−1 и n); если клиент ещё не дотикал до момента (кадр отрисовки не в счёт) — кадр не снимается.
     */
    void beforeFrame(RenderFrameEvent.Pre e) {
        if (!started) return;
        frameReady = false;
        if (recording != null) {
            double partial = recording.time() - (recording.t - 1);
            if (partial > 1.0001) {
                FrameClock.advanceNext(partial - 1 + 0.01);
            } else {
                if (partial < -0.02) Incoming.LOG.warn("TRAILER {}: клиент обогнал кадр на {} тика", recording.name, -partial);
                FrameClock.setPartial((float) Mth.clamp(partial, 0, 1));
                frameReady = true;
            }
        }
        if (steps.peek() instanceof Shot shot) shot.applyCamera();
        else CineCamera.apply(idleTime); // между планами камера стоит, где закончила
        holdTarget();
    }

    /**
     * Видео с борта: камера на подвесе смотрит туда, куда игрок ведёт мышью; в трейлере мышь не двигается, и подвес
     * остался бы на курсе входа — в пике в кадре был бы горизонт. Оператор держит цель в центре кадра.
     */
    void holdTarget() {
        if (!ProjectileCamera.isViewing() || !(mc.getCameraEntity() instanceof StrikeProjectile p)
                || !(ua.zentix.incoming.client.hud.ClientFlights.find(p.getUUID()) instanceof ua.zentix.incoming.client.hud.ClientFlights.Tracked f)) {
            return;
        }
        Vec3 d = f.target().subtract(p.getPosition(mc.getTimer().getGameTimeDeltaPartialTick(false)));
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
        float pitch = (float) (-Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
    }

    void afterFrame(RenderFrameEvent.Post e) {
        if (replayShot != null) adaptReplayRate();
        if (recording == null || !frameReady) return;
        rec.frame(recording.time(), mc.gameRenderer.getMainCamera());
        FrameClock.advanceNext(rec.step());
        adaptTickRate();
    }

    /** Время текущего плана (для сглаживания взгляда). */
    double shotTime() {
        return steps.peek() instanceof Shot s ? s.time() : 0;
    }

    /**
     * Сервер идёт в том же темпе, что и запись (тиков в секунду = кадров отрисовки в секунду × шаг кадра): тогда
     * данные о сущностях приходят к клиенту вовремя, а не рывками.
     */
    void adaptTickRate() {
        long now = System.nanoTime();
        if (frameClock == 0) {
            frameClock = now;
            frameCount = 0;
            return;
        }
        if (++frameCount < 10) return;
        double fps = frameCount / ((now - frameClock) / 1e9);
        frameClock = now;
        frameCount = 0;
        float want = (float) Mth.clamp(fps * rec.step(), 1, 20);
        if (Math.abs(want - tickRate) / tickRate > 0.1) setTickRate(want);
    }

    void setFrozen(boolean frozen) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setFrozen(frozen));
    }

    /**
     * Кадр ровно {@code INCOMING_SIZE}: окно без рамки и ровно этого размера. В KWin окно с заголовком ужималось под
     * экран (кадр 1920×1052); не вышло — съёмка не начинается: кадры другого размера монтажу не годятся.
     */
    boolean exactFrame() {
        String[] want = System.getProperty("incoming.frame", "").split("x");
        if (want.length != 2) return true;
        int w = Integer.parseInt(want[0]), h = Integer.parseInt(want[1]);
        var target = mc.getMainRenderTarget();
        if (target.width == w && target.height == h) {
            Incoming.LOG.info("TRAILER кадр {}x{}", w, h);
            return true;
        }
        long window = mc.getWindow().getWindow();
        if (frameTries == 0) {
            Incoming.LOG.info("TRAILER окно {}x{}, нужно {}x{}: убираю рамку и ставлю размер", target.width, target.height, w, h);
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_DECORATED, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        }
        if (frameTries % 20 == 0) org.lwjgl.glfw.GLFW.glfwSetWindowSize(window, w, h);
        if (++frameTries > 200) {
            Incoming.LOG.error("TRAILER кадр {}x{} вместо {}x{}: экран меньше окна? Съёмка остановлена", target.width, target.height, w, h);
            Runtime.getRuntime().halt(3);
        }
        return false;
    }

    void setTickRate(float rate) {
        if (rate == tickRate) return;
        tickRate = rate;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> server.tickRateManager().setTickRate(rate));
    }

    /**
     * Мир на {@code chunks} чанков вокруг камеры получен и собран в секции для отрисовки. Общим планам с высоты — 10:
     * первый план в свежем мире иначе снимал бы деревню на «острове» над пустотой; видео с борта — вся прорисовка (ракета
     * смотрит за деревню). Остальным хватает 4: пока ждём дальние, снаряд уже долетает, и план удара снимался бы без удара.
     */
    /** Чанки в {@code chunks} от камеры (не дальше прорисовки без двух) у клиента; {@code render} — и секции построены. */
    boolean worldReady(int chunks, boolean render) {
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        int cx = Mth.floor(cam.x) >> 4, cz = Mth.floor(cam.z) >> 4;
        int r = Math.max(4, Math.min(chunks, mc.options.getEffectiveRenderDistance() - 2));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return !render || mc.levelRenderer.hasRenderedAllSections();
    }

    /** Невидимый игрок держится рядом с кинокамерой: сервер шлёт мир вокруг игрока, а не вокруг камеры. */
    void followCamera() {
        Pose p = CineCamera.pose();
        if (actor || p == null || mc.player == null) return;
        if (mc.player.position().distanceToSqr(p.pos()) < 24 * 24) return;
        Vec3 at = p.pos();
        withPlayer((level, sp) -> sp.teleportTo(level, at.x, at.y, at.z, sp.getYRot(), sp.getXRot()));
    }

    /** Интерфейс игры в кадре не нужен; интерфейс мода — только в планах «с интерфейсом», вспышки — всегда. */
    void layer(RenderGuiLayerEvent.Pre e) {
        if (!started) return;
        ResourceLocation id = e.getName();
        if (ALWAYS_LAYERS.contains(id)) return;
        if (hud && id.getNamespace().equals(Incoming.MOD_ID)) return;
        e.setCanceled(true);
    }

    /** Спрятанный игрок-невидимка не виден; статисты (боты) — видны. */
    void hideActor(RenderPlayerEvent.Pre e) {
        if (started && !actor && e.getEntity() == mc.player) e.setCanceled(true);
    }

    void hideName(net.neoforged.neoforge.client.event.RenderNameTagEvent e) {
        if (started) e.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    void hideHand(RenderHandEvent e) {
        if (CineCamera.active()) e.setCanceled(true);
    }

    // ================================================================ мелочи

    void fire(String what, Vec3 at) {
        cmd(String.format(Locale.ROOT, "incoming %s at %.1f %.1f %.1f", what, at.x, at.y, at.z));
    }

    static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("TRAILER /{}", c);
    }

    static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90f;
    }

    static float pitchTo(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return (float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
    }
}
