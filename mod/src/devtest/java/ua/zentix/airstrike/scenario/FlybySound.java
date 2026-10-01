package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.flight.FlightTrack;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.Hearing;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Облёт для проверки звука без ушей: зритель стоит на земле, снаряды проходят мимо него так, как задумано каждым
 * случаем звука, а в лог идёт всё, что слышно, — по каждому слою мотора (без порога громкости), запуск и остановка
 * каждой петли, разовые звуки мода, отметки пуска и удара по серверу, в конце случая — итоги {@code SCENARIO check}.
 * <p>
 * Случаи (по порядку, каждый ждёт, пока его снаряды отзвучат):
 * <ul>
 *   <li>{@code approach} — крылатая ракета заходит из 1,8 км прямо на зрителя (он в 30 блоках сбоку от цели): свист
 *       подлёта слышно с дальнего края (звук, изданный за ~1,5 км), он нарастает до удара;</li>
 *   <li>{@code dogleg} — ракета на обходе маршрута (пуск в 2 км до цели, точка обхода в 580 блоках сбоку): зритель
 *       в 40 блоках от середины первого плеча, ракета идёт мимо него курсом в сторону от цели — свиста подлёта нет,
 *       только мотор и шорох воздуха рядом;</li>
 *   <li>{@code pass} — ракета идёт на цель в 700 блоках за зрителем и проходит в 60 блоках сбоку: свист на подлёте,
 *       после пролёта стихает плавно; мотор на подлёте только громче, вслед только тише и звучит, пока до уха не дойдёт
 *       взрыв;</li>
 *   <li>{@code grad} — залп РСЗО из 32 снарядов, пусковая в 300 блоках позади, цели в 300 ± 150 впереди: вой всю дугу
 *       у каждого снаряда, каналов не больше {@code VoiceBudget.CAP};</li>
 *   <li>{@code far} — ракета и шахед издалека на зрителя: сперва вне мира (путь по пакетам сервера, {@code s}), потом
 *       сущностью ({@code e}) — звук переходит без перезапуска и скачка громкости.</li>
 * </ul>
 * Строки лога (время: {@code t} — тики случая, {@code ms} — часы системы для сверки с audio.wav; в начале случая —
 * короткий звук «pling» интерфейса, по нему WAV совмещается с {@code SCENARIO mark … sync}):
 * <pre>
 * SCENARIO mark &lt;случай&gt; sync|fire|launch|enter-world|leave-world|stall|move|gone|end …
 * SCENARIO loop start|stop &lt;случай&gt; &lt;оружие&gt;#&lt;id&gt; &lt;слой&gt; src=e|s d=… vol=… (stop: lived=… peak=…)
 * SCENARIO gain &lt;случай&gt; t=… ms=… live=N | &lt;оружие&gt;#&lt;id&gt;(e|s d=…) &lt;слой&gt; громкость×тон …
 * SCENARIO play &lt;случай&gt; &lt;звук&gt; d=… t=… ms=…
 * SCENARIO check &lt;случай&gt; &lt;что&gt; PASS|FAIL|WARN …
 * </pre>
 * {@code d} у слоя — расстояние до точки, чей звук слышен сейчас (запаздывающий момент), а не до снаряда.
 * Запуск: {@code tools/client_scenario.sh flyby-all} (или один случай: {@code flyby-grad}),
 * в полной сборке — {@code tools/prod_client.py flyby-all}.
 */
final class FlybySound {
    private static final List<String> ALL = List.of("approach", "dogleg", "pass", "grad", "far");
    /** Сколько тиков звук ещё может идти к уху после удара (самый дальний — ~1500 блоков, 90 тиков). */
    private static final int RINGOUT = 140;
    /** Дольше этого случай не ждёт своих снарядов (сервер в облаке отстаёт). */
    private static final int TIMEOUT = 4000;
    /** Громкость слоя, с которой его считаем слышным в проверках. */
    private static final double HEARD = 0.02;
    /** Снарядов в залпе РСЗО. */
    private static final int GRAD = 32;

    private enum Stage { WAIT, LOAD, CASE_SETTLE, RUN, RINGOUT, DONE }

    /** Слой мотора в этот тик: снаряд, какой слой, откуда путь, громкость, тон, расстояние до слышимой точки. */
    private record Sample(int t, UUID id, String weapon, String layer, boolean server, double vol, double pitch, double d) {}

    /** Что сервер знает о снаряде: для отметок пуска, входа в мир и удара. */
    private record Seen(String weapon, boolean virtual, Vec3 pos) {}

    private final List<String> cases;
    private int index = -1;
    private Stage stage = Stage.WAIT;
    private int tick, stageTick, caseTick;
    private Vec3 ear = Vec3.ZERO;
    private String current = "-";

    // --- сервер (поток сервера) ---
    private final Map<UUID, Seen> seen = new HashMap<>();
    /** Снаряды, впервые увиденные во время случая: UUID → случай. */
    private final Map<UUID, String> caseOf = new ConcurrentHashMap<>();
    /** Живые снаряды по случаям; удары — тик случая, когда снаряд пропал. */
    private final Map<UUID, Boolean> alive = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> goneAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> enteredAt = new ConcurrentHashMap<>();
    /** Игровой тик, когда снаряд последний раз сдвинулся; стоящие дольше {@link #STALL} тиков (только сервер). */
    private final Map<UUID, Long> movedAt = new HashMap<>();
    private final Set<UUID> stalled = new HashSet<>();
    private static final int STALL = 10;
    private volatile String serverCase = "-";
    private volatile int serverCaseTick;

    // --- клиент ---
    private Map<SoundInstance, Loop> loops = new IdentityHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    /** Тики случая, когда движку велели играть взрыв. */
    private final List<Integer> blasts = new ArrayList<>();
    private int maxLive;

    /** Петля мотора, пока звучит. */
    private static final class Loop {
        final UUID id;
        final String weapon, layer;
        final int started;
        double peak;

        Loop(UUID id, String weapon, String layer, int started) {
            this.id = id;
            this.weapon = weapon;
            this.layer = layer;
            this.started = started;
        }
    }

    FlybySound(String which) {
        cases = "all".equals(which) ? ALL : List.of(which.split(","));
        for (String c : cases) {
            if (!ALL.contains(c)) throw new IllegalArgumentException("SCENARIO flyby: нет случая " + c + " (есть " + ALL + ")");
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onPlay);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        tick++;
        stageTick++;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> scan(server.overworld()));
        switch (stage) {
            case WAIT -> {
                if (stageTick < 40) return;
                server("time set 6000");
                server("weather clear");
                server("gamerule doDaylightCycle false");
                server("gamerule doWeatherCycle false");
                server("gamerule doMobSpawning false");
                AirstrikeConfig.SERVER.launchNearPlayer.set(false);
                AirstrikeConfig.SERVER.maxSalvo.set(GRAD); // по умолчанию залп — до 30
                server(String.format(Locale.ROOT, "tp %s 0.5 200 0.5", name()));
                next(Stage.LOAD);
            }
            case LOAD -> {
                // зритель — на земле: высота по карте высот клиента, когда его чанк пришёл
                int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
                if (stageTick < 100 || y <= mc.level.getMinBuildHeight() && stageTick < 1200) return;
                ear = new Vec3(0.5, y, 0.5);
                Airstrike.LOG.info("SCENARIO flyby зритель на {}", xyz(ear));
                nextCase();
            }
            case CASE_SETTLE -> {
                if (stageTick < 60) return;
                caseTick = 0;
                mark("sync", "");
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 2.0f, 0.6f));
                fire(server);
                next(Stage.RUN);
            }
            case RUN -> {
                caseTick++;
                probe(mc);
                boolean launched = caseOf.containsValue(current);
                boolean flying = caseOf.entrySet().stream().anyMatch(en -> en.getValue().equals(current) && alive.containsKey(en.getKey()));
                if (stageTick > 40 && launched && !flying) next(Stage.RINGOUT);
                else if (stageTick > TIMEOUT) {
                    check("timeout", "FAIL", "снаряды случая не отзвучали за " + TIMEOUT + " тиков");
                    next(Stage.RINGOUT);
                }
            }
            case RINGOUT -> {
                caseTick++;
                probe(mc);
                if (stageTick < RINGOUT) return;
                finish();
                nextCase();
            }
            case DONE -> {
                // клиент закрывается не в тот же тик: итоги уже в логе
            }
        }
        if (tick % 100 == 0) Airstrike.LOG.info("SCENARIO fps {}", mc.getFps());
    }

    private void next(Stage s) {
        stage = s;
        stageTick = 0;
    }

    private void nextCase() {
        index++;
        samples.clear();
        events.clear();
        blasts.clear();
        loops = new IdentityHashMap<>();
        maxLive = 0;
        if (index >= cases.size()) {
            serverCase = "-";
            Airstrike.LOG.info("SCENARIO done");
            next(Stage.DONE);
            Minecraft.getInstance().stop();
            return;
        }
        current = cases.get(index);
        serverCase = current;
        Vec3 from = geometry().listenerFacing();
        server(String.format(Locale.ROOT, "tp %s %.1f %.1f %.1f facing %.1f %.1f %.1f", name(), ear.x, ear.y, ear.z, from.x, ear.y + 10, from.z));
        next(Stage.CASE_SETTLE);
    }

    // ---------------------------------------------------------------- случаи

    /**
     * Где цель и откуда смотреть. {@code yaw} — курс захода (у каждого случая свой, чтобы воронки не ложились в одно
     * место); {@code target} — только x и z, высоту знает сервер.
     */
    private record Geometry(float yaw, Vec3 target, Vec3 listenerFacing) {}

    private Geometry geometry() {
        return switch (current) {
            case "approach" -> {
                // цель в 20 блоках впереди и 30 сбоку: ракета идёт на зрителя и проходит рядом
                float yaw = 0;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(20)).add(side.scale(30)), ear.subtract(dir.scale(1000)));
            }
            case "dogleg" -> {
                // маршрут с обходом (как у пуска с пусковой, но без разворота на месте): пуск в 2000 блоках до цели на
                // прямой захода, точка входа в 500, путь 2400 — точка обхода в ~580 блоках сбоку, плечи под ~40° к прямой;
                // зритель в 40 блоках от середины первого плеча (оно идёт в сторону от цели)
                float yaw = 90;
                Vec3 dir = Local.horizontal(yaw);
                Vec3 start = Vec3.ZERO.subtract(dir.scale(DOGLEG_START));
                Vec3 m = doglegRoute(start, Vec3.ZERO, dir).points().getFirst();
                Vec3 leg = m.subtract(start.x, 0, start.z);
                Vec3 mid = start.add(leg.scale(0.5)).add(perp(leg.normalize()).scale(40));
                Vec3 target = ear.subtract(mid.x, 0, mid.z);
                yield new Geometry(yaw, target, target.add(start));
            }
            case "pass" -> {
                // цель в 700 блоках впереди и 60 сбоку: ракета проходит мимо зрителя, не на него
                float yaw = 180;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(700)).add(side.scale(-60)), ear.subtract(dir.scale(1000)));
            }
            case "grad" -> {
                // пусковая без стреляющего рядом стоит в 600 блоках до цели: зритель посередине дуги, в 20 блоках сбоку
                float yaw = 270;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(300)).add(side.scale(20)), ear.subtract(dir.scale(300)));
            }
            case "far" -> {
                // ракета и шахед из-за дальности сущностей прямо на зрителя, цель в 150 блоках за ним
                float yaw = 45;
                Vec3 dir = Local.horizontal(yaw), side = perp(dir);
                yield new Geometry(yaw, ear.add(dir.scale(150)).add(side.scale(10)), ear.subtract(dir.scale(1000)));
            }
            default -> throw new IllegalStateException(current);
        };
    }

    private static final double DOGLEG_START = 2000;
    /** Ракета случаев approach, pass и far пущена издалека за столько блоков до цели. */
    private static final double MISSILE_FROM = 1840;

    /** Время полёта ракеты (настройка, секунд), при котором она стартует за {@link #MISSILE_FROM} блоков до цели. */
    private static int missileSeconds() {
        return (int) Math.ceil(MISSILE_FROM / (WeaponSpec.MISSILE.airframe().cruiseSpeed() * 20));
    }

    private static Route doglegRoute(Vec3 start, Vec3 target, Vec3 dir) {
        return Route.plan(start, target, dir, 2400, 500, 1);
    }

    private static Vec3 perp(Vec3 dir) {
        return new Vec3(-dir.z, 0, dir.x);
    }

    /** Пуск снарядов случая — на потоке сервера, от консоли сервера (курс захода — поворот команды). */
    private void fire(MinecraftServer server) {
        Geometry g = geometry();
        String name = current;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            Vec3 t = Target.Ground.at(level, g.target().x, g.target().z).pos();
            String at = String.format(Locale.ROOT, "at %.1f %.1f %.1f", t.x, t.y, t.z);
            String rotated = String.format(Locale.ROOT, "execute rotated %.0f 0 run airstrike ", g.yaw());
            var c = AirstrikeConfig.SERVER;
            switch (name) {
                case "approach", "pass" -> {
                    c.missileFlightTime.set(missileSeconds());
                    run(server, rotated + "missile " + at);
                }
                case "dogleg" -> {
                    // маршрут с обходом бывает только у пуска с пусковой рядом со стреляющим; здесь он задан прямо
                    StrikeProjectile p = ModEntities.CRUISE_MISSILE.get().create(level);
                    if (p == null) return;
                    Vec3 dir = Local.horizontal(g.yaw());
                    Target.Ground target = Target.Ground.at(level, t.x, t.z);
                    Vec3 start = target.pos().subtract(dir.scale(DOGLEG_START)).add(0, 12, 0);
                    p.launch(start, target, target.pos(), null);
                    Route route = doglegRoute(start, target.pos(), dir);
                    p.setRoute(route);
                    // с пусковой ракета уходит сразу к первой точке маршрута, а не на цель
                    float yaw = ua.zentix.airstrike.guidance.FlightController.anglesTo(start, route.points().getFirst().add(0, start.y, 0))[0];
                    try {
                        ((ua.zentix.airstrike.guidance.FlightController) FLIGHT.get(p)).set(yaw, 0);
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException("SCENARIO flyby: курс ракеты не задать", ex);
                    }
                    p.moveTo(start.x, start.y, start.z, yaw, 0);
                    VirtualFlights.launch(level, p);
                    Airstrike.LOG.info("SCENARIO flyby dogleg маршрут {} → {}", xyz(start), p.getUUID());
                }
                case "grad" -> run(server, rotated + "salvo rocket " + GRAD + " 150 " + at);
                case "far" -> {
                    c.missileFlightTime.set(missileSeconds());
                    c.droneFlightTime.set(30);
                    run(server, rotated + "drone " + at);
                    run(server, rotated + "missile " + at);
                }
                default -> throw new IllegalStateException(name);
            }
            Airstrike.LOG.info("SCENARIO mark {} fire target={} game={} ms={}", name, xyz(t), level.getGameTime(), System.currentTimeMillis());
        });
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        Airstrike.LOG.info("SCENARIO /{}", command);
    }

    // ---------------------------------------------------------------- сервер: пуски и удары

    /** Снаряды на сервере (в мире и вне его): отметки пуска, входа в мир и выхода, удара. */
    private void scan(ServerLevel level) {
        Map<UUID, Seen> now = new LinkedHashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof StrikeProjectile p && !p.isRemoved()) now.put(p.getUUID(), new Seen(p.weapon().getSerializedName(), false, p.position()));
        }
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) now.put(p.getUUID(), new Seen(p.weapon().getSerializedName(), true, p.position()));
        String c = serverCase;
        for (var en : now.entrySet()) {
            Seen was = seen.get(en.getKey()), is = en.getValue();
            if (was == null) {
                if (!"-".equals(c)) caseOf.put(en.getKey(), c);
                serverMark(level, c, "launch", en.getKey(), is);
            } else if (was.virtual() && !is.virtual()) {
                enteredAt.put(en.getKey(), serverCaseTick);
                serverMark(level, c, "enter-world", en.getKey(), is);
            } else if (!was.virtual() && is.virtual()) {
                serverMark(level, c, "leave-world", en.getKey(), is);
            }
            // снаряд стоит на месте (снаряд в полёте не стоит): звук по пакетам тогда затихает, это видно в gain
            long game = level.getGameTime();
            if (was == null || was.pos().distanceToSqr(is.pos()) > 1e-4) {
                if (stalled.remove(en.getKey())) serverMark(level, c, "move", en.getKey(), is);
                movedAt.put(en.getKey(), game);
            } else if (game - movedAt.getOrDefault(en.getKey(), game) >= STALL && stalled.add(en.getKey())) {
                serverMark(level, c, "stall", en.getKey(), is);
            }
            alive.put(en.getKey(), true);
        }
        for (var en : seen.entrySet()) {
            if (now.containsKey(en.getKey())) continue;
            alive.remove(en.getKey());
            movedAt.remove(en.getKey());
            stalled.remove(en.getKey());
            goneAt.put(en.getKey(), serverCaseTick);
            serverMark(level, c, "gone", en.getKey(), en.getValue());
        }
        seen.clear();
        seen.putAll(now);
    }

    private void serverMark(ServerLevel level, String c, String what, UUID id, Seen s) {
        Airstrike.LOG.info("SCENARIO mark {} {} {}#{} virtual={} pos={} d={} game={} ms={}", c, what, s.weapon(), shortId(id), s.virtual(), xyz(s.pos()),
                String.format(Locale.ROOT, "%.0f", s.pos().distanceTo(ear)), level.getGameTime(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- клиент: что слышно

    /** Все звучащие слои моторов (без порога): петли, их запуск и остановка, строка громкости раз в 5 тиков. */
    private void probe(Minecraft mc) {
        serverCaseTick = caseTick;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Map<SoundInstance, Loop> now = new IdentityHashMap<>();
        StringBuilder line = new StringBuilder();
        for (SoundInstance s : Set.copyOf(mc.getSoundManager().soundEngine.instanceToChannel.keySet())) {
            if (!ENGINE.isInstance(s)) continue;
            Loop loop = loops.get(s);
            boolean fromServer;
            try {
                FlightTrack track = (FlightTrack) TRACK.get(s);
                if (loop == null) loop = new Loop(track.id, track.weapon.toString().toLowerCase(Locale.ROOT),
                        LAYER.get(s).toString().toLowerCase(Locale.ROOT), caseTick);
                fromServer = track.fromServer();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("SCENARIO flyby: поля звука мода изменились", ex);
            }
            double vol = s.getSound() != null ? s.getVolume() : 0, pitch = s.getPitch();
            double d = Math.sqrt((s.getX() - cam.x) * (s.getX() - cam.x) + (s.getY() - cam.y) * (s.getY() - cam.y) + (s.getZ() - cam.z) * (s.getZ() - cam.z));
            if (!loops.containsKey(s)) {
                Airstrike.LOG.info("SCENARIO loop start {} {}#{} {} src={} d={} vol={} t={} ms={}", current, loop.weapon, shortId(loop.id), loop.layer,
                        fromServer ? "s" : "e", f0(d), f3(vol), caseTick, System.currentTimeMillis());
                events.add("start " + loop.id + " " + loop.layer + " " + caseTick);
            }
            loop.peak = Math.max(loop.peak, vol);
            now.put(s, loop);
            samples.add(new Sample(caseTick, loop.id, loop.weapon, loop.layer, fromServer, vol, pitch, d));
            line.append(String.format(Locale.ROOT, " %s#%s(%s d=%.0f) %s %.3f×%.2f;", loop.weapon, shortId(loop.id), fromServer ? "s" : "e", d, loop.layer, vol, pitch));
        }
        for (var en : loops.entrySet()) {
            if (now.containsKey(en.getKey())) continue;
            Loop l = en.getValue();
            Airstrike.LOG.info("SCENARIO loop stop {} {}#{} {} lived={} peak={} t={} ms={}", current, l.weapon, shortId(l.id), l.layer, caseTick - l.started,
                    f3(l.peak), caseTick, System.currentTimeMillis());
            events.add("stop " + l.id + " " + l.layer + " " + caseTick);
        }
        loops = now;
        maxLive = Math.max(maxLive, now.size());
        if (caseTick % 5 == 0) {
            Airstrike.LOG.info("SCENARIO gain {} t={} ms={} live={} |{}", current, caseTick, System.currentTimeMillis(), now.size(), line);
        }
    }

    /** Разовые звуки мода (пуск, отделение, взрывы): когда движку велели их играть. */
    private void onPlay(PlaySoundEvent e) {
        SoundInstance s = e.getSound();
        if (s == null || stage != Stage.RUN && stage != Stage.RINGOUT || s.isLooping() || !Airstrike.MOD_ID.equals(s.getLocation().getNamespace())) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        String path = s.getLocation().getPath();
        if (path.startsWith("blast.") || path.equals("rocket.blast")) blasts.add(caseTick);
        Airstrike.LOG.info("SCENARIO play {} {} d={} t={} ms={}", current, path, f0(new Vec3(s.getX(), s.getY(), s.getZ()).distanceTo(cam)),
                caseTick, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- итоги случая

    private void finish() {
        mark("end", String.format(Locale.ROOT, "max_live=%d loops_started=%d", maxLive, events.stream().filter(s -> s.startsWith("start")).count()));
        check("max_live", maxLive <= 48 ? "PASS" : "FAIL", "max_live=" + maxLive + " (предел 48)");
        int restarts = restarts();
        check("restarts", restarts == 0 ? "PASS" : "WARN", "петля того же слоя того же снаряда снова запущена ≤40 тиков после остановки: " + restarts);
        switch (current) {
            case "approach" -> checkApproach();
            case "dogleg" -> checkDogleg();
            case "pass" -> checkPass();
            case "grad" -> checkGrad();
            case "far" -> checkFar();
            default -> { }
        }
    }

    private void checkApproach() {
        List<Sample> w = layer("missile_whistle");
        // 0.05: слабее — ещё звук первой точки пути, пока настоящий идёт к уху (путь начинается на краю слышимости)
        Sample first = w.stream().filter(s -> s.vol >= 0.05).findFirst().orElse(null);
        if (first == null) {
            check("whistle_heard", "FAIL", "свиста подлёта не было");
            return;
        }
        Integer impact = impactOf("missile");
        int warning = impact == null ? -1 : impact - first.t;
        // звук обгоняет ракету на d·(1/v − 1/c): с 1500 блоков — ~290 тиков
        int expected = (int) (first.d * (1 / WeaponSpec.MISSILE.airframe().cruiseSpeed() - 1 / ua.zentix.airstrike.client.sound.Acoustics.SPEED));
        check("whistle_from", first.d >= 1000 ? "PASS" : "FAIL", "свист слышно с " + f0(first.d) + " блоков (слышимая точка), громкость " + f3(first.vol));
        check("whistle_warning", warning >= expected * 0.8 ? "PASS" : "FAIL", "свист за " + warning + " тиков до удара (ожидание ~" + expected + ")");
        double far = maxVol(w, 800, 1300), near = maxVol(w, 60, 400);
        check("whistle_rises", near > far ? "PASS" : "FAIL", "свист вдали (800–1300) " + f3(far) + ", вблизи (60–400) " + f3(near));
    }

    private void checkDogleg() {
        List<Sample> w = layer("missile_whistle");
        // только до ближайшей точки: вслед затихает обтекание пролёта (на 350 блоках ~0,02), это не свист атаки
        // обтекание слышно до AIRFLOW и гаснет ещё FADE блоков; свист атаки был бы слышен дальше (срез JET)
        int closest = w.stream().min((a, b) -> Double.compare(a.d, b.d)).map(Sample::t).orElse(Integer.MAX_VALUE);
        double outside = w.stream().filter(s -> s.t <= closest && s.d > Hearing.AIRFLOW + Hearing.FADE).mapToDouble(Sample::vol).max().orElse(0);
        check("no_attack_whistle", outside < HEARD ? "PASS" : "FAIL", "свист дальше " + (int) (Hearing.AIRFLOW + Hearing.FADE) + " блоков (только подлёт на цель): " + f3(outside));
        double engine = samples.stream().filter(s -> s.layer.startsWith("missile_") && !s.layer.equals("missile_whistle")).mapToDouble(Sample::vol).max().orElse(0);
        check("engine_heard", engine >= HEARD ? "PASS" : "WARN", "мотор на обходе: " + f3(engine));
    }

    private void checkPass() {
        List<Sample> w = layer("missile_whistle");
        Sample peak = w.stream().max((a, b) -> Double.compare(a.vol, b.vol)).orElse(null);
        if (peak == null || peak.vol < 0.1) {
            check("whistle_heard", "FAIL", "свист на подлёте " + (peak == null ? 0 : f3(peak.vol)));
            return;
        }
        int fade = w.stream().filter(s -> s.t > peak.t && s.vol < 0.1 * peak.vol).mapToInt(Sample::t).findFirst().orElse(Integer.MAX_VALUE) - peak.t;
        check("whistle_fades", fade >= 6 ? "PASS" : "FAIL", "после пика " + f3(peak.vol) + " до 10% за " + (fade == Integer.MAX_VALUE - peak.t ? "∞" : fade) + " тиков");
        // мотор: сумма мощностей слоёв на подлёте не падает, вслед не растёт (запас 1 дБ на сглаживание громкости).
        // Ближе 170 блоков слои «спереди»/«сзади» выбирает угол к курсу (выхлоп сзади громче), мощность там не функция
        // расстояния; их переход проверяет EngineSoundTest, здесь — только дальний слой и его передача ближним
        final double nearZone = 170;
        Map<Integer, double[]> byTick = new java.util.TreeMap<>();
        for (Sample s : samples) {
            if (!s.layer.startsWith("missile_") || s.layer.equals("missile_whistle")) continue;
            double[] a = byTick.computeIfAbsent(s.t, k -> new double[] {0, Double.MAX_VALUE});
            a[0] += s.vol * s.vol;
            a[1] = Math.min(a[1], s.d);
        }
        int bad = 0;
        double prevP = -1, prevD = -1;
        String worst = "";
        for (double[] a : byTick.values()) {
            if (prevP > 1e-6 && a[0] > 1e-6 && Math.min(prevD, a[1]) >= nearZone) {
                double db = 10 * Math.log10(a[0] / prevP);
                // расстояние стоит (путь ещё не начался, звук первой точки нарастает) — сравнивать не с чем
                if (Math.abs(a[1] - prevD) < 0.5) {
                    prevP = a[0];
                    continue;
                }
                boolean closer = a[1] < prevD;
                if (closer && db < -1 || !closer && db > 1) {
                    bad++;
                    worst = String.format(Locale.ROOT, "%.1f дБ при %.0f→%.0f блоков", db, prevD, a[1]);
                }
            }
            prevP = a[0];
            prevD = a[1];
        }
        check("engine_even", bad == 0 ? "PASS" : "FAIL", "скачков мощности мотора против расстояния: " + bad + (bad > 0 ? ", последний " + worst : ""));
        // мотор дальней ракеты (путь по пакетам сервера) звучит, пока до уха не дойдёт взрыв: без паузы тишины перед ним
        int blast = blasts.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int stop = events.stream().filter(ev -> ev.startsWith("stop ")).mapToInt(ev -> Integer.parseInt(ev.substring(ev.lastIndexOf(' ') + 1))).max().orElse(-1);
        check("engine_until_blast", blast >= 0 && stop >= blast ? "PASS" : "FAIL", "мотор смолк на тике " + stop + ", взрыв — на тике " + blast);
    }

    private void checkGrad() {
        List<UUID> rockets = caseOf.entrySet().stream().filter(en -> en.getValue().equals(current)).map(Map.Entry::getKey).toList();
        long heard = rockets.stream().filter(id -> samples.stream().anyMatch(s -> s.id.equals(id) && s.layer.equals("rocket_air") && s.vol >= HEARD)).count();
        check("each_rocket_howls", heard >= Math.ceil(rockets.size() * 0.9) ? "PASS" : "FAIL", "выли после выгорания " + heard + " из " + rockets.size()
                + " (заказано " + GRAD + ")");
        // от первого воя до последнего удара рёв или вой слышно без перерыва
        int from = samples.stream().filter(s -> s.layer.equals("rocket_air") && s.vol >= HEARD).mapToInt(Sample::t).min().orElse(-1);
        int to = rockets.stream().map(goneAt::get).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(-1);
        int gaps = 0;
        for (int t = from; from >= 0 && t <= to; t++) {
            final int tt = t;
            if (samples.stream().noneMatch(s -> s.t == tt && s.vol >= HEARD)) gaps++;
        }
        check("no_silence", gaps <= 2 ? "PASS" : "FAIL", "тиков тишины между первым воем (" + from + ") и последним ударом (" + to + "): " + gaps);
    }

    private void checkFar() {
        List<UUID> ids = caseOf.entrySet().stream().filter(en -> en.getValue().equals(current)).map(Map.Entry::getKey).toList();
        for (UUID id : ids) {
            List<Sample> mine = samples.stream().filter(s -> s.id.equals(id)).toList();
            String weapon = mine.isEmpty() ? "?" : mine.getFirst().weapon;
            int handover = mine.stream().filter(s -> !s.server).mapToInt(Sample::t).min().orElse(-1);
            boolean fromServer = mine.stream().anyMatch(s -> s.server);
            if (!fromServer || handover < 0) {
                check("handover_" + weapon, "WARN", weapon + "#" + shortId(id) + ": переход с пакетов на сущность не увиден (s=" + fromServer + ", e=" + (handover >= 0) + ")");
                continue;
            }
            // снаряд идёт на зрителя: на переходе мощность слоёв не должна проседать (новые слои вблизи — не перезапуск)
            long restartsNear = restarts(id, handover - 20, handover + 20);
            double before = power(mine, handover - 3, handover - 1), dip = Double.MAX_VALUE;
            for (int t = handover; t <= handover + 5; t++) dip = Math.min(dip, power(mine, t, t));
            double db = before > 0 && dip > 0 ? 10 * Math.log10(dip / before) : 0;
            boolean ok = restartsNear == 0 && db >= -1.5;
            check("handover_" + weapon, ok ? "PASS" : "FAIL", String.format(Locale.ROOT,
                    "%s#%s: сущность с t=%d (сервер: в мире с t=%s), перезапусков слоёв рядом %d, провал мощности за 5 тиков %.1f дБ",
                    weapon, shortId(id), handover, enteredAt.get(id), restartsNear, db));
        }
    }

    /** Средняя мощность всех слоёв снаряда за тики [a, b]. */
    private static double power(List<Sample> mine, int a, int b) {
        return mine.stream().filter(s -> s.t >= a && s.t <= b).mapToDouble(s -> s.vol * s.vol).sum() / (b - a + 1);
    }

    private int restarts() {
        return restarts(null, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** Слой снаряда снова запущен не позже 40 тиков после остановки (у {@code id}, если задан, в тики [a, b]). */
    private int restarts(UUID id, int a, int b) {
        Map<String, Integer> stopped = new HashMap<>();
        int n = 0;
        for (String ev : events) {
            String[] p = ev.split(" ");
            if (id != null && !p[1].equals(id.toString())) continue;
            String key = p[1] + " " + p[2];
            int t = Integer.parseInt(p[3]);
            if (p[0].equals("stop")) stopped.put(key, t);
            else if (stopped.containsKey(key) && t - stopped.get(key) <= 40 && t >= a && t <= b) n++;
        }
        return n;
    }

    private Integer impactOf(String weapon) {
        return caseOf.entrySet().stream().filter(en -> en.getValue().equals(current)).map(Map.Entry::getKey)
                .filter(id -> samples.stream().anyMatch(s -> s.id.equals(id) && s.weapon.equals(weapon))).map(goneAt::get)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private List<Sample> layer(String layer) {
        return samples.stream().filter(s -> s.layer.equals(layer)).toList();
    }

    private static double maxVol(List<Sample> list, double dMin, double dMax) {
        return list.stream().filter(s -> s.d >= dMin && s.d <= dMax).mapToDouble(Sample::vol).max().orElse(0);
    }

    private void check(String what, String verdict, String detail) {
        Airstrike.LOG.info("SCENARIO check {} {} {} {}", current, what, verdict, detail);
    }

    private void mark(String what, String detail) {
        Airstrike.LOG.info("SCENARIO mark {} {} t={} ms={} {}", current, what, caseTick, System.currentTimeMillis(), detail);
    }

    // ---------------------------------------------------------------- мелочи

    private static String name() {
        return Minecraft.getInstance().player.getGameProfile().getName();
    }

    private static void server(String command) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> run(server, command));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // слой и путь петли закрыты в звуке мода (пакет client.sound) — через отражение
    private static final Class<?> ENGINE;
    private static final Field TRACK, LAYER, FLIGHT;

    static {
        try {
            ENGINE = Class.forName("ua.zentix.airstrike.client.sound.EngineSound");
            TRACK = open(ENGINE.getDeclaredField("track"));
            LAYER = open(ENGINE.getDeclaredField("layer"));
            FLIGHT = open(StrikeProjectile.class.getDeclaredField("flight"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Field f) {
        f.setAccessible(true);
        return f;
    }
}
