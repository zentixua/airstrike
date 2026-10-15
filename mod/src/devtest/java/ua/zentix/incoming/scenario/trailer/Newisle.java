package ua.zentix.incoming.scenario.trailer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.Keys;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.screen.MapScreen;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.world.HeapWatch;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.scenario.trailer.CineCamera.Pose;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.scenario.trailer.NewisleData.Frame;
import ua.zentix.incoming.scenario.trailer.NewisleData.Strike;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Local;
import ua.zentix.incoming.util.Terrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Постановщик трейлера на Newisle: один дубль ({@code INCOMING_NEWISLE_TAKE}) из {@code tools/trailer/scenes/newisle-takes.json}
 * на копии мира, которую дал конвейер ({@code tools/trailer/produce.py}). Время суток и погода стоят весь дубль, действия
 * сцены ({@code stage}), записи планов по порядку истории, действия после них ({@code after}); в конце — ждёт полёты,
 * сохраняет мир (снимок для следующего дубля цепочки) и пишет итог дубля JSON ({@code INCOMING_NEWISLE_RESULT}).
 * Удары — командами мода, как оператор: пуски по одному времени ({@link NewisleData#schedule}), опорный — по времени до
 * удара из пакетов сервера; пуски идут через один разносчик ({@link #launch}) и не встают в один тик. Камеры — из разведки ({@code newisle-poses.json}); общий план повтора ставит вывод.
 * <p>
 * Режимы: {@code INCOMING_NEWISLE_LIVE=1} — живой дубль Артёма (он в кадре сам, подсказки на экране, камера-призрак),
 * {@code INCOMING_NEWISLE_STANDIN=1} — вместо него бот (слепой прогон), {@code INCOMING_NEWISLE_VARIANT=1|2} — пересъёмка
 * запасной камерой, {@code INCOMING_NEWISLE_FAULT=camera} — нарочно плохая камера первого плана (проверка ограждений
 * конвейера), {@code INCOMING_NEWISLE_TRIAL=<поиск>[:<значение>]} — проба поиска P1 вместо дубля.
 */
final class Newisle {
    static boolean selected() {
        return "newisle".equals(System.getenv("INCOMING_TRAILER_MAP"));
    }

    /** Строка лога: мир дубля сохранён — конвейер берёт эту копию снимком для следующего дубля. */
    static final String SAVED = "NEWISLE world saved";

    private final Trailer film;
    private final Minecraft mc;
    private final War war;
    @Nullable
    private final NewisleData data;
    private final String takeId;
    @Nullable
    private final JsonObject take;
    @Nullable
    private final String trial;
    private final boolean live, standin;
    private final int variant;
    private final String fault;
    private final Path resultFile;
    private final JsonObject result = new JsonObject();
    private final JsonArray shotsOut = new JsonArray(), warnings = new JsonArray();
    private final long startedMs = System.currentTimeMillis();
    private final RandomSource random;
    private final Map<String, Crowd> crowds = new HashMap<>();
    private final Map<String, Fight> squads = new HashMap<>();
    private final Map<String, List<BlockPos>> racks = new HashMap<>();
    private final Map<String, WeaponType> rackWeapon = new HashMap<>();
    private final Set<String> creative = new LinkedHashSet<>(), bots = new LinkedHashSet<>();
    private final List<Probe> probes = new ArrayList<>();
    // пишется потоком клиента (пуск), читается потоком сервера каждый тик (watchStrikes)
    private final List<Launched> launched = new java.util.concurrent.CopyOnWriteArrayList<>();
    @Nullable
    private Silo silo;
    private List<String> hints = List.of();
    private int hintFrom, hintTo;
    private boolean enterSeen, firstShot = true;
    @Nullable
    private String problem;
    @Nullable
    private Vec3 lastHidden;
    private final NewisleData.Heights heights = new NewisleData.Heights() {
        @Override
        public double top(double x, double z) {
            return server(level -> (double) War.groundY(level, x, z));
        }

        @Override
        public double ground(double x, double z) {
            return server(level -> (double) natural(level, x, z));
        }
    };

    Newisle(Trailer film) {
        this.film = film;
        this.mc = film.mc;
        this.war = new War(film);
        this.takeId = System.getenv().getOrDefault("INCOMING_NEWISLE_TAKE", "");
        String t = System.getenv("INCOMING_NEWISLE_TRIAL");
        this.trial = t == null || t.isBlank() ? null : t;
        this.live = "1".equals(System.getenv("INCOMING_NEWISLE_LIVE"));
        this.standin = "1".equals(System.getenv("INCOMING_NEWISLE_STANDIN"));
        this.variant = Integer.parseInt(System.getenv().getOrDefault("INCOMING_NEWISLE_VARIANT", "0"));
        this.fault = System.getenv().getOrDefault("INCOMING_NEWISLE_FAULT", "");
        String rf = System.getenv("INCOMING_NEWISLE_RESULT");
        this.resultFile = rf == null || rf.isBlank() ? film.recordDir().resolve("newisle-result-" + (trial != null ? "trial" : takeId) + ".json")
                : Path.of(rf);
        this.random = RandomSource.create(1000L + takeId.hashCode() * 31L + variant);
        NewisleData d = null;
        try {
            d = NewisleData.load(NewisleData.scenes(mc.gameDirectory.toPath()));
        } catch (IOException | RuntimeException e) {
            problem = "данные сцен не прочитаны: " + e;
        }
        this.data = d;
        this.take = d == null || trial != null ? null : d.take(takeId);
        if (problem == null && trial == null && take == null) problem = "нет дубля " + takeId;
        if (problem == null && take != null) problem = preflight();
        result.addProperty("take", trial != null ? "trial:" + trial : takeId);
        result.addProperty("variant", variant);
        result.addProperty("live", live);
        result.addProperty("standin", standin);
        result.addProperty("fault", fault);
        result.addProperty("shots_selected", System.getenv().getOrDefault("INCOMING_TRAILER_SHOTS", ""));
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::serverTickStart);
        NeoForge.EVENT_BUS.addListener(this::serverTickEnd);
        NeoForge.EVENT_BUS.addListener(this::drawHints);
    }

    /**
     * Миллисекунды на тик встроенного сервера ({@code ServerTickEvent.Pre/Post}, {@code System.nanoTime} в потоке
     * сервера), с точностью 0,1 мс; ключ — время игры в конце тика. Пишет поток сервера, читает поток клиента в конце
     * записи ({@code mspt} у плана) — {@link java.util.concurrent.ConcurrentHashMap}.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Double> serverTickMs = new java.util.concurrent.ConcurrentHashMap<>();
    private long tickStartNanos;

    private void serverTickStart(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) {
        tickStartNanos = System.nanoTime();
    }

    private void serverTickEnd(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        double ms = (System.nanoTime() - tickStartNanos) / 1e6;
        serverTickMs.put(e.getServer().overworld().getGameTime(), Math.round(ms * 10) / 10.0);
        watchStrikes(e.getServer());
    }

    /** Первый тик сервера, когда каждый его снаряд появился: по нему удары записи привязываются к снарядам. */
    private final Map<UUID, Long> serverSeen = new HashMap<>();

    /**
     * Удары записи у сервера, в его тиках: привязка — новый снаряд того же оружия у цели (в пределах 5 СКО рассеивания),
     * первым появившимся после пуска; полёт кончился (снаряда нет или он убран) — тик удара. B-2 не смотрим: его удар —
     * бомба после сброса, у сервера это другой снаряд (остаётся клиентская привязка из {@code ClientFlights}).
     */
    private void watchStrikes(MinecraftServer server) {
        if (launched.isEmpty()) return;
        long now = server.overworld().getGameTime();
        Map<UUID, StrikeProjectile> live = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (StrikeProjectile p : StrikeWorld.projectiles(level)) live.put(p.getUUID(), p);
        }
        for (Map.Entry<UUID, StrikeProjectile> e : live.entrySet()) serverSeen.merge(e.getKey(), now, Math::min);
        for (Launched l : launched) {
            if (l.strike.weapon() == WeaponType.BUNKER) continue;
            if (l.watchFrom == -2) l.watchFrom = now;
            if (l.serverId == null) {
                StrikeProjectile pick = null;
                for (Map.Entry<UUID, StrikeProjectile> e : live.entrySet()) {
                    StrikeProjectile p = e.getValue();
                    Long seen = serverSeen.get(e.getKey());
                    // появился после пуска: раньше — это чей-то прошлый полёт
                    if (seen == null || seen < l.watchFrom || p.isRemoved()) continue;
                    if (p.weapon() != l.strike.weapon()) continue;
                    Vec3 aim = p.aimPoint();
                    if (Math.hypot(aim.x - l.target.x, aim.z - l.target.z) >= l.reach()) continue;
                    UUID mine = e.getKey();
                    if (launched.stream().anyMatch(o -> mine.equals(o.serverId))) continue;
                    if (pick == null || seen < serverSeen.get(pick.getUUID())) pick = p;
                }
                if (pick != null) {
                    l.serverId = pick.getUUID();
                    l.firstSeenServer = serverSeen.get(pick.getUUID());
                }
                continue;
            }
            if (l.goneServer < 0) {
                StrikeProjectile p = live.get(l.serverId);
                if (p == null || p.isRemoved()) l.goneServer = now;
            }
        }
        serverSeen.keySet().retainAll(live.keySet());
    }

    /**
     * Чего нет для съёмки дубля: позы камер его записей (первый план каждой — камера записи), места {@code camera:<план>}
     * в действиях. Нет — дубль не начинается: конвейер встаёт «остановлен, жду» с этой строкой.
     */
    @Nullable
    private String preflight() {
        Set<String> need = new LinkedHashSet<>();
        for (JsonElement e : take.getAsJsonArray("shots")) {
            JsonObject s = e.getAsJsonObject();
            String cam = s.has("camera") ? s.get("camera").getAsString() : "scene";
            if (!cam.equals("onboard")) need.add(s.getAsJsonArray("plans").get(0).getAsString());
        }
        collectCameras(take, need);
        List<String> missing = need.stream().filter(p -> !data.poses.containsKey(p)).toList();
        return missing.isEmpty() ? null : "нет поз камер " + String.join(", ", missing) + " (newisle-poses.json — разведка P1)";
    }

    private static void collectCameras(JsonElement e, Set<String> out) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().startsWith("camera:")) {
            out.add(e.getAsString().substring("camera:".length()));
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectCameras(x, out);
        } else if (e.isJsonObject()) {
            for (var x : e.getAsJsonObject().entrySet()) collectCameras(x.getValue(), out);
        }
    }

    // ================================================================ дубль

    void stage() {
        if (problem != null) {
            String why = problem;
            film.run(() -> stop(why));
            return;
        }
        war.config();
        if (trial != null) {
            trial();
            finish();
            return;
        }
        boolean siren = !take.has("siren") || take.get("siren").getAsBoolean();
        long clock = data.clock(take.get("clock").getAsString());
        film.run(() -> {
            Film.cmd("time set " + clock);
            Film.cmd("weather clear");
            // сирена у цели — настройка мира: n49–n50 (первый удар) — без тревоги, дальше — с ней
            IncomingConfig.SERVER.siren.set(siren);
        });
        if (take.has("artem")) artem(take.getAsJsonObject("artem"));
        for (JsonElement a : list(take, "stage")) step(a.getAsJsonObject());
        JsonArray shots = take.getAsJsonArray("shots");
        for (int i = 0; i < shots.size(); i++) shot(shots.get(i).getAsJsonObject());
        if (take.getAsJsonArray("shots").isEmpty() && take.has("cover")) result.add("cover", take.get("cover"));
        for (JsonElement a : list(take, "after")) step(a.getAsJsonObject());
        finish();
    }

    private static JsonArray list(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
    }

    /**
     * Дубль с Артёмом: вживую он сам в кадре (творческий режим — урона нет), подсказки на экране, начало — по Enter; без
     * него на слепом прогоне — бот на его месте; в цепочке (удары без записи) — никого.
     */
    private void artem(JsonObject a) {
        JsonElement stand = a.get("stand"), look = a.get("look");
        String item = a.has("item") ? a.get("item").getAsString() : null;
        boolean boat = a.has("boat") && a.get("boat").getAsBoolean();
        List<String> lines = new ArrayList<>();
        for (JsonElement h : a.getAsJsonArray("hints")) lines.add(h.getAsString());
        if (live) {
            war.actor(() -> feet(stand), () -> at(look, 0), item);
            if (boat) boatFor(stand, () -> mc.player.getGameProfile().getName());
            film.run(() -> {
                hints = lines;
                hintFrom = 0;
                hintTo = Math.min(2, lines.size());
                enterSeen = false;
            });
            // полчаса на то, чтобы встать и нажать Enter; не нажал — дубль не снят, конвейер ждёт
            film.waitUntil(() -> enterSeen, 20 * 60 * 30);
            film.run(() -> {
                if (!enterSeen) stop("Артём не нажал Enter за 30 минут");
                hintFrom = Math.min(2, lines.size());
                hintTo = lines.size();
            });
        } else if (standin) {
            srv(level -> {
                Vec3 p = feet(stand), l = at(look, 0);
                spawn("Artem", p, Film.yawTo(p, l));
            });
            war.settle();
            film.run(() -> film.withServer(s -> {
                if (item != null) film.cast().command("item replace entity Artem weapon.mainhand with " + item);
                film.cast().act("Artem", War.one(Cast.lookAt(at(look, 0))), true);
            }));
            if (boat) boatFor(stand, () -> "Artem");
        }
    }

    /** Лодка у места и {@code rider} в ней. */
    private void boatFor(JsonElement at, java.util.function.Supplier<String> rider) {
        film.run(() -> {
            Vec3 p = feet(at);
            film.withServer(s -> {
                film.cast().command(String.format(Locale.ROOT, "summon minecraft:oak_boat %.2f %.2f %.2f {Tags:[\"nw_boat\"]}", p.x + 0.5, p.y, p.z + 0.5));
            });
        });
        film.waitTicks(10);
        film.run(() -> film.withServer(s -> film.cast().command("ride " + rider.get() + " mount @e[tag=nw_boat,limit=1,sort=nearest]")));
        film.waitTicks(10);
    }

    // ================================================================ записи

    /** Состояние записи у её шагов: удары, опорный удар, момент. */
    private final class Run {
        final JsonObject shot;
        final String name;
        final List<String> plans = new ArrayList<>();
        final JsonObject out = new JsonObject();
        List<Strike> strikes = List.of();
        NewisleData.Schedule schedule;
        final List<Launched> mine = new ArrayList<>();
        final Vec3[] aims;
        boolean moment = true, whenSeen;
        long startTick = -1;
        // тот же старт по часам сервера: клиентские штампы под нагрузкой ударов отстают от него (T21, ночь 15.10)
        volatile long startServer = -1;

        Run(JsonObject shot) {
            this.shot = shot;
            for (JsonElement p : shot.getAsJsonArray("plans")) plans.add(p.getAsString());
            this.name = plans.get(0);
            int n = shot.has("strikes") ? shot.getAsJsonArray("strikes").size() : 0;
            this.aims = new Vec3[Math.max(1, n)];
            java.util.Arrays.fill(aims, Vec3.ZERO);
        }

        /** Секунд до момента опорного удара (удар или точка пролёта): по пакету сервера, до него — по расписанию. */
        double remaining() {
            if (schedule == null || mine.isEmpty()) return Double.POSITIVE_INFINITY;
            Launched ref = mine.stream().filter(l -> l.index == schedule.reference()).findFirst().orElse(null);
            return ref == null ? Double.POSITIVE_INFINITY : ref.remaining();
        }
    }

    private void shot(JsonObject s) {
        Run run = new Run(s);
        int length = (int) Math.round(s.get("seconds").getAsDouble() * 20);
        String cam = s.has("camera") ? s.get("camera").getAsString() : "scene";
        boolean ghost = live && take.has("artem");
        if (s.has("stage_player")) {
            JsonObject sp = s.getAsJsonObject("stage_player");
            war.actor(() -> feet(sp.get("at")), () -> at(sp.get("look"), 0), sp.has("item") ? sp.get("item").getAsString() : null);
        }
        Film.Shot shot = film.shot(run.name).length(length);
        boolean fault = firstShot && variant == 0 && this.fault.equals("camera");
        firstShot = false;
        switch (cam) {
            case "player" -> shot.player(view(run.name));
            case "scope" -> shot.player(view(run.name)).distant();
            case "onboard" -> shot.projectileCamera().readyChunks(12).cueEnd(ProjectileCamera::exit);
            case "fpv" -> shot.hidden().camera(() -> fpv(run.name, length, fault));
            default -> {
                if (ghost) shot.ghost().camera(() -> path(run.name, fault));
                else shot.hidden().camera(() -> path(run.name, fault));
            }
        }
        if (bool(s, "hud") || cam.equals("onboard") || cam.equals("scope")) shot.hud();
        if (bool(s, "chat")) shot.chat();
        if (bool(s, "live")) shot.live();
        if (bool(s, "distant")) shot.distant();
        if (s.has("readyChunks")) shot.readyChunks(s.get("readyChunks").getAsInt());
        if (s.has("watch")) {
            JsonObject w = s.getAsJsonObject("watch");
            shot.watch(WeaponType.parse(w.get("weapon").getAsString()), w.get("r").getAsDouble(), run.aims.length, run.aims);
        }
        JsonObject when = s.has("when") ? s.getAsJsonObject("when") : null;
        boolean explicit = when != null;
        shot.onReady(() -> ready(run, explicit));
        BooleanSupplier moment = moment(run, when);
        if (moment != null) {
            shot.when(() -> {
                boolean ok = moment.getAsBoolean();
                run.whenSeen = ok;
                return ok;
            }, 20 * 240);
        }
        shot.cue(0, () -> {
            run.startTick = mc.level.getGameTime();
            film.withServer(sv -> run.startServer = sv.overworld().getGameTime());
            run.moment = moment == null || run.whenSeen;
            if (!run.moment) warn(run.name + ": момент не наступил, записано как есть");
            for (String p : run.plans) {
                double off = NewisleData.offset(s, p);
                if (off <= 0) film.mark("plan:" + p);
                else war.later((int) Math.round(off * 20), () -> {
                    if (film.marking()) film.mark("plan:" + p);
                });
            }
            // все пуски после начала плана — по секундам плана
            if (run.schedule != null && !run.schedule.waits()) {
                for (int i = 0; i < run.strikes.size(); i++) {
                    int k = i;
                    war.later((int) Math.round(run.schedule.launchAt()[i] * 20), () -> launch(run, k));
                }
            }
        });
        for (JsonElement c : list(s, "cues")) {
            JsonObject cue = c.getAsJsonObject();
            Runnable r = now(cue, run);
            shot.cue((int) Math.round(cue.get("at").getAsDouble() * 20), r);
        }
        List<Runnable> ends = new ArrayList<>();
        for (JsonElement e : list(s, "end")) ends.add(now(e.getAsJsonObject(), run));
        shot.cueEnd(() -> {
            if (cam.equals("onboard")) ProjectileCamera.exit();
            ends.forEach(Runnable::run);
            closeShot(run);
        });
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    /** Камера готова: удары по расписанию, действия {@code ready}, пробы до удара. */
    private void ready(Run run, boolean explicitWhen) {
        try {
            run.strikes = data.strikes(run.shot, heights);
        } catch (RuntimeException e) {
            warn(run.name + ": удары не разобраны: " + e.getMessage());
            run.strikes = List.of();
        }
        for (int i = 0; i < run.strikes.size() && i < run.aims.length; i++) run.aims[i] = run.strikes.get(i).to();
        if (!run.strikes.isEmpty()) {
            // пусковая встаёт, только если чанк места пуска готов (у клиента он есть — сервер прислал); иначе старт вне мира
            run.schedule = NewisleData.schedule(run.strikes, data.speeds(), explicitWhen,
                    f -> mc.level.getChunkSource().hasChunk((int) Math.floor(f.x) >> 4, (int) Math.floor(f.z) >> 4));
            if (run.schedule.waits()) {
                int ref = run.schedule.reference();
                launch(run, ref);
                for (int i = 0; i < run.strikes.size(); i++) {
                    if (i == ref) continue;
                    int k = i;
                    double thr = run.schedule.threshold(i, run.strikes);
                    war.once(run.name + " пуск " + k, () -> run.remaining() <= thr, 20 * 240, () -> launch(run, k));
                }
            }
        }
        for (JsonElement a : list(run.shot, "ready")) now(a.getAsJsonObject(), run).run();
        for (JsonElement p : list(run.shot, "probes")) probes.add(probe(p.getAsJsonObject()));
    }

    /**
     * Момент записи: задан ({@code when}) — по нему; нет — удары плана: опорному осталось его {@code eta}; без ударов —
     * сразу. null — план начинается сразу.
     */
    @Nullable
    private BooleanSupplier moment(Run run, @Nullable JsonObject when) {
        if (when == null) {
            boolean strikes = run.shot.has("strikes") || run.plans.stream().anyMatch(p -> data.plan(p) != null && data.plan(p).has("strikes"));
            if (!strikes) return null;
            return () -> run.schedule == null || !run.schedule.waits() || run.remaining() <= run.strikes.get(run.schedule.reference()).eta();
        }
        String kind = when.get("kind").getAsString();
        return switch (kind) {
            case "none" -> null;
            case "siren" -> {
                WeaponType w = WeaponType.parse(when.get("weapon").getAsString());
                double lead = when.has("lead") ? when.get("lead").getAsDouble() : 0;
                double siren = w.spec().sirenLead() / 20.0;
                yield () -> run.mine.stream().mapToDouble(Launched::remaining).min().orElse(Double.POSITIVE_INFINITY) <= siren + lead;
            }
            case "lit" -> {
                String rack = when.get("rack").getAsString();
                int index = when.has("index") ? when.get("index").getAsInt() : -1;
                yield () -> {
                    List<BlockPos> all = racks.getOrDefault(rack, List.of());
                    if (all.isEmpty()) return true;
                    List<BlockPos> r = index >= 0 && index < all.size() ? List.of(all.get(index)) : all;
                    return war.lit(entity(rackWeapon.getOrDefault(rack, WeaponType.MISSILE)), r);
                };
            }
            case "interceptor" -> {
                double r = when.get("r").getAsDouble();
                var place = once(when.get("at"));
                yield () -> film.nearest(InterceptorEntity.class, place.get(), r) != null;
            }
            case "onboard" -> {
                WeaponType w = WeaponType.parse(when.get("weapon").getAsString());
                double r = when.get("r").getAsDouble();
                var place = once(when.get("near"));
                yield () -> {
                    Vec3 near = place.get();
                    Launched l = run.mine.isEmpty() ? null : run.mine.get(0);
                    if (l == null || !War.followFlight(w, l.target)) return false;
                    ClientFlights.Tracked f = l.flight();
                    return f != null && f.position(1).distanceTo(near) < r;
                };
            }
            case "warning" -> {
                double below = when.get("below").getAsDouble() * 20;
                yield () -> film.warningTicks() < below;
            }
            case "front" -> {
                double lead = when.get("lead").getAsDouble() * 20;
                var place = once(when.get("at"));
                yield () -> War.frontIn(place.get()) < lead;
            }
            case "icbm" -> {
                double above = when.get("above").getAsDouble();
                yield () -> {
                    IcbmEntity e = film.newest(IcbmEntity.class);
                    double base = silo != null ? silo.ground : 64;
                    return e != null && e.getY() > base + above;
                };
            }
            default -> throw new IllegalArgumentException(run.name + ": момент " + kind);
        };
    }

    /** Точка сценария один раз, к первому вопросу (условия момента спрашивают каждый тик, а высота — у сервера). */
    private java.util.function.Supplier<Vec3> once(JsonElement spec) {
        Vec3[] p = new Vec3[1];
        return () -> p[0] != null ? p[0] : (p[0] = at(spec, 0));
    }

    /** Конец записи: что было на деле (момент, удары и их время) — в итог дубля. */
    private void closeShot(Run run) {
        JsonObject o = run.out;
        JsonArray plans = new JsonArray();
        run.plans.forEach(plans::add);
        o.add("plans", plans);
        o.addProperty("replay", run.name);
        o.addProperty("moment", run.moment);
        o.addProperty("selected", selected(run.name));
        JsonArray st = new JsonArray();
        for (Launched l : run.mine) st.add(l.report(run.startTick, run.startServer));
        o.add("strikes", st);
        // сервер под отставание дёргает цели (Артём, 14.10): миллисекунды на тик от начала плана до конца
        // (startTick — та же база, что actual ударов; тик с временем игры t — тик, кончившийся в t)
        if (run.startTick >= 0) {
            JsonArray mspt = new JsonArray();
            for (long t = run.startTick + 1; t <= mc.level.getGameTime(); t++) {
                Double v = serverTickMs.get(t);
                if (v != null) mspt.add(v);
            }
            o.add("mspt", mspt);
        }
        if (run.shot.has("cover")) o.add("cover", run.shot.get("cover"));
        shotsOut.add(o);
    }

    private static boolean selected(String shot) {
        String only = System.getenv("INCOMING_TRAILER_SHOTS");
        return only == null || only.isBlank() || java.util.Arrays.asList(only.split(",")).contains(shot);
    }

    // ================================================================ камеры

    /** Кадры разведки плана с поправкой пересъёмки: запасная камера — выше и шире; нарочно плохая — под землёй. */
    private List<Frame> frames(String plan, boolean fault) {
        List<Frame> f = data.poses.get(plan);
        if (f == null) throw new IllegalStateException("нет позы " + plan);
        double up = 1.5 * variant + (fault ? -40 : 0), wider = 6.0 * variant;
        if (up == 0 && wider == 0) return f;
        List<Frame> out = new ArrayList<>();
        for (Frame x : f) out.add(new Frame(x.t(), x.eye().add(0, up, 0), x.yaw(), x.pitch(), Math.min(100, x.fov() + wider)));
        return out;
    }

    private CineCamera.Path path(String plan, boolean fault) {
        List<Frame> f = frames(plan, fault);
        return t -> NewisleData.poseAt(f, t);
    }

    /** FPV по точкам разведки: весь путь — за план, взгляд вперёд по пути, крен в поворотах. */
    private CineCamera.Path fpv(String plan, int length, boolean fault) {
        List<Frame> f = frames(plan, fault);
        List<Vec3> pts = f.stream().map(Frame::eye).toList();
        if (pts.size() < 2) return path(plan, fault);
        double len = new CineCamera.Spline(pts).length();
        return CineCamera.fpv(pts, Math.max(0.2, len / Math.max(1, length)), 6, 0.6, 0.05, f.get(0).fov());
    }

    /** Вид глазами игрока: курс и тангаж разведки (место — где стоит игрок). */
    private CineCamera.Path view(String plan) {
        List<Frame> f = data.poses.get(plan);
        return t -> {
            Pose p = NewisleData.poseAt(f, t);
            return new Pose(Vec3.ZERO, p.yaw(), p.pitch(), 0, p.fov());
        };
    }

    // ================================================================ удары

    /** Пущенный удар: его полёт у клиента ({@code ClientFlights}), когда пущен и когда был у цели (пролёта). */
    private final class Launched {
        final int index;
        final Strike strike;
        final Vec3 target;
        final double flight, after, delay;
        final long at;
        final Set<UUID> before = new HashSet<>();
        @Nullable
        UUID id;
        boolean dropped;
        long gone = -1, nearestTick = -1;
        double nearest = Double.MAX_VALUE;
        // тот же снаряд у сервера: его полёты уходят из списка ровно на тике удара, а часы клиента под нагрузкой
        // ковра отстают от сервера на тик синхронизации времени (T21, ночь 15.10: три полёта «пропали» у клиента
        // разом на 1 с раньше своих ударов) — время удара меряем по тикам сервера
        @Nullable
        volatile UUID serverId;
        volatile long watchFrom = -2, firstSeenServer = -1, goneServer = -1;

        Launched(int index, Strike strike, double flight, double after, double delay) {
            this.index = index;
            this.strike = strike;
            this.target = strike.to();
            this.flight = flight;
            this.after = after;
            this.delay = delay;
            this.at = mc.level.getGameTime();
            for (ClientFlights.Tracked f : ClientFlights.all()) before.add(f.id);
        }

        @Nullable
        ClientFlights.Tracked flight() {
            return id == null ? null : ClientFlights.find(id);
        }

        /** До момента: время до удара из пакета сервера без хода от точки пролёта до цели; пакета ещё нет — по расписанию. */
        double remaining() {
            ClientFlights.Tracked f = flight();
            if (f != null) return f.etaSeconds(0) - after;
            if (gone >= 0) return -1;
            return flight - (mc.level.getGameTime() - at) / 20.0;
        }

        /** Где цель полёта у клиента бывает от точки приказа: 16 блоков, у неуправляемого — 5 СКО рассеивания (паспорт). */
        double reach() {
            return Math.max(16, 5 * strike.weapon().spec().route().sigma(Math.hypot(target.x - strike.from().x, target.z - strike.from().z)));
        }

        /** Новый, ничей полёт того же оружия у цели. */
        boolean candidate(ClientFlights.Tracked f) {
            return !before.contains(f.id) && f.weapon() == strike.weapon() && launched.stream().noneMatch(l -> f.id.equals(l.id))
                    && Math.hypot(f.target().x - target.x, f.target().z - target.z) < reach();
        }

        void tick() {
            if (id == null) {
                // свой — первый появившийся (номер ClientFlights): пуски идут по порядку, а цель у клиента — с рассеиванием
                // (у «Града» СКО — 1 % дальности, ~10 блоков; соседние цели n21 — через 15 блоков)
                ClientFlights.Tracked pick = null;
                for (ClientFlights.Tracked f : ClientFlights.all()) {
                    if (candidate(f) && (pick == null || f.number < pick.number)) pick = f;
                }
                if (pick != null) id = pick.id;
                return;
            }
            ClientFlights.Tracked f = flight();
            long now = mc.level.getGameTime();
            if (f == null) {
                // B-2 после сброса уходит из полётов (FlightStatus), его бомба приходит тем же пакетом: удар — она
                if (gone < 0 && !dropped && strike.weapon() == WeaponType.BUNKER) {
                    for (ClientFlights.Tracked b : ClientFlights.all()) {
                        if (candidate(b)) {
                            id = b.id;
                            dropped = true;
                            return;
                        }
                    }
                }
                if (gone < 0) gone = now;
                return;
            }
            if (strike.pass() != null) {
                Vec3 p = f.position(1);
                double d = Math.hypot(p.x - strike.pass().x, p.z - strike.pass().z);
                if (d < nearest) {
                    nearest = d;
                    nearestTick = now;
                }
            }
        }

        JsonObject report(long start, long startServer) {
            JsonObject o = new JsonObject();
            o.addProperty("weapon", strike.weapon().getSerializedName());
            // удар сдвинут разносчиком пусков: eta — с задержкой, иначе проверка срока (±1 с) его провалит
            o.addProperty("eta", strike.eta() + delay);
            o.addProperty("delay", delay);
            o.addProperty("pass", strike.pass() != null);
            o.addProperty("bound", id != null || serverId != null);
            o.addProperty("flight", flight);
            if (startServer >= 0 && serverId != null) {
                // часы сервера: пущен — когда снаряд появился у него, удар — когда полёт кончился
                if (firstSeenServer >= 0) o.addProperty("launched", (firstSeenServer - startServer) / 20.0);
                if (goneServer >= 0) o.addProperty("actual", (goneServer - startServer) / 20.0);
            }
            if (start >= 0) {
                // часы клиента: расписание пуска и повтор (окно плана) — на них; под нагрузкой часы клиента
                // отстают от серверных на период синхронизации, и серверное «actual» читается ранним (T13, 15.10:
                // удары ровно в eta по клиенту, сервер — на 1.1 с раньше) — отдаём оба, ворота берут любой из них
                if (startServer >= 0 && at >= 0) o.addProperty("skew", (startServer - start) / 20.0);
                if (startServer < 0 && at >= 0) o.addProperty("launched", (at - start) / 20.0);
                long moment = strike.pass() != null ? nearestTick : gone;
                if (moment >= 0) {
                    o.addProperty("actual_client", (moment - start) / 20.0);
                    if (!o.has("actual")) o.addProperty("actual", (moment - start) / 20.0);   // без сервера — как раньше
                }
            }
            if (strike.pass() != null && nearest < Double.MAX_VALUE) o.addProperty("pass_distance", nearest);
            return o;
        }
    }

    /**
     * Пуск удара {@code i} записи — через разносчик: пуски постановщика не встают в один тик (Артём, 14.10: установки не
     * стреляют в одну миллисекунду). Пуск ближе зазора к прошлому ждёт остаток ({@link War#later}); зазор — с разбросом
     * по зерну дубля и варианта: то же место пуска (ближе {@link #SAME_PLACE} блоков) и то же оружие — как очередь одной
     * трубы «Града» (9–13 тиков), разные места — как разные расчёты (20–60 тиков).
     */
    private void launch(Run run, int i) {
        Strike s = run.strikes.get(i);
        long at = launchAt(mc.level.getGameTime(), lastLaunchTick, lastLaunchAt, lastLaunchWeapon, s.weapon(), s.from(), random);
        lastLaunchTick = at;
        lastLaunchAt = s.from();
        lastLaunchWeapon = s.weapon();
        // ждать по времени игры: тики клиента под нагрузкой отстают от сервера, и пуск уходил позже записанного delay
        // (T18 ночью 15.10: 34 тика клиента — 46 тиков игры, 74 — 114)
        long now = mc.level.getGameTime();
        if (at <= now) fire(run, i, 0);
        else war.once(run.name + ": разносчик пусков " + i, () -> mc.level.getGameTime() >= at, 20 * 60,
                () -> fire(run, i, (mc.level.getGameTime() - now) / 20.0));
    }

    /** Тик пуска разносчика: сейчас или остаток зазора после прошлого пуска. Чистая — для юнит-теста. */
    static long launchAt(long now, long lastTick, @Nullable Vec3 lastAt, @Nullable WeaponType lastWeapon, WeaponType weapon,
                         Vec3 from, RandomSource random) {
        if (lastAt == null || lastWeapon == null) return now;
        boolean same = weapon == lastWeapon && Math.hypot(from.x - lastAt.x, from.z - lastAt.z) < SAME_PLACE;
        int gap = same ? SAME_GAP + random.nextInt(SAME_SPREAD) : OTHER_GAP + random.nextInt(OTHER_SPREAD);
        return Math.max(now, lastTick + gap);
    }

    /** Сам пуск командой оператора (игрок съёмки): с места пуска через точки пути. */
    private void fire(Run run, int i, double delay) {
        Strike s = run.strikes.get(i);
        Launched l = new Launched(i, s, run.schedule.flight()[i], s.afterPass(data.speeds()), delay);
        StringBuilder via = new StringBuilder();
        if (!s.via().isEmpty()) {
            via.append(" via");
            for (Vec3 v : s.via()) via.append(String.format(Locale.ROOT, " %.1f %.1f", v.x, v.z));
        }
        Film.cmd(String.format(Locale.ROOT, "incoming salvo %s 1 0 at %s from %.1f %.1f%s", s.weapon().getSerializedName(), War.xyz(s.to()),
                s.from().x, s.from().z, via));
        run.mine.add(l);
        launched.add(l);
    }

    /** Прошлый пуск разносчика: тик, место и оружие — по ним зазор следующему. */
    private long lastLaunchTick = Long.MIN_VALUE;
    @Nullable
    private Vec3 lastLaunchAt;
    @Nullable
    private WeaponType lastLaunchWeapon;
    /** Одно место пуска — ближе столько блоков; зазоры очереди одного места и разных мест, тиков (разброс — сверху). */
    private static final int SAME_PLACE = 40, SAME_GAP = 9, SAME_SPREAD = 5, OTHER_GAP = 20, OTHER_SPREAD = 41;

    private void tick(ClientTickEvent.Post e) {
        if (++ticks % 1200 == 0) heap();
        if (mc.level == null) return;
        for (Launched l : launched) l.tick();
        if (!enterSeen && !hints.isEmpty() && mc.screen == null
                && InputConstants.isKeyDown(mc.getWindow().getWindow(), InputConstants.KEY_RETURN)) {
            enterSeen = true;
        }
    }

    private long ticks;

    /**
     * Живой объём старшего поколения после последней видимой сборки — тот же, по которому бюджет руин
     * ({@link HeapWatch}), строкой раз в минуту: сторож конвейера снимает клиент, у которого он выше доли
     * {@code limits.heap_share} (утечка в других модах). Доля — выше бюджета руин: ядерный дубль легитимно
     * держит старшее поколение высоким, пока руины строятся. Замера ещё не было — занятое по всем пулам кучи
     * после сборки (с запасом).
     */
    private static void heap() {
        long live = HeapWatch.liveOldGeneration();
        if (live < 0) {
            live = 0;
            for (var p : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
                var u = p.getType() == java.lang.management.MemoryType.HEAP ? p.getCollectionUsage() : null;
                if (u != null) live += u.getUsed();
            }
        }
        long max = Runtime.getRuntime().maxMemory();
        Incoming.LOG.info("NEWISLE heap {}% ({} МБ из {})", Math.round(live * 100.0 / max), live >> 20, max >> 20);
    }

    /** Подсказки Артёму в живом дубле — поверх игры (в повтор не пишутся). */
    private void drawHints(RenderGuiEvent.Post e) {
        if (!live || hints.isEmpty() || hintFrom >= hintTo) return;
        var g = e.getGuiGraphics();
        var font = mc.font;
        int y = 12;
        for (int i = hintFrom; i < hintTo; i++) {
            String line = hints.get(i);
            int w = font.width(line);
            g.fill(8, y - 3, 16 + w, y + 11, 0xA0000000);
            g.drawString(font, line, 12, y, 0xFFFFFF);
            y += 16;
        }
    }

    // ================================================================ действия

    /** Действие сцены шагом сценария: ждёт, ставит, бьёт. */
    private void step(JsonObject a) {
        String what = a.get("do").getAsString();
        goTo(a);
        switch (what) {
            case "wait" -> film.waitTicks((int) Math.round(a.get("s").getAsDouble() * 20));
            case "settle" -> {
                war.settle();
                film.run(() -> film.withServer(s -> {
                    for (String n : creative) film.cast().command("gamemode creative " + n);
                    creative.clear();
                }));
            }
            case "flights.wait" -> film.waitUntil(() -> ClientFlights.all().isEmpty(), (int) Math.round(a.get("s").getAsDouble() * 20));
            case "crowd" -> crowd(a, false);
            case "gunners" -> crowd(a, true);
            case "fight" -> fight(a);
            case "launchers" -> launchers(a);
            case "silo.build" -> siloBuild(a);
            case "commander" -> commander(a);
            case "player" -> {
                boolean spectator = a.has("mode") && a.get("mode").getAsString().equals("spectator");
                String item = a.has("item") ? a.get("item").getAsString() : null;
                if (spectator) {
                    film.run(() -> {
                        film.actor = true;
                        Vec3 p = at(a.get("at"), 0), l = at(a.get("look"), 0);
                        film.withPlayer((level, sp) -> sp.setGameMode(GameType.SPECTATOR));
                        war.place(p, l);
                    });
                    film.waitTicks(30);
                } else {
                    war.actor(() -> feet(a.get("at")), () -> at(a.get("look"), 0), item);
                }
            }
            case "hidden" -> hide(() -> at(a.get("at"), 0), () -> at(a.get("look"), 0));
            case "boat" -> boatFor(a.get("at"), () -> standin ? "Artem" : mc.player.getGameProfile().getName());
            case "airship" -> film.then(inline(() -> {
                // сцена дирижабля ставит свои шаги от места: место — по миру, шаги — сразу за этим
                Vec3 at = feet(a.get("at"));
                war.hiddenAt(at.add(0, 30, -40), at);
                AirshipScene.stage(film, at, a.has("yaw") ? a.get("yaw").getAsFloat() : 0, a.get("ground").getAsString());
            }));
            case "salvo" -> film.run(now(a, null));
            case "grid.node" -> srv(level -> war.addGridNode(level, at(a.get("at"), 0), a.get("r").getAsInt()));
            case "station.wire" -> {
                Vec3[] box = data.box(a.get("box"));
                srv(level -> War.wireStation(level, BlockPos.containing(box[0]), BlockPos.containing(box[1])));
            }
            default -> film.run(now(a, null));
        }
    }

    /**
     * Невидимка — к месту действия, если оно далеко от прошлого: блоки ставятся только в загруженных чанках, мир вокруг
     * игрока сервер шлёт и держит. Вживую (Артём в кадре) игрок стоит, где стоит.
     */
    private void goTo(JsonObject a) {
        if (live) return;
        JsonElement p = a.has("at") ? a.get("at") : a.has("around") ? a.get("around") : null;
        if (p == null || a.get("do").getAsString().equals("player")) return;
        if (a.get("do").getAsString().startsWith("crowd.") || a.get("do").getAsString().startsWith("sam.")) return;
        film.run(() -> {
            // ряд мест — по первому из них; ряд бывает и именем места (farm_missiles у T06): line сам разбирает оба
            Vec3 q = data.line(p, heights).get(0);
            if (lastHidden != null && lastHidden.distanceTo(q) < 96) return;
            lastHidden = q;
            war.place(q.add(0, 20, 0), q);
        });
        film.waitTicks(40);
    }

    /** Действие сразу (реплика плана, {@code ready}, {@code end} или простой шаг сцены): в потоке клиента. */
    private Runnable now(JsonObject a, @Nullable Run run) {
        String what = a.get("do").getAsString();
        return switch (what) {
            case "campfire" -> () -> {
                Vec3 p = feet(a.get("at"));
                film.withServer(s -> film.cast().command("setblock " + block(p) + " minecraft:campfire[lit=true]"));
            };
            case "lamps" -> () -> {
                Vec3 c = feet(a.get("around"));
                double r = a.get("r").getAsDouble();
                int n = a.get("n").getAsInt();
                List<Vec3> spots = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double ang = Math.PI * 2 * i / n + random.nextDouble() * 0.4;
                    double x = c.x + Math.cos(ang) * r, z = c.z + Math.sin(ang) * r;
                    spots.add(new Vec3(x, heights.top(x, z), z));
                }
                film.withServer(s -> spots.forEach(p -> film.cast().command("setblock " + block(p) + " minecraft:lantern")));
            };
            case "crowd.watch" -> () -> {
                Vec3 p = at(a.get("at"), 0);
                double j = a.has("jitter") ? a.get("jitter").getAsDouble() : 10;
                film.withServer(s -> group(a).watch(p, j));
            };
            case "crowd.cower" -> () -> {
                Vec3 p = at(a.get("from"), 0);
                film.withServer(s -> group(a).cower(p));
            };
            case "crowd.flee" -> () -> {
                Vec3 to = feet(a.get("to"));
                JsonArray st = a.has("stagger") ? a.getAsJsonArray("stagger") : null;
                int min = st == null ? 0 : (int) Math.round(st.get(0).getAsDouble() * 20), max = st == null ? 8 : (int) Math.round(st.get(1).getAsDouble() * 20);
                film.withServer(s -> group(a).flee(to, min, max));
            };
            case "crowd.follow" -> () -> {
                Class<? extends Entity> type = entity(WeaponType.parse(a.get("weapon").getAsString()));
                JsonArray d = a.getAsJsonArray("delay");
                int len = run == null ? 400 : (int) Math.round(run.shot.get("seconds").getAsDouble() * 20) + 100;
                for (Crowd.Person p : group(a).people()) {
                    int delay = (int) Math.round((d.get(0).getAsDouble() + random.nextDouble() * (d.get(1).getAsDouble() - d.get(0).getAsDouble())) * 20);
                    war.later(delay, () -> film.withServer(s -> war.trackLook(p.name, type, len)));
                }
            };
            case "crowd.remove" -> () -> film.withServer(s -> {
                Crowd c = crowds.remove(a.get("group").getAsString());
                Fight f = squads.remove(a.get("group").getAsString());
                if (c != null) {
                    c.people().forEach(p -> bots.remove(p.name));
                    c.removeAll();
                }
                if (f != null) f.stop();
            });
            case "launchers.fire" -> () -> {
                List<BlockPos> r = racks.getOrDefault(a.get("rack").getAsString(), List.of());
                int delay = a.has("delay") ? (int) Math.round(a.get("delay").getAsDouble() * 20) : 0;
                war.later(delay, () -> war.fireAll(r));
            };
            case "launcher.redstone" -> () -> {
                List<BlockPos> r = racks.getOrDefault(a.get("rack").getAsString(), List.of());
                int i = a.get("index").getAsInt();
                if (i >= r.size()) {
                    warn("launcher.redstone: нет пусковой " + i);
                    return;
                }
                BlockPos signal = r.get(i).north();
                film.withServer(s -> film.cast().command("setblock " + block(signal) + " minecraft:redstone_block"));
                war.later(20, () -> film.withServer(s -> film.cast().command("setblock " + block(signal) + " minecraft:air")));
            };
            case "sam" -> () -> {
                Vec3 p = feet(a.get("at"));
                int load = a.get("load").getAsInt();
                film.withServer(s -> {
                    ServerLevel level = s.overworld();
                    BlockPos b = BlockPos.containing(p);
                    level.setBlockAndUpdate(b, ModBlocks.SAM.get().defaultBlockState());
                    if (level.getBlockEntity(b) instanceof SamBlockEntity sam) sam.load(new ItemStack(ModItems.INTERCEPTOR.get(), load));
                });
            };
            case "sam.clear" -> () -> {
                Vec3 c = at(a.get("at"), 0);
                film.withServer(s -> War.removeSams(s.overworld(), c, a.get("r").getAsInt()));
            };
            case "gunners.fire" -> () -> {
                Fight f = squads.get(a.get("group").getAsString());
                if (f == null) return;
                double range = a.has("range") ? a.get("range").getAsDouble() : 160;
                WeaponType w = WeaponType.parse(a.get("weapon").getAsString());
                film.withServer(s -> {
                    f.range = range;
                    f.sky = eye -> skyTarget(s.overworld(), eye, entity(w), range);
                    f.start();
                });
            };
            case "gunners.stop" -> () -> {
                Fight f = squads.get(a.get("group").getAsString());
                if (f != null) film.withServer(s -> {
                    f.sky = null;
                    f.stop();
                });
            };
            case "fight.arm" -> () -> film.withServer(s -> {
                Fight f = squads.get("street");
                if (f != null) for (Fight.Soldier x : f.soldiers()) film.armSoldier(x.name, x.side);
            });
            case "fight.start" -> () -> film.withServer(s -> squads.getOrDefault("street", null).start());
            case "fight.stop" -> () -> film.withServer(s -> {
                Fight f = squads.get("street");
                if (f != null) f.stop();
            });
            case "fight.remove" -> () -> film.withServer(s -> {
                Fight f = squads.remove("street");
                if (f != null) for (Fight.Soldier x : f.soldiers()) {
                    film.cast().remove(x.name);
                    bots.remove(x.name);
                }
            });
            case "silo.open" -> () -> lid(false);
            case "silo.close" -> () -> lid(true);
            case "nuke.order" -> () -> nukeOrder(a);
            case "nuke.clear" -> () -> film.withServer(s -> NuclearStrikes.clear(s.overworld()));
            case "nuke.key" -> () -> PacketDistributor.sendToServer(new C2S.NuclearKey());
            case "salvo" -> () -> {
                Vec3 to = at(a.get("to"), 0), from = at(a.get("from"), 0);
                int count = a.has("count") ? a.get("count").getAsInt() : 1, spread = a.has("spread") ? a.get("spread").getAsInt() : 0;
                war.salvo(a.get("weapon").getAsString(), count, spread, to, from);
            };
            case "scope" -> () -> mc.options.keyUse.setDown(a.get("on").getAsBoolean());
            case "map.open" -> () -> {
                Vec3 t = at(a.get("target"), 0);
                MapTarget.set(mc.level, new MapTarget.Place(t.x, t.z));
                MapTarget.clearRoute();
                MapScreen.reset();
                mc.setScreen(new MapScreen(new RemoteScreen()));
                Cursor.show(new double[]{mc.getWindow().getGuiScaledWidth() * 0.7, mc.getWindow().getGuiScaledHeight() * 0.75});
            };
            case "map.route" -> () -> mapRoute(a.get("route").getAsString());
            case "map.close" -> () -> {
                Cursor.hide();
                mc.setScreen(null);
                MapTarget.clearRoute();
            };
            case "retarget" -> () -> {
                WeaponType w = WeaponType.parse(a.get("weapon").getAsString());
                Vec3 to = at(a.get("to"), 0);
                for (ClientFlights.Tracked f : ClientFlights.all()) {
                    if (f.weapon() != w) continue;
                    PacketDistributor.sendToServer(new C2S.Retarget(f.id, new C2S.AimHint(C2S.AimHint.POINT, to, 0, Vec3.ZERO)));
                    return;
                }
                warn("retarget: нет полёта " + w.getSerializedName());
            };
            default -> throw new IllegalArgumentException("нет действия " + what);
        };
    }

    /** Путь залпа на открытой карте пульта: курсор идёт по точкам, каждая встаёт в маршрут. */
    private void mapRoute(String name) {
        JsonObject r = data.scenario.getAsJsonObject("routes").getAsJsonObject(name);
        MapTarget.clearRoute();
        JsonArray via = r.getAsJsonArray("via");
        int gap = 6;
        for (int i = 0; i < via.size(); i++) {
            JsonArray v = via.get(i).getAsJsonArray();
            Vec3 p = new Vec3(v.get(0).getAsDouble(), 0, v.get(1).getAsDouble());
            war.later(i * gap, () -> Cursor.moveTo(war.mapPoint(p), gap - 1));
            war.later(i * gap + gap - 1, () -> {
                if (mc.level != null) MapTarget.add(mc.level, new MapTarget.Place(p.x, p.z));
            });
        }
    }

    private Crowd group(JsonObject a) {
        Crowd c = crowds.get(a.get("group").getAsString());
        if (c == null) throw new IllegalStateException("нет группы " + a.get("group"));
        return c;
    }

    /**
     * Толпа (или расчёт с оружием — {@code gunners}): {@code n} человек вокруг места на открытой земле (не на крышах), имена
     * — из {@code names}, потом {@code prefix} с номером; бродят в {@code roam} блоков.
     */
    private void crowd(JsonObject a, boolean armed) {
        String group = a.get("group").getAsString();
        int n = a.get("n").getAsInt();
        double spread = a.has("spread") ? a.get("spread").getAsDouble() : 6, roam = a.has("roam") ? a.get("roam").getAsDouble() : 3;
        String prefix = a.has("prefix") ? a.get("prefix").getAsString() : group;
        List<String> names = new ArrayList<>();
        if (a.has("names")) for (JsonElement e : a.getAsJsonArray("names")) names.add(e.getAsString());
        boolean god = a.has("creative") && a.get("creative").getAsBoolean();
        srv(level -> {
            Vec3 c = feet(a.get("at"));
            Crowd crowd = crowds.computeIfAbsent(group, k -> new Crowd(film.cast(), film.mc.getSingleplayerServer()));
            Fight squad = armed ? squads.computeIfAbsent(group, k -> new Fight(film.cast(), film.mc.getSingleplayerServer())) : null;
            for (int i = 0, tries = 0; i < n && tries < 40 * n; tries++) {
                double ang = random.nextDouble() * Math.PI * 2, d = Math.sqrt(random.nextDouble()) * spread;
                double x = Math.floor(c.x + Math.cos(ang) * d) + 0.5, z = Math.floor(c.z + Math.sin(ang) * d) + 0.5;
                int y = War.groundY(level, x, z);
                if (Math.abs(y - c.y) > 3 || !level.getFluidState(BlockPos.containing(x, y - 1, z)).isEmpty()) continue;
                String name = i < names.size() ? names.get(i) : prefix + i;
                i++;
                Vec3 p = new Vec3(x, y, z);
                bots.add(name);
                if (squad != null) {
                    spawn(name, p, random.nextFloat() * 360);
                    squad.add(name, 0, p, new Vec3(0, 0, 1));
                } else {
                    crowd.add(name, p, roam);
                }
                if (god) creative.add(name);
            }
        });
        if (armed) {
            war.settle();
            srv(level -> {
                for (Fight.Soldier s : squads.get(group).soldiers()) film.armSoldier(s.name, 0);
            });
        }
    }

    /** Уличный бой: две линии по {@code n} бойцов друг против друга, лицом к середине другой. */
    private void fight(JsonObject a) {
        srv(level -> {
            List<Vec3> west = data.line(a.get("west"), heights), east = data.line(a.get("east"), heights);
            Fight f = squads.computeIfAbsent("street", k -> new Fight(film.cast(), film.mc.getSingleplayerServer()));
            Vec3 cw = centre(west), ce = centre(east);
            for (int side = 0; side < 2; side++) {
                List<Vec3> line = side == 0 ? west : east;
                Vec3 facing = (side == 0 ? ce.subtract(cw) : cw.subtract(ce)).multiply(1, 0, 1).normalize();
                for (int i = 0; i < line.size(); i++) {
                    String name = (side == 0 ? "West" : "East") + i;
                    Vec3 p = new Vec3(Math.floor(line.get(i).x) + 0.5, line.get(i).y, Math.floor(line.get(i).z) + 0.5);
                    spawn(name, p, Film.yawTo(Vec3.ZERO, facing));
                    f.add(name, side, p, facing);
                }
            }
        });
    }

    private static Vec3 centre(List<Vec3> pts) {
        Vec3 s = Vec3.ZERO;
        for (Vec3 p : pts) s = s.add(p);
        return s.scale(1.0 / Math.max(1, pts.size()));
    }

    /** Пусковые в ряд: каждая с задачей по пути залпа ({@code via}) и запасом; ряд — по имени для {@code launchers.fire}. */
    private void launchers(JsonObject a) {
        String rack = a.get("rack").getAsString();
        WeaponType w = WeaponType.parse(a.get("weapon").getAsString());
        rackWeapon.put(rack, w);
        film.run(() -> {
            List<Vec3> line = data.line(a.get("at"), heights);
            Vec3 target = at(a.get("to"), 0);
            StringBuilder via = new StringBuilder();
            if (a.has("route")) {
                JsonArray v = data.scenario.getAsJsonObject("routes").getAsJsonObject(a.get("route").getAsString()).getAsJsonArray("via");
                via.append(" via");
                for (JsonElement p : v) via.append(String.format(Locale.ROOT, " %.1f %.1f", p.getAsJsonArray().get(0).getAsDouble(),
                        p.getAsJsonArray().get(1).getAsDouble()));
            }
            String mission = String.format(Locale.ROOT, "%s %d %d %s%s", w.getSerializedName(), a.get("count").getAsInt(), a.get("spread").getAsInt(),
                    War.xyz(target), via);
            List<BlockPos> list = new ArrayList<>();
            for (Vec3 p : line) list.add(BlockPos.containing(p));
            racks.put(rack, list);
            int load = a.get("load").getAsInt();
            film.withServer(s -> {
                for (BlockPos p : list) {
                    String at = p.getX() + " " + p.getY() + " " + p.getZ();
                    film.cast().command("setblock " + at + " incoming:fixed_launcher");
                    film.cast().command("incoming launcher " + at + " mission " + mission);
                    film.cast().command("incoming launcher " + at + " load " + load);
                }
            });
        });
        film.waitTicks(20);
    }

    // ================================================================ шахта МБР

    /** Шахта: внутри {@code x0..x1, z0..z1} от дна до крышки; {@code ground} — первый воздух над землёй у шахты. */
    private record Silo(int x0, int z0, int x1, int z1, int floor, int lid, double ground, Vec3 centre) {}

    private void siloBuild(JsonObject a) {
        int depth = a.get("depth").getAsInt(), half = a.get("size").getAsInt() / 2;
        srv(level -> {
            Vec3 c = at(a.get("at"), 0);
            int cx = Mth.floor(c.x), cz = Mth.floor(c.z), g = War.groundY(level, cx + 0.5, cz + 0.5);
            Silo s = new Silo(cx - half, cz - half, cx + half, cz + half, g - depth, g - 1, g, new Vec3(cx + 0.5, g, cz + 0.5));
            silo = s;
            var cast = film.cast();
            cast.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:gray_concrete", s.x0 - 1, s.floor - 1, s.z0 - 1, s.x1 + 1, s.lid, s.z1 + 1));
            cast.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.x0, s.floor, s.z0, s.x1, s.lid - 1, s.z1));
            lidBlocks(s, true);
            Incoming.LOG.info("SCENARIO film staging: шахта МБР {}..{} {}..{}, дно {}, крышка {}", s.x0, s.x1, s.z0, s.z1, s.floor, s.lid);
        });
    }

    private void lid(boolean closed) {
        if (silo == null) {
            warn("шахта не построена");
            return;
        }
        Silo s = silo;
        film.withServer(srv -> lidBlocks(s, closed));
    }

    private void lidBlocks(Silo s, boolean closed) {
        film.cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", s.x0, s.lid, s.z0, s.x1, s.lid, s.z1,
                closed ? "minecraft:polished_deepslate" : "minecraft:air"));
    }

    /**
     * Командир расчёта у шахты: в 30 блоках от неё лицом от шахты (МБР встаёт в 30 блоках позади отдавшего приказ —
     * {@code NuclearStrikes.launchFrom}: со дна открытой шахты), с пультом ядерного удара; игрок — ещё на
     * {@code player_back} дальше, лицом к нему (второй ключ — рядом с ним).
     */
    private void commander(JsonObject a) {
        String name = a.get("name").getAsString();
        double back = a.has("player_back") ? a.get("player_back").getAsDouble() : 10;
        Vec3[] post = new Vec3[2];
        srv(level -> {
            if (silo == null) throw new IllegalStateException("commander: шахта не построена");
            Vec3 toward = at(a.get("toward"), 0);
            Vec3 dir = toward.subtract(silo.centre).multiply(1, 0, 1).normalize();
            Vec3 cm = silo.centre.add(dir.scale(30));
            cm = new Vec3(cm.x, War.groundY(level, cm.x, cm.z), cm.z);
            Vec3 me = cm.add(dir.scale(back));
            post[0] = cm;
            post[1] = new Vec3(me.x, War.groundY(level, me.x, me.z), me.z);
            spawn(name, cm, Film.yawTo(silo.centre, cm));
            film.cast().steady(name);
        });
        war.settle();
        srv(level -> {
            film.cast().command("item replace entity " + name
                    + " hotbar.0 with incoming:strike_designator[incoming:loadout={weapon:\"nuke\",count:1,spread:0}]");
            film.cast().command("give " + name + " incoming:icbm 1");
        });
        war.actor(() -> post[1], () -> post[0].add(0, 1.4, 0), "incoming:strike_designator");
        film.run(() -> {
            Keys.SECOND_KEY.setKey(InputConstants.getKey("key.keyboard.k"));
            KeyMapping.resetMapping();
        });
        film.run(() -> film.withServer(s -> film.cast().act(name, War.one(Cast.lookAt(post[1].add(0, 1.6, 0))), true)));
    }

    /**
     * Приказ на ядерный удар: бот — строкой чата, как игрок (не оператор: мод ждёт второй ключ рядом); игрок с
     * {@code pad} — пуск от места пуска (место «запустившего» — в 30 блоках от него по курсу 0, МБР встаёт позади
     * него), без него — от шахты, без шахты — пульт. Место пуска без игрока рядом — на тикетах до конца дубля
     * ({@link #holdPad}): чанк дальше зоны тикания, и не тикающая МБР замирает на старте (у незагруженного чанка
     * высота стола минимальная: n32 15.10 стартовала с y −55 и не поднималась).
     */
    private void nukeOrder(JsonObject a) {
        Vec3 gz = at(a.get("at_place"), 0);
        double kt = a.has("kt") ? a.get("kt").getAsDouble() : 15;
        String by = a.get("by").getAsString();
        if (!by.equals("player")) {
            film.withServer(s -> film.cast().act(by, War.one(Cast.step("chat", String.format(Locale.ROOT, "/incoming nuke at %s %.0f air",
                    War.xyz(gz), kt))), true));
            return;
        }
        if (!a.has("pad") && silo == null) {
            Film.cmd(String.format(Locale.ROOT, "incoming nuke at %s %.0f air", War.xyz(gz), kt));
            return;
        }
        Vec3 pad = a.has("pad") ? at(a.get("pad"), 0) : silo.centre;
        Vec3 launcher = pad.add(Local.horizontal(0).scale(30));
        UUID owner = mc.player.getUUID();
        holdPad(pad);
        film.waitUntil(() -> padReady(pad), 20 * 10);
        film.run(() -> {
            if (!padReady(pad)) stop("место пуска МБР не загрузилось: " + block(pad));
        });
        film.withServer(s -> NuclearStrikes.launchFrom(s.overworld(), gz, kt, true, launcher, 0, owner));
    }

    /** Чанки мест пуска — снятые в {@link #finish()} тикеты, чтобы чанки тикали до конца дубля. */
    private final List<ChunkPos> heldPads = new ArrayList<>();
    /** Владелец тикетов мест пуска ({@link ChunkTickets} — свой, чтобы не снимать чужие). */
    private static final UUID PAD_TICKETS = UUID.fromString("00000000-0000-4000-8000-5c3a72e00001");

    /** Место пуска и восемь соседей — на тикетах тикания сущностей ({@code ChunkTickets} — как снаряды мода). */
    private void holdPad(Vec3 pad) {
        int cx = Mth.floor(pad.x) >> 4, cz = Mth.floor(pad.z) >> 4;
        film.withServer(s -> {
            ServerLevel level = s.overworld();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                ChunkPos p = new ChunkPos(cx + dx, cz + dz);
                if (heldPads.contains(p)) continue;
                ChunkTickets.hold(level, PAD_TICKETS, p.toLong(), true);
                heldPads.add(p);
            }
        });
    }

    /** Чанк места пуска загружен целиком ({@code Terrain.ready}): у {@code Terrain.height} незагруженного — минимум мира. */
    private boolean padReady(Vec3 pad) {
        MinecraftServer s = mc.getSingleplayerServer();
        int cx = Mth.floor(pad.x) >> 4, cz = Mth.floor(pad.z) >> 4;
        return s.submit(() -> Terrain.ready(s.overworld(), cx, cz)).join();
    }

    // ================================================================ пробы

    /** Блоки рамки до удара и после ({@code glass} — стёкла, {@code solid} — всё не воздух): стекло выбито, дом стоит. */
    private final class Probe {
        final String name, count;
        final Vec3 centre;
        final double r, h;
        volatile int before = -1, after = -1;

        Probe(String name, String count, Vec3 centre, double r, double h) {
            this.name = name;
            this.count = count;
            this.centre = centre;
            this.r = r;
            this.h = h;
        }

        int measure(ServerLevel level) {
            int n = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = Mth.floor(centre.x - r); x <= Mth.floor(centre.x + r); x++) {
                for (int z = Mth.floor(centre.z - r); z <= Mth.floor(centre.z + r); z++) {
                    for (int y = Mth.floor(centre.y - h); y <= Mth.floor(centre.y + h); y++) {
                        BlockState s = level.getBlockState(p.set(x, y, z));
                        // «solid» — стоит ли дом: без стекла, иначе выбитое волной стекло считалось бы рухнувшим домом
                        boolean glass = s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES);
                        if (count.equals("glass") ? glass : !s.isAir() && !glass) n++;
                    }
                }
            }
            return n;
        }

        JsonObject report() {
            JsonObject o = new JsonObject();
            o.addProperty("count", count);
            o.addProperty("before", before);
            o.addProperty("after", after);
            return o;
        }
    }

    private Probe probe(JsonObject p) {
        Probe pr = new Probe(p.get("name").getAsString(), p.get("count").getAsString(), at(p.get("around"), 0), p.get("r").getAsDouble(),
                p.get("h").getAsDouble());
        film.withServer(s -> pr.before = pr.measure(s.overworld()));
        return pr;
    }

    // ================================================================ поиски P1

    /**
     * Проба поиска ({@code INCOMING_NEWISLE_TRIAL}): {@code terrace:<блоков>}, {@code grad:<снарядов>}, {@code spots},
     * {@code boat}, {@code pointblank}. Каждая — на своей копии мастера; итог — в файл итога ({@code trial}), выбор и
     * запись в сцены — конвейер.
     */
    private void trial() {
        String[] t = trial.split(":", 2);
        JsonObject search = data.takes.getAsJsonObject("searches").getAsJsonObject(t[0]);
        JsonObject out = new JsonObject();
        result.add("trial", out);
        out.addProperty("search", t[0]);
        film.run(() -> {
            Film.cmd("time set " + data.clock("night"));
            Film.cmd("weather clear");
        });
        switch (t[0]) {
            case "terrace" -> {
                double d = Double.parseDouble(t[1]);
                out.addProperty("distance", d);
                String cam = "camera:" + search.get("from_camera").getAsString();
                hide(() -> at(new com.google.gson.JsonPrimitive(cam), 0), () -> at(search.get("toward"), 0));
                film.waitTicks(40);
                film.run(() -> {
                    Vec3 eye = at(new com.google.gson.JsonPrimitive(cam), 0), aim = at(search.get("toward"), 0);
                    Vec3 dir = aim.subtract(eye).multiply(1, 0, 1).normalize();
                    Vec3 hit = new Vec3(eye.x + dir.x * d, aim.y, eye.z + dir.z * d);
                    out.add("hit", vec(hit));
                    for (JsonElement p : take(searchTake("T03")).getAsJsonArray("shots").get(0).getAsJsonObject().getAsJsonArray("probes")) {
                        probes.add(probe(p.getAsJsonObject()));
                    }
                    Vec3 from = hit.add(dir.scale(1400));
                    Film.cmd(String.format(Locale.ROOT, "incoming salvo missile 1 0 at %s from %.1f %.1f", War.xyz(hit), from.x, from.z));
                });
                film.waitTicks(40);
            }
            case "grad" -> {
                int n = Integer.parseInt(t[1]);
                out.addProperty("size", n);
                JsonObject plan = data.plan(search.get("plan").getAsString());
                hide(() -> at(plan.get("target"), 0), () -> at(plan.get("target"), 0));
                film.waitTicks(40);
                double[] peak = {0};
                film.run(() -> {
                    Vec3 c = at(plan.get("target"), 0), from = at(new com.google.gson.JsonPrimitive("grad_battery"), 0);
                    Film.cmd(String.format(Locale.ROOT, "incoming salvo rocket %d 40 at %s from %.1f %.1f", n, War.xyz(c), from.x, from.z));
                });
                // пик — за весь залп: от первого снаряда у клиента до последнего удара и ещё 5 с работы попаданий
                boolean[] seen = {false};
                int[] after = {0};
                film.waitUntil(() -> {
                    peak[0] = Math.max(peak[0], film.peakTickMs(20));
                    if (!ClientFlights.all().isEmpty()) seen[0] = true;
                    else if (seen[0]) after[0]++;
                    return after[0] > 100;
                }, 20 * 180);
                film.run(() -> out.addProperty("mspt_peak", peak[0]));
            }
            case "spots" -> srv(level -> {
                // место, от которого ищется другое (кафе — от входа в метро), — уже найденное, поэтому — по зависимостям
                JsonObject places = new JsonObject();
                Map<String, Vec3> found = new HashMap<>();
                List<JsonObject> left = new ArrayList<>();
                for (JsonElement e : search.getAsJsonArray("items")) left.add(e.getAsJsonObject());
                for (int pass = 0; pass < 4 && !left.isEmpty(); pass++) {
                    for (var i = left.iterator(); i.hasNext(); ) {
                        JsonObject it = i.next();
                        if (pass < 3 && waitsFor(it, left)) continue;
                        i.remove();
                        Vec3 p = spot(level, it, found);
                        if (p == null) continue;
                        found.put(it.get("place").getAsString(), p);
                        places.add(it.get("place").getAsString(), vec(p));
                    }
                }
                out.add("places", places);
            });
            case "boat" -> boatTrial(search, out);
            case "pointblank" -> pointBlankTrial(search, out);
            default -> throw new IllegalArgumentException("нет поиска " + t[0]);
        }
    }

    private String searchTake(String id) {
        return id;
    }

    private JsonObject take(String id) {
        JsonObject t = data.take(id);
        if (t == null) throw new IllegalStateException("нет дубля " + id);
        return t;
    }

    /** Место ищется от другого места этого же поиска, которое ещё не найдено. */
    private static boolean waitsFor(JsonObject it, List<JsonObject> left) {
        for (String k : new String[]{"see", "see_from", "away_from"}) {
            if (!it.has(k) || !it.get(k).isJsonPrimitive()) continue;
            String name = it.get(k).getAsString();
            for (JsonObject o : left) if (o != it && o.get("place").getAsString().equals(name)) return true;
        }
        return false;
    }

    /** Место по лучам по блокам: земля, фасад, вода, лестница — у {@code near} в {@code r} блоках (поток сервера). */
    @Nullable
    private Vec3 spot(ServerLevel level, JsonObject it, Map<String, Vec3> found) {
        JsonArray near = it.getAsJsonArray("near");
        double nx = near.get(0).getAsDouble(), nz = near.get(1).getAsDouble(), r = it.get("r").getAsDouble();
        String kind = it.get("kind").getAsString();
        Vec3 see = it.has("see") ? pointOn(level, it.get("see"), found) : null;
        Vec3 from = it.has("see_from") ? pointOn(level, it.get("see_from"), found).add(0, 1.6, 0) : null;
        Vec3 away = it.has("away_from") ? pointOn(level, it.get("away_from"), found) : null;
        double dist = it.has("dist") ? it.get("dist").getAsDouble() : 0;
        if (kind.equals("facade") && from != null) {
            Vec3 best = null;
            double bd = Double.MAX_VALUE;
            for (double dx = -r; dx <= r; dx += 2) {
                for (double dz = -r; dz <= r; dz += 2) {
                    for (double up = 2; up <= 30; up += 4) {
                        Vec3 aim = new Vec3(nx + dx, War.groundY(level, nx + dx, nz + dz) + up, nz + dz);
                        BlockHitResult hit = level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
                        if (hit.getType() != HitResult.Type.BLOCK) continue;
                        Vec3 h = hit.getLocation();
                        if (Math.hypot(h.x - nx, h.z - nz) > r || h.y < War.groundY(level, h.x, h.z) - 1 + 3) continue;
                        double d = Math.hypot(h.x - nx, h.z - nz);
                        if (d < bd) {
                            bd = d;
                            best = h;
                        }
                    }
                }
            }
            return best;
        }
        Vec3 best = null;
        double bd = Double.MAX_VALUE;
        for (double dx = -r; dx <= r; dx += 1) {
            for (double dz = -r; dz <= r; dz += 1) {
                double x = Math.floor(nx + dx) + 0.5, z = Math.floor(nz + dz) + 0.5;
                if (Math.hypot(x - nx, z - nz) > r) continue;
                if (away != null && Math.abs(Math.hypot(x - away.x, z - away.z) - dist) > 15) continue;
                int y = War.groundY(level, x, z);
                BlockPos below = BlockPos.containing(x, y - 1, z);
                Vec3 p;
                switch (kind) {
                    case "water" -> {
                        if (!level.getFluidState(below).is(Fluids.WATER) || !level.getFluidState(below.below()).is(Fluids.WATER)) continue;
                        p = new Vec3(x, y, z);
                    }
                    case "stairs" -> {
                        Vec3 st = null;
                        for (int k = 0; k < 24 && st == null; k++) {
                            BlockPos q = BlockPos.containing(x, y - 1 - k, z);
                            if (level.getBlockState(q).getBlock() instanceof StairBlock) st = new Vec3(x, q.getY() + 1, z);
                        }
                        if (st == null) continue;
                        p = st;
                    }
                    default -> {
                        BlockState s = level.getBlockState(below);
                        if (!level.getFluidState(below).isEmpty() || s.is(BlockTags.LEAVES) || !flat(level, x, z, y)) continue;
                        p = new Vec3(x, y, z);
                    }
                }
                if (see != null && !clear(level, p.add(0, 1.6, 0), see)) continue;
                double d = Math.hypot(x - nx, z - nz);
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
        }
        return best;
    }

    private Vec3 pointOn(ServerLevel level, JsonElement spec, Map<String, Vec3> found) {
        if (spec.isJsonPrimitive() && found.containsKey(spec.getAsString())) return found.get(spec.getAsString());
        return data.point(spec, 0, new NewisleData.Heights() {
            @Override
            public double top(double x, double z) {
                return War.groundY(level, x, z);
            }

            @Override
            public double ground(double x, double z) {
                return natural(level, x, z);
            }
        });
    }

    private static boolean flat(ServerLevel level, double x, double z, int y) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (War.groundY(level, x + dx, z + dz) != y) return false;
        return true;
    }

    /** Луч по блокам свободен до {@code b} (или упирается в блок у самой точки — дверь, вход). */
    private static boolean clear(ServerLevel level, Vec3 a, Vec3 b) {
        BlockHitResult hit = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(b) < 3;
    }

    /**
     * Лодка из блоков на воде у {@code fisher}: корпус 3×7 из досок, сборка Sable; через 10 с — крен и дифферент по местам
     * углов корпуса в мире. Не собралась или кренится больше {@code max_tilt} — монтаж берёт обычную лодку.
     */
    private void boatTrial(JsonObject search, JsonObject out) {
        Vec3[] corners = new Vec3[4];
        hide(() -> feet(search.get("at")), () -> feet(search.get("at")));
        film.waitTicks(40);
        srv(level -> {
            Vec3 w = feet(search.get("at"));
            int x = Mth.floor(w.x), y = (int) w.y, z = Mth.floor(w.z);
            film.cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:spruce_planks", x - 1, y, z - 3, x + 1, y, z + 3));
            film.cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:spruce_slab", x - 1, y + 1, z - 3, x - 1, y + 1, z + 3));
            film.cast().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:spruce_slab", x + 1, y + 1, z - 3, x + 1, y + 1, z + 3));
            int before = SubLevels.all(level).size();
            film.cast().command(String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", x - 1, y, z - 3, x + 1, y + 1, z + 3));
            out.addProperty("assembled", SubLevels.all(level).size() > before);
            corners[0] = new Vec3(x - 1, y, z - 3);
            corners[1] = new Vec3(x + 1, y, z - 3);
            corners[2] = new Vec3(x - 1, y, z + 3);
            corners[3] = new Vec3(x + 1, y, z + 3);
        });
        film.waitTicks(20);
        Vec3[] plot = new Vec3[4];
        srv(level -> {
            var subs = SubLevels.near(level, corners[0], 16);
            if (subs.isEmpty()) return;
            for (int i = 0; i < 4; i++) plot[i] = SubLevels.toPlot(subs.get(0), corners[i]);
        });
        film.waitTicks(200);
        srv(level -> {
            if (plot[0] == null) {
                out.addProperty("ok", false);
                return;
            }
            Vec3[] wpos = new Vec3[4];
            for (int i = 0; i < 4; i++) wpos[i] = SubLevels.toWorld(level, plot[i]);
            double roll = Math.toDegrees(Math.atan2(Math.abs(wpos[1].y - wpos[0].y), 2)), pitch = Math.toDegrees(Math.atan2(Math.abs(wpos[2].y - wpos[0].y), 6));
            out.addProperty("roll", roll);
            out.addProperty("pitch", pitch);
            out.addProperty("afloat", wpos[0].y > corners[0].y - 2);
            out.addProperty("ok", roll <= search.get("max_tilt").getAsDouble() && pitch <= search.get("max_tilt").getAsDouble() && wpos[0].y > corners[0].y - 2);
        });
    }

    /**
     * Пуля Point Blank по шахеду: боец с автоматом у {@code mg_group}, шахед проходит над ним в 40 блоках; сбит — если
     * пропал дальше 100 блоков от своей цели (не долетел).
     */
    private void pointBlankTrial(JsonObject search, JsonObject out) {
        JsonObject one = new JsonObject();
        one.addProperty("do", "gunners");
        one.addProperty("group", "pb");
        one.addProperty("at", "mg_group");
        one.addProperty("n", 1);
        one.addProperty("spread", 1);
        one.addProperty("prefix", "Pb");
        hide(() -> feet(one.get("at")), () -> feet(one.get("at")));
        film.waitTicks(20);
        crowd(one, true);
        Vec3[] target = new Vec3[1];
        UUID[] id = new UUID[1];
        Vec3[] last = new Vec3[1];
        Set<UUID> before = new HashSet<>();
        film.run(() -> {
            Vec3 c = feet(one.get("at"));
            target[0] = new Vec3(c.x + 300, 0, c.z);
            Fight f = squads.get("pb");
            film.withServer(s -> {
                f.range = 160;
                f.sky = eye -> skyTarget(s.overworld(), eye, DroneEntity.class, 160);
                f.start();
            });
            ClientFlights.all().forEach(x -> before.add(x.id));
            Film.cmd(String.format(Locale.ROOT, "incoming salvo drone 1 0 at %.1f %.1f %.1f from %.1f %.1f", target[0].x, heights.top(target[0].x, target[0].z),
                    target[0].z, c.x - 900, c.z));
        });
        film.waitUntil(() -> {
            for (ClientFlights.Tracked f : ClientFlights.all()) if (!before.contains(f.id) && id[0] == null) id[0] = f.id;
            ClientFlights.Tracked f = id[0] == null ? null : ClientFlights.find(id[0]);
            if (f != null) last[0] = f.position(1);
            return id[0] != null && f == null;
        }, 20 * 120);
        film.run(() -> {
            double d = last[0] == null ? -1 : Math.hypot(last[0].x - target[0].x, last[0].z - target[0].z);
            out.addProperty("last_distance", d);
            out.addProperty("downed", d > 100);
            Fight f = squads.get("pb");
            film.withServer(s -> f.stop());
        });
    }

    /** Ближайший снаряд этого вида над бойцом в {@code range} блоках — с упреждением на 6 тиков (поток сервера). */
    @Nullable
    private static Vec3 skyTarget(ServerLevel level, Vec3 eye, Class<? extends Entity> type, double range) {
        Entity best = null;
        double bd = range * range;
        for (Entity e : level.getEntitiesOfClass(type, new AABB(eye, eye).inflate(range))) {
            if (e.getY() < eye.y + 5) continue;
            double d = e.distanceToSqr(eye);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best == null ? null : best.position().add(best.getDeltaMovement().scale(6));
    }

    // ================================================================ конец дубля

    /** Полёты долетели, пробы после, боты ушли, мир сохранён — итог дубля и выход. */
    private void finish() {
        int wait = data == null ? 0 : data.limits().get("flights_wait_s").getAsInt() * 20;
        film.waitUntil(() -> ClientFlights.all().isEmpty(), wait);
        film.waitTicks(60);
        srv(level -> {
            for (ChunkPos p : heldPads) ChunkTickets.hold(level, PAD_TICKETS, p.toLong(), false);
            heldPads.clear();
            for (Probe p : probes) p.after = p.measure(level);
        });
        film.run(() -> film.withServer(s -> {
            for (String b : bots) film.cast().remove(b);
            if (standin) film.cast().remove("Artem");
            for (Fight f : squads.values()) f.stop();
            crowds.values().forEach(Crowd::removeAll);
        }));
        film.waitTicks(40);
        CompletableFuture<?>[] save = new CompletableFuture<?>[1];
        film.then(() -> {
            if (save[0] == null) {
                MinecraftServer s = mc.getSingleplayerServer();
                save[0] = s.submit(() -> s.saveEverything(true, true, true));
            }
            return save[0].isDone();
        });
        film.run(() -> {
            boolean saved = !save[0].isCompletedExceptionally();
            if (saved) Incoming.LOG.info(SAVED + " {}", takeId);
            write("done", null, saved);
            film.rec.finish();
            Incoming.LOG.info("TRAILER done");
            Runtime.getRuntime().halt(0);
        });
    }

    /** Остановиться с причиной: конвейер встаёт «остановлен, жду» и показывает её одной строкой. */
    private void stop(String why) {
        Incoming.LOG.error("NEWISLE stopped: {}", why);
        write("stopped", why, false);
        Runtime.getRuntime().halt(3);
    }

    private void warn(String what) {
        Incoming.LOG.warn("NEWISLE {}: {}", takeId, what);
        warnings.add(what);
    }

    private void write(String status, @Nullable String reason, boolean saved) {
        result.addProperty("status", status);
        if (reason != null) result.addProperty("reason", reason);
        result.addProperty("saved", saved);
        result.addProperty("seconds", (System.currentTimeMillis() - startedMs) / 1000.0);
        result.add("shots", shotsOut);
        JsonObject pr = new JsonObject();
        for (Probe p : probes) pr.add(p.name, p.report());
        result.add("probes", pr);
        result.add("warnings", warnings);
        try {
            Files.createDirectories(resultFile.toAbsolutePath().getParent());
            Path tmp = resultFile.resolveSibling(resultFile.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
            Files.move(tmp, resultFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Incoming.LOG.error("NEWISLE: итог дубля не записан в {}", resultFile, e);
        }
    }

    // ================================================================ мир и точки

    /**
     * Шаг, который сам ставит шаги (сцена дирижабля — от места, известного только в мире): они идут сразу за ним, до
     * остальных шагов дубля.
     */
    private Film.Step inline(Runnable build) {
        return () -> {
            Film.Step self = film.steps.poll();
            List<Film.Step> rest = new ArrayList<>(film.steps);
            film.steps.clear();
            build.run();
            film.steps.addAll(rest);
            film.steps.addFirst(self);
            return true;
        };
    }

    /** Работа на сервере, которую сценарий ждёт; ошибка — дубль остановлен с ней (иначе шаг ждал бы вечно). */
    private void srv(java.util.function.Consumer<ServerLevel> work) {
        film.onServer(level -> {
            try {
                work.accept(level);
            } catch (RuntimeException e) {
                Incoming.LOG.error("NEWISLE: работа сервера упала", e);
                stop("ошибка постановки: " + e);
            }
        });
    }

    /** Невидимка — в 20 блоках над местом (места — к моменту шага): мир там грузится и приходит к клиенту. */
    private void hide(java.util.function.Supplier<Vec3> p, java.util.function.Supplier<Vec3> look) {
        film.run(() -> war.place(p.get().add(0, 20, 0), look.get()));
        film.waitTicks(20);
    }

    /** Бот-статист: вход через Almighty, ждать — {@link War#settle}. */
    private void spawn(String name, Vec3 at, float yaw) {
        bots.add(name);
        film.cast().spawn(name, at, yaw, null);
    }

    private <T> T server(Function<ServerLevel, T> work) {
        MinecraftServer s = mc.getSingleplayerServer();
        if (s.isSameThread()) return work.apply(s.overworld());
        return s.submit(() -> work.apply(s.overworld())).join();
    }

    /** Точка сценария (у цели и глаза — над верхом на {@code above}). */
    private Vec3 at(JsonElement spec, double above) {
        return data.point(spec, above, heights);
    }

    /** Место человека или постройки: ноги — на верху колонки. */
    private Vec3 feet(JsonElement spec) {
        return data.point(spec, 0, heights);
    }

    /** Земля под домом: вниз от верха колонки до природного блока (камень, земля, песок), не дальше 120 блоков. */
    static int natural(ServerLevel level, double x, double z) {
        int top = War.groundY(level, x, z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(Mth.floor(x), top - 1, Mth.floor(z));
        for (int i = 0; i < 120 && p.getY() > level.getMinBuildHeight(); i++, p.move(0, -1, 0)) {
            BlockState s = level.getBlockState(p);
            if (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Tags.Blocks.GRAVELS)
                    || s.is(BlockTags.TERRACOTTA) || s.is(Tags.Blocks.SANDSTONE_BLOCKS) || !s.getFluidState().isEmpty()) {
                return p.getY() + 1;
            }
        }
        return top;
    }

    private static String block(Vec3 p) {
        return block(BlockPos.containing(p));
    }

    private static String block(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static JsonArray vec(Vec3 p) {
        JsonArray a = new JsonArray();
        a.add(Math.round(p.x * 10) / 10.0);
        a.add(Math.round(p.y * 10) / 10.0);
        a.add(Math.round(p.z * 10) / 10.0);
        return a;
    }

    static Class<? extends StrikeProjectile> entity(WeaponType w) {
        return switch (w) {
            case DRONE -> DroneEntity.class;
            case MISSILE -> CruiseMissileEntity.class;
            case BUNKER -> BunkerBusterEntity.class;
            case NUKE -> IcbmEntity.class;
            case ROCKET -> RocketEntity.class;
            case LOITER -> LoiterEntity.class;
        };
    }
}
