package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.client.nuclear.ClientNuclear;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Журнал плана по тикам его повтора Flashback: где каждый снаряд (пути мода {@link FlightTracks} — по сущности у клиента
 * или по данным сервера, если сущности у клиента нет), где люди (игроки и боты), когда и где снаряд взорвался, ядерный
 * подрыв, отметки сценария. По нему камеры ставятся после записи, а не наугад: что и когда будет в кадре, известно до
 * вывода. Тик журнала — тик повтора ({@link FlashbackApi#writtenTicks}): запись Flashback кончает тик клиента после
 * {@code ClientTickEvent.Post}, в котором журнал пишет тот же номер.
 * <p>
 * Строки JSON: {@code {"t":N,"me":[x,y,z],"cam":[x,y,z,yaw,pitch,roll,fov],"p":[[имя,x,y,z],…]}} каждый тик (записывающий,
 * кинокамера плана, если она есть, и люди);
 * {@code {"t":N,"f":[[i,x,y,z,фаза,сущность],…]}} — где снаряды в тик N (пишется с отставанием {@link #LAG}); {@code {"t":N,"new":i,
 * "id":UUID,"weapon":…,"bomber":…}} — новый путь; {@code {"t":N,"impact":i,"pos":[…]}} — взрыв (путь кончился пакетом
 * взрыва) или {@code "lost"} — путь кончился без взрыва (ушёл из данных); {@code {"t":N,"nuke":[x,y,z],"kt":…}};
 * {@code {"t":N,"mark":…}}.
 */
final class EventLog {
    private final Writer out;
    private final Map<UUID, Integer> index = new HashMap<>();
    private final Set<Integer> ended = new HashSet<>();
    private final Set<Integer> nukes = new HashSet<>();
    private int lastTick = -1;

    EventLog(Path file) {
        try {
            Files.createDirectories(file.getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Путь снаряда пишется с отставанием {@link #LAG} тиков: о дальнем снаряде сервер шлёт пакеты раз в несколько тиков,
     * и тики между ними путь мода ({@link FlightTrack}) заполняет по прямой, когда приходит следующий пакет; запись по
     * прогнозу на текущий тик расходилась бы с тем, где снаряд был.
     */
    private static final int LAG = 12;
    /** По какой тик клиента ({@link FlightTracks#now}) записан путь каждого снаряда. */
    private final Map<Integer, Long> logged = new HashMap<>();
    private long offset;

    /** Тик клиента (после тика мира и путей мода): всё, что есть в этот тик повтора. */
    void tick(int t) {
        if (t < 0 || t == lastTick) return;
        lastTick = t;
        Minecraft mc = Minecraft.getInstance();
        long now = FlightTracks.now();
        offset = t - now;
        java.util.TreeMap<Long, StringBuilder> lines = new java.util.TreeMap<>();
        for (FlightTrack track : FlightTracks.all()) {
            Integer i = index.get(track.id);
            if (i == null || ended.contains(i) && !track.isDead()) {
                // новый путь (или тот же UUID полетел заново)
                i = index.size();
                index.put(track.id, i);
                logged.remove(i);
                write(String.format(Locale.ROOT, "{\"t\":%d,\"new\":%d,\"id\":\"%s\",\"weapon\":\"%s\",\"bomber\":%b}", t, i, track.id,
                        track.weapon.getSerializedName(), track.bomber));
            }
            if (ended.contains(i)) continue;
            path(track, i, track.isDead() ? track.deathTick() : Math.min(now - LAG, track.lastTick()), lines);
            if (track.isDead() && ended.add(i)) {
                double[] p = new double[3];
                track.at(track.deathTick(), p);
                // взрыв — путь кончился пакетом взрыва в этот же тик (FlightTracks.impact); иначе снаряд пропал из данных
                // (ушёл, отменён): путь тогда кончается через FlightTracks.STALE тиков без данных
                String kind = now - track.deathTick() <= 3 ? "impact" : "lost";
                write(String.format(Locale.ROOT, "{\"t\":%d,\"%s\":%d,\"at\":%d,\"pos\":[%.2f,%.2f,%.2f]}", track.deathTick() + offset,
                        kind, i, t, p[0], p[1], p[2]));
            }
        }
        for (var e : lines.entrySet()) write("{\"t\":" + e.getKey() + ",\"f\":[" + e.getValue() + "]}");
        StringBuilder people = new StringBuilder();
        if (mc.level != null) {
            for (Player pl : mc.level.players()) {
                if (pl == mc.player) continue;
                if (!people.isEmpty()) people.append(',');
                people.append(String.format(Locale.ROOT, "[\"%s\",%.2f,%.2f,%.2f]", pl.getGameProfile().getName(), pl.getX(), pl.getY(), pl.getZ()));
            }
        }
        Vec3 me = mc.player == null ? Vec3.ZERO : mc.player.position();
        // кинокамера плана, как её поставил сценарий: свой путь плана повторяется в выводе как есть
        CineCamera.Pose cam = CineCamera.pose();
        String camLine = cam == null ? "" : String.format(Locale.ROOT, ",\"cam\":[%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f]",
                cam.pos().x, cam.pos().y, cam.pos().z, cam.yaw(), cam.pitch(), cam.roll(), cam.fov());
        write(String.format(Locale.ROOT, "{\"t\":%d,\"me\":[%.1f,%.1f,%.1f]%s,\"p\":[%s]}", t, me.x, me.y, me.z, camLine, people));
        for (var d : ClientNuclear.detonations()) {
            if (d.live && nukes.add(d.d.id())) {
                Vec3 b = d.d.burst();
                write(String.format(Locale.ROOT, "{\"t\":%d,\"nuke\":[%.1f,%.1f,%.1f],\"kt\":%.1f}", t, b.x, b.y, b.z, d.d.yieldKt()));
            }
        }
    }

    /** Путь снаряда {@code i} с прошлой записи по тик клиента {@code upTo} — в строки по тикам повтора. */
    private void path(FlightTrack track, int i, long upTo, java.util.TreeMap<Long, StringBuilder> lines) {
        long from = Math.max(logged.getOrDefault(i, Long.MIN_VALUE) + 1, (long) Math.ceil(track.start()));
        double[] p = new double[3];
        for (long k = from; k <= upTo; k++) {
            track.at(k, p);
            StringBuilder b = lines.computeIfAbsent(k + offset, x -> new StringBuilder());
            if (!b.isEmpty()) b.append(',');
            b.append(String.format(Locale.ROOT, "[%d,%.2f,%.2f,%.2f,%d,%d]", i, p[0], p[1], p[2], track.phase(k), track.fromServer(k) ? 0 : 1));
        }
        if (upTo >= from) logged.put(i, upTo);
    }

    void mark(int t, String what) {
        write(String.format(Locale.ROOT, "{\"t\":%d,\"mark\":\"%s\"}", t, what));
    }

    private void write(String line) {
        try {
            out.write(line);
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void close() {
        // конец записи: путь живых снарядов — по всё, что известно
        java.util.TreeMap<Long, StringBuilder> lines = new java.util.TreeMap<>();
        for (FlightTrack track : FlightTracks.all()) {
            Integer i = index.get(track.id);
            if (i != null && !ended.contains(i)) path(track, i, track.lastTick(), lines);
        }
        for (var e : lines.entrySet()) write("{\"t\":" + e.getKey() + ",\"f\":[" + e.getValue() + "]}");
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ================================================================ чтение

    /** Путь снаряда по журналу: место на каждый тик, где он был в данных; конец — взрыв или пропажа. */
    static final class Flight {
        final int index;
        final UUID id;
        final String weapon;
        final boolean bomber;
        final int born;
        final java.util.TreeMap<Integer, double[]> at = new java.util.TreeMap<>();
        final Map<Integer, Integer> phase = new HashMap<>();
        /** Тик взрыва (−1 — не взорвался в записи) и его место. */
        int impact = -1;
        int lost = -1;
        Vec3 impactPos;

        Flight(int index, UUID id, String weapon, boolean bomber, int born) {
            this.index = index;
            this.id = id;
            this.weapon = weapon;
            this.bomber = bomber;
            this.born = born;
        }

        int first() {
            return at.isEmpty() ? born : at.firstKey();
        }

        int last() {
            return at.isEmpty() ? born : at.lastKey();
        }

        /**
         * Место в тик t: между записями — по прямой (данные сервера о дальнем снаряде приходят раз в несколько тиков), после
         * последней до взрыва — к месту взрыва, вне записи — ближайший край; null — пути нет.
         */
        @Nullable
        Vec3 pos(double t) {
            if (at.isEmpty()) return null;
            var lo = at.floorEntry((int) Math.floor(t));
            var hi = at.ceilingEntry((int) Math.ceil(t));
            if (lo == null) return vec(at.firstEntry().getValue());
            if (hi == null) {
                if (impact >= 0 && impactPos != null && impact > lo.getKey()) {
                    if (t >= impact) return impactPos;
                    double k = (t - lo.getKey()) / (impact - lo.getKey());
                    return vec(lo.getValue()).lerp(impactPos, k);
                }
                return vec(lo.getValue());
            }
            if (hi.getKey().equals(lo.getKey())) return vec(lo.getValue());
            double k = (t - lo.getKey()) / (hi.getKey() - lo.getKey());
            return vec(lo.getValue()).lerp(vec(hi.getValue()), k);
        }

        private static Vec3 vec(double[] p) {
            return new Vec3(p[0], p[1], p[2]);
        }

        /** Скорость в тик t, блоков за тик. */
        Vec3 velocity(double t) {
            Vec3 a = pos(t - 1), b = pos(t + 1);
            return a == null || b == null ? Vec3.ZERO : b.subtract(a).scale(0.5);
        }

        boolean present(double t) {
            return at.containsKey((int) Math.floor(t));
        }
    }

    record Mark(int t, String what) {}

    record Nuke(int t, Vec3 burst, double kt) {}

    /** Весь журнал плана. */
    static final class Data {
        final List<Flight> flights = new ArrayList<>();
        final Map<String, Map<Integer, Vec3>> people = new HashMap<>();
        final Map<Integer, Vec3> recorder = new HashMap<>();
        /** Кинокамера плана по тикам, как её вёл сценарий при записи. */
        final java.util.TreeMap<Integer, CineCamera.Pose> camera = new java.util.TreeMap<>();
        final List<Mark> marks = new ArrayList<>();
        final List<Nuke> nukes = new ArrayList<>();
        int ticks;

        List<Flight> of(String weapon) {
            return flights.stream().filter(f -> f.weapon.equals(weapon)).toList();
        }

        @Nullable
        Mark mark(String what) {
            return marks.stream().filter(m -> m.what.equals(what)).findFirst().orElse(null);
        }

        @Nullable
        Vec3 person(String name, int t) {
            Map<Integer, Vec3> track = people.get(name);
            if (track == null || track.isEmpty()) return null;
            Vec3 p = track.get(t);
            if (p != null) return p;
            int best = Integer.MAX_VALUE, at = -1;
            for (int k : track.keySet()) {
                if (Math.abs(k - t) < best) {
                    best = Math.abs(k - t);
                    at = k;
                }
            }
            return track.get(at);
        }
    }

    static Data read(Path file) throws IOException {
        Data d = new Data();
        Map<Integer, Flight> byIndex = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            int t = o.get("t").getAsInt();
            d.ticks = Math.max(d.ticks, t + 1);
            if (o.has("new")) {
                Flight f = new Flight(o.get("new").getAsInt(), UUID.fromString(o.get("id").getAsString()), o.get("weapon").getAsString(),
                        o.get("bomber").getAsBoolean(), t);
                byIndex.put(f.index, f);
                d.flights.add(f);
            } else if (o.has("impact") || o.has("lost")) {
                boolean impact = o.has("impact");
                Flight f = byIndex.get(o.get(impact ? "impact" : "lost").getAsInt());
                if (f == null) continue;
                if (impact) {
                    f.impact = t;
                    JsonArray p = o.getAsJsonArray("pos");
                    f.impactPos = new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
                } else {
                    f.lost = t;
                }
            } else if (o.has("f") || o.has("me")) {
                if (o.has("f")) {
                    for (var e : o.getAsJsonArray("f")) {
                        JsonArray a = e.getAsJsonArray();
                        Flight f = byIndex.get(a.get(0).getAsInt());
                        if (f == null) continue;
                        f.at.put(t, new double[]{a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()});
                        f.phase.put(t, a.get(4).getAsInt());
                    }
                }
                if (o.has("p")) {
                    for (var e : o.getAsJsonArray("p")) {
                        JsonArray a = e.getAsJsonArray();
                        d.people.computeIfAbsent(a.get(0).getAsString(), k -> new HashMap<>())
                                .put(t, new Vec3(a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()));
                    }
                }
                if (o.has("me")) {
                    JsonArray me = o.getAsJsonArray("me");
                    d.recorder.put(t, new Vec3(me.get(0).getAsDouble(), me.get(1).getAsDouble(), me.get(2).getAsDouble()));
                }
                if (o.has("cam")) {
                    JsonArray c = o.getAsJsonArray("cam");
                    d.camera.put(t, new CineCamera.Pose(new Vec3(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()),
                            c.get(3).getAsFloat(), c.get(4).getAsFloat(), c.get(5).getAsFloat(), c.get(6).getAsDouble()));
                }
            } else if (o.has("nuke")) {
                JsonArray p = o.getAsJsonArray("nuke");
                d.nukes.add(new Nuke(t, new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()), o.get("kt").getAsDouble()));
            } else if (o.has("mark")) {
                d.marks.add(new Mark(t, o.get("mark").getAsString()));
            }
        }
        return d;
    }
}
