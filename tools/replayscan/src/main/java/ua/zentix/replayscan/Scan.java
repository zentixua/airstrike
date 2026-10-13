package ua.zentix.replayscan;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.payload.ClientboundCustomSetTimePayload;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import ua.zentix.replayscan.stream.ReplayFile;
import ua.zentix.replayscan.stream.Timeline;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Разбор одного повтора в потоке сервера повтора: в конце каждого его тика ({@code ServerTickEvent.Post}) мир
 * повтора — уже с пакетами этого тика, а свой разбор записи ({@link Timeline}, {@link Decoder}) даёт сами события
 * тика: что записал клиент, по кодекам игры и модов, не по тому, что сервер повтора отдаёт зрителю (он отдаёт звуки,
 * частицы и сущности по расстоянию до камеры зрителя). Действия следующего тика разбираются заранее: замеры земли
 * у места взрыва — по миру до него (воронка ещё не вырыта).
 * <p>
 * Таблицы (README.md): события ({@code events}), сущности ({@code entities}, {@code tracks}, {@code states}),
 * дальние снаряды из пакетов модов ({@code far}), блоки ({@code blocks}), время игры ({@code time}), глаза
 * зрителей ({@code viewers}) и видимость ({@code sight}).
 */
final class Scan implements Closeable {
    /** Пакеты состояния, которых много и которые есть в мире повтора или не нужны: в ленту событий не идут. */
    private static final Set<String> QUIET = Set.of("ClientboundLevelChunkWithLightPacket", "ClientboundLightUpdatePacket",
            "ClientboundForgetLevelChunkPacket", "ClientboundChunkBatchStartPacket", "ClientboundChunkBatchFinishedPacket",
            "ClientboundChunksBiomesPacket", "ClientboundSetChunkCacheCenterPacket", "ClientboundSetChunkCacheRadiusPacket",
            "ClientboundSetSimulationDistancePacket", "ClientboundMoveEntityPacket$Pos", "ClientboundMoveEntityPacket$PosRot",
            "ClientboundMoveEntityPacket$Rot", "ClientboundTeleportEntityPacket", "ClientboundRotateHeadPacket",
            "ClientboundSetEntityMotionPacket", "ClientboundSetEntityDataPacket", "ClientboundUpdateAttributesPacket",
            "ClientboundSetEquipmentPacket", "ClientboundSetTimePacket", "ClientboundKeepAlivePacket", "ClientboundPingPacket",
            "ClientboundBlockUpdatePacket", "ClientboundSectionBlocksUpdatePacket", "ClientboundBlockChangedAckPacket",
            "ClientboundContainerSetSlotPacket", "ClientboundContainerSetContentPacket", "ClientboundContainerSetDataPacket",
            "ClientboundSetExperiencePacket", "ClientboundUpdateMobEffectPacket", "ClientboundRemoveMobEffectPacket",
            "ClientboundAnimatePacket", "ClientboundBlockDestructionPacket", "ClientboundBlockEventPacket",
            "ClientboundBlockEntityDataPacket", "ClientboundSetPassengersPacket", "ClientboundSetEntityLinkPacket",
            "ClientboundTakeItemEntityPacket", "ClientboundLevelParticlesPacket", "ClientboundUpdateRecipesPacket",
            "ClientboundRecipePacket", "ClientboundUpdateAdvancementsPacket", "ClientboundAwardStatsPacket",
            "ClientboundCommandsPacket", "ClientboundUpdateTagsPacket", "ClientboundSetDefaultSpawnPositionPacket",
            "ClientboundTickingStatePacket", "ClientboundTickingStepPacket", "ClientboundPlayerAbilitiesPacket",
            "ClientboundSetCarriedItemPacket", "ClientboundCooldownPacket", "ClientboundMapItemDataPacket",
            "ClientboundCustomPayloadPacket", "ClientboundSetHealthPacket", "ClientboundSetBorderCenterPacket",
            "ClientboundInitializeBorderPacket", "ClientboundServerDataPacket", "ClientboundCustomChatCompletionsPacket",
            "ClientboundSetObjectivePacket", "ClientboundSetScorePacket", "ClientboundResetScorePacket",
            "ClientboundSetDisplayObjectivePacket", "ClientboundSetPlayerTeamPacket", "ClientboundTabListPacket",
            "ClientboundPlayerInfoUpdatePacket", "ClientboundHurtAnimationPacket");
    /** Пакеты модов, которые ничего не говорят о событиях: свет и дубль появления сущности (NeoForge). */
    private static final Set<String> QUIET_PAYLOADS = Set.of("neoforge:auxiliary_light_data", "neoforge:advanced_add_entity");
    /** Имена полей с местом события в пакетах модов — по порядку предпочтения (вложенные записи — тоже). */
    private static final List<String> PLACE_NAMES = List.of("pos", "position", "burst", "center", "origin");
    private static final TicketType<ChunkPos> ASK_TICKET = TicketType.create("replayscan", Comparator.comparingLong(ChunkPos::toLong));
    /** Видимость не считается дальше от оси взгляда, чем столько градусов (ни в какой кадр не войдёт). */
    private static final double MAX_ANGLE = 75;
    /** Замеры земли у места события: вниз до блока — не дальше, ближайший блок — в кубе такого радиуса. */
    private static final int CLEAR_MAX = 64, SOLID_RADIUS = 6;

    private final Settings settings;
    private final FlashbackLink link;
    private final Logger log;
    private final ReplayFile file;
    private Timeline timeline;
    @Nullable
    private Timeline.Action carry;
    private Decoder decoder;
    private final long started = System.nanoTime();

    private ResourceKey<Level> dim = Level.OVERWORLD;
    private final Map<ResourceKey<Level>, LongSet> known = new HashMap<>();
    /** Последний разобранный тик ({@code -1} — ни одного). */
    private int last = -1;
    /** События тика {@code ahead} (разобраны заранее, с замерами по миру до него). */
    private List<Event> next = new ArrayList<>();
    private int ahead = -1;
    private final List<Far> farNow = new ArrayList<>();
    private final List<Far> farNext = new ArrayList<>();
    private long eventSeq;
    private boolean done;
    private final List<String> notes = new ArrayList<>();
    private int gaps;

    private final Map<Entity, Tracked> tracked = new IdentityHashMap<>();
    private int entitySeq;
    private final List<Camera> cameras;
    private final Set<String> ticketed = new java.util.HashSet<>();

    private final Csv events, blocks, far, tracks, entities, states, sight, viewers, time;

    /** Событие с местом или без; замеры земли — до тика события ({@code pre}) или после (первый тик, перемотка). */
    private static final class Event {
        final long id;
        final int tick;
        final ResourceKey<Level> dim;
        final String kind, src;
        @Nullable
        final Vec3 pos;
        final double size;
        @Nullable
        final String uuid;
        final String data;
        final boolean measure;
        int clear = Integer.MIN_VALUE;
        double solid = Double.NaN;
        boolean pre;

        Event(long id, int tick, ResourceKey<Level> dim, String kind, String src, @Nullable Vec3 pos, double size, @Nullable String uuid, String data, boolean measure) {
            this.id = id;
            this.tick = tick;
            this.dim = dim;
            this.kind = kind;
            this.src = src;
            this.pos = pos;
            this.size = size;
            this.uuid = uuid;
            this.data = data;
            this.measure = measure;
        }
    }

    /** Снаряд вдали из пакета мода: список записей с UUID и местом {@code pos}. */
    private record Far(ResourceKey<Level> dim, String src, String uuid, Vec3 pos, String data) {}

    private static final class Tracked {
        final int eid;
        final Entity entity;
        final String type;
        final boolean subject, player;
        final int first;
        final Vec3 firstPos;
        int lastSeen;
        Vec3 pos;
        float yaw, pitch;
        String data = "";
        ResourceKey<Level> dim;

        Tracked(int eid, Entity e, String type, boolean subject, int tick) {
            this.eid = eid;
            this.entity = e;
            this.type = type;
            this.subject = subject;
            this.player = e instanceof Player;
            this.first = tick;
            this.firstPos = e.position();
            this.lastSeen = tick;
            this.dim = e.level().dimension();
        }
    }

    /** Камера из файла {@code ask}: глаз, взгляд (не обязателен) и отрезок тиков. */
    private record Camera(String id, ResourceKey<Level> dim, Vec3 eye, float yaw, float pitch, boolean look, int from, int to) {}

    /** Зритель тика: игрок записи, игрок или камера. */
    private record Viewer(String id, ServerLevel level, Vec3 eye, float yaw, float pitch, boolean look) {}

    Scan(Settings settings, FlashbackLink link, Logger log) throws IOException {
        this.settings = settings;
        this.link = link;
        this.log = log;
        this.file = ReplayFile.open(settings.replay());
        this.timeline = new Timeline(file);
        this.cameras = settings.ask() == null ? List.of() : cameras(settings.ask());
        Path out = settings.out();
        Files.createDirectories(out);
        events = new Csv(out.resolve("events.csv.gz"), "e", "t", "dim", "kind", "src", "x", "y", "z", "size", "uuid", "clear", "solid", "pre", "data");
        blocks = new Csv(out.resolve("blocks.csv.gz"), "t", "dim", "x", "y", "z", "state");
        far = new Csv(out.resolve("far.csv.gz"), "t", "dim", "src", "uuid", "x", "y", "z", "data");
        tracks = new Csv(out.resolve("tracks.csv.gz"), "t", "eid", "x", "y", "z", "yaw", "pitch", "vx", "vy", "vz", "ground");
        entities = new Csv(out.resolve("entities.csv.gz"), "eid", "id", "uuid", "type", "name", "subject", "player", "dim", "first", "last",
                "x0", "y0", "z0", "x1", "y1", "z1");
        states = new Csv(out.resolve("states.csv.gz"), "t", "eid", "data");
        sight = new Csv(out.resolve("sight.csv.gz"), "t", "viewer", "kind", "ref", "dist", "ahead", "cx", "cy", "hard", "soft", "unknown");
        viewers = new Csv(out.resolve("viewers.csv.gz"), "t", "viewer", "dim", "x", "y", "z", "yaw", "pitch");
        time = new Csv(out.resolve("time.csv.gz"), "t", "game_time", "day_time");
    }

    boolean done() {
        return done;
    }

    int last() {
        return last;
    }

    int total() {
        return file.totalTicks();
    }

    /** Конец тика сервера повтора {@code t}. */
    void tick(MinecraftServer server, int t) throws IOException, ReflectiveOperationException {
        if (done) return;
        if (decoder == null) decoder = new Decoder(server.registryAccess());
        if (t <= last) {
            // назад (перемотка): запись — с начала, без таблиц до t
            notes.add("seek back from " + last + " to " + t);
            timeline = new Timeline(file);
            carry = null;
            ahead = -1;
            next = new ArrayList<>();
            known.clear();
            last = -1;
        }
        boolean seeking = link.fastForwarding(server);
        List<Event> now;
        farNow.clear();
        if (ahead == t) {
            now = next;
            farNow.addAll(farNext);
        } else {
            if (last >= 0 && t > last + 1) gaps++;
            now = new ArrayList<>();
            read(server, t, now, farNow, !seeking && t >= settings.from());
        }
        boolean output = !seeking && t >= settings.from();
        if (output) {
            for (Event e : now) {
                if (e.measure && e.clear == Integer.MIN_VALUE) measure(server, e, false);
                write(e);
            }
            for (Far f : farNow) {
                far.add(t).add(f.dim.location().toString()).add(f.src).add(f.uuid).add(f.pos.x).add(f.pos.y).add(f.pos.z).add(f.data).end();
            }
            sample(server, t);
            look(server, t, now);
        }
        last = t;
        // события следующего тика — заранее: замеры у взрыва по миру до него
        next = new ArrayList<>();
        farNext.clear();
        read(server, t + 1, next, farNext, true);
        ahead = t + 1;
        for (Event e : next) {
            if (e.measure) measure(server, e, true);
        }
        int end = settings.to() > 0 ? Math.min(settings.to(), total()) : total();
        if (t >= end) done = true;
    }

    /** Действия до тика {@code t} включительно; события тика {@code t} — в {@code out}, если {@code emit}. */
    private void read(MinecraftServer server, int t, List<Event> out, List<Far> farOut, boolean emit) throws IOException {
        while (true) {
            Timeline.Action a = carry != null ? carry : timeline.next();
            carry = null;
            if (a == null) return;
            if (a.tick() > t) {
                carry = a;
                return;
            }
            boolean live = emit && a.tick() == t && !a.snapshot();
            int tick = a.tick();
            decoder.decode(a, live, item -> item(server, tick, item, live, out, farOut));
        }
    }

    private void item(MinecraftServer server, int t, Decoder.Item item, boolean live, List<Event> out, List<Far> farOut) {
        switch (item) {
            case Decoder.Chunk c -> {
                if (!c.forwarded()) known.computeIfAbsent(dim, k -> new LongOpenHashSet()).add(ReplayFile.pack(c.x(), c.z()));
            }
            case Decoder.CachedChunk c -> {
                try {
                    long place = file.cachedChunk(c.index());
                    if (place != Long.MIN_VALUE) known.computeIfAbsent(dim, k -> new LongOpenHashSet()).add(place);
                } catch (IOException e) {
                    notes.add("chunk cache " + c.index() + ": " + e);
                }
            }
            case Decoder.GamePacket g -> packet(server, t, g, live, out);
            case Decoder.Payload p -> {
                if (live) payload(t, p, out, farOut);
            }
            case Decoder.RawPayload r -> {
                if (live) out.add(new Event(eventSeq++, t, dim, "payload", r.id().toString(), null, r.bytes(), null,
                        "{\"undecoded\":" + Json.of(r.error()) + "}", false));
            }
        }
    }

    private void packet(MinecraftServer server, int t, Decoder.GamePacket g, boolean live, List<Event> out) {
        Packet<?> p = g.packet();
        if (p instanceof ClientboundLoginPacket login) dim = login.commonPlayerSpawnInfo().dimension();
        if (p instanceof ClientboundRespawnPacket respawn) dim = respawn.commonPlayerSpawnInfo().dimension();
        if (!live || g.forwarded()) return;
        switch (p) {
            case ClientboundSetTimePacket time -> this.time.add(t).add(time.getGameTime()).add(time.getDayTime()).end();
            case ClientboundBlockUpdatePacket b -> block(t, b.getPos(), b.getBlockState());
            case ClientboundSectionBlocksUpdatePacket s -> s.runUpdates((pos, state) -> block(t, pos, state));
            default -> {
                String name = Decoder.name(p.getClass());
                if (QUIET.contains(name)) return;
                out.add(event(server, t, name, p));
            }
        }
    }

    private void block(int t, BlockPos pos, BlockState state) {
        blocks.add(t).add(dim.location().toString()).add(pos.getX()).add(pos.getY()).add(pos.getZ()).add(BlockStateParser.serialize(state)).end();
    }

    private Event event(MinecraftServer server, int t, String name, Packet<?> p) {
        ServerLevel level = server.getLevel(dim);
        Map<String, Object> data = new LinkedHashMap<>();
        Vec3 pos = null;
        double size = Double.NaN;
        String uuid = null;
        boolean measure = false;
        switch (p) {
            case ClientboundExplodePacket e -> {
                pos = new Vec3(e.getX(), e.getY(), e.getZ());
                size = e.getPower();
                measure = true;
                data.put("blocks", e.getToBlow().size());
                data.put("sound", e.getExplosionSound());
            }
            case ClientboundSoundPacket s -> {
                pos = new Vec3(s.getX(), s.getY(), s.getZ());
                size = s.getVolume();
                data.put("sound", s.getSound());
                data.put("source", s.getSource());
                data.put("pitch", s.getPitch());
            }
            case ClientboundSoundEntityPacket s -> {
                Entity en = level == null ? null : level.getEntity(s.getId());
                pos = en == null ? null : en.position();
                size = s.getVolume();
                data.put("sound", s.getSound());
                data.put("source", s.getSource());
                data.put("pitch", s.getPitch());
                data.put("entity", s.getId());
                if (en != null) data.put("type", en.getType());
            }
            case ClientboundLevelEventPacket l -> {
                pos = l.getPos().getCenter();
                data.put("type", l.getType());
                data.put("data", l.getData());
                data.put("global", l.isGlobalEvent());
            }
            case ClientboundAddEntityPacket a -> {
                pos = new Vec3(a.getX(), a.getY(), a.getZ());
                uuid = a.getUUID().toString();
                data.put("id", a.getId());
                data.put("type", a.getType());
                data.put("velocity", new Vec3(a.getXa(), a.getYa(), a.getZa()));
            }
            case ClientboundDamageEventPacket d -> {
                Entity en = level == null ? null : level.getEntity(d.entityId());
                pos = en == null ? null : en.position();
                data.put("entity", d.entityId());
                if (en != null) data.put("type", en.getType());
                data.put("damage", d.sourceType());
                data.put("cause", d.sourceCauseId());
                data.put("direct", d.sourceDirectId());
            }
            case ClientboundEntityEventPacket ev -> {
                Entity en = level == null ? null : ev.getEntity(level);
                pos = en == null ? null : en.position();
                data.put("event", ev.getEventId());
                if (en != null) {
                    data.put("entity", en.getId());
                    data.put("type", en.getType());
                }
            }
            default -> {
                return new Event(eventSeq++, t, dim, "packet", name, null, Double.NaN, null, Json.of(p), false);
            }
        }
        return new Event(eventSeq++, t, dim, "packet", name, pos, size, uuid, Json.of(data), measure);
    }

    private void payload(int t, Decoder.Payload p, List<Event> out, List<Far> farOut) {
        Object v = p.value();
        String src = p.id().toString();
        if (QUIET_PAYLOADS.contains(src)) return;
        if (v instanceof ClientboundCustomSetTimePayload time) {
            // время NeoForge (вместо ванильного пакета времени у клиента NeoForge)
            this.time.add(t).add(time.gameTime()).add(time.dayTime()).end();
            return;
        }
        if (!(v instanceof Record r)) {
            out.add(new Event(eventSeq++, t, dim, "payload", src, null, Double.NaN, null, Json.of(v), false));
            return;
        }
        List<?> list = farList(r);
        if (list != null) {
            for (Object o : list) {
                Record e = (Record) o;
                String id = null;
                Vec3 pos = null;
                Map<String, Object> rest = new LinkedHashMap<>();
                for (RecordComponent c : e.getClass().getRecordComponents()) {
                    Object value = Json.component(e, c);
                    if (id == null && value instanceof UUID u) id = u.toString();
                    else if (pos == null && c.getName().equals("pos") && value instanceof Vec3 vec) pos = vec;
                    else rest.put(c.getName(), value);
                }
                if (id != null && pos != null) farOut.add(new Far(dim, src, id, pos, Json.of(rest)));
            }
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        flatten(r, "", 0, fields);
        Vec3 pos = null;
        for (String name : PLACE_NAMES) {
            for (Map.Entry<String, Object> f : fields.entrySet()) {
                String key = f.getKey().substring(f.getKey().lastIndexOf('.') + 1);
                if (pos == null && key.equals(name)) pos = place(f.getValue());
            }
        }
        if (pos == null) pos = fields.values().stream().filter(o -> o instanceof Vec3).map(o -> (Vec3) o).findFirst().orElse(null);
        String uuid = fields.values().stream().filter(o -> o instanceof UUID).map(Object::toString).findFirst().orElse(null);
        out.add(new Event(eventSeq++, t, dim, "payload", src, pos, Double.NaN, uuid, Json.of(v), pos != null));
    }

    /** Поля записи и вложенных записей (до двух уровней) по пути {@code a.b}; {@code Optional} — его значение. */
    private static void flatten(Record r, String prefix, int depth, Map<String, Object> out) {
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            Object value = Json.component(r, c);
            if (value instanceof java.util.Optional<?> o) value = o.orElse(null);
            if (value == null) continue;
            out.put(prefix + c.getName(), value);
            if (value instanceof Record nested && depth < 2) flatten(nested, prefix + c.getName() + ".", depth + 1, out);
        }
    }

    @Nullable
    private static Vec3 place(Object value) {
        if (value instanceof Vec3 vec) return vec;
        if (value instanceof BlockPos b) return b.getCenter();
        return null;
    }

    /** Список дальних снарядов в пакете мода: единственный список записей с UUID и местом {@code pos}. */
    @Nullable
    private static List<?> farList(Record r) {
        for (RecordComponent c : r.getClass().getRecordComponents()) {
            if (!(Json.component(r, c) instanceof List<?> l) || l.isEmpty() || !(l.getFirst() instanceof Record e)) continue;
            boolean id = false, pos = false;
            for (RecordComponent ec : e.getClass().getRecordComponents()) {
                if (ec.getType() == UUID.class) id = true;
                if (ec.getType() == Vec3.class && ec.getName().equals("pos")) pos = true;
            }
            if (id && pos) return l;
        }
        return null;
    }

    private void measure(MinecraftServer server, Event e, boolean pre) {
        ServerLevel level = server.getLevel(e.dim);
        if (level == null || e.pos == null) return;
        Rays rays = new Rays(level, known.getOrDefault(e.dim, new LongOpenHashSet()));
        e.clear = rays.clearance(e.pos, CLEAR_MAX);
        e.solid = rays.nearestSolid(e.pos, SOLID_RADIUS);
        e.pre = pre;
    }

    private void write(Event e) {
        events.add(e.id).add(e.tick).add(e.dim.location().toString()).add(e.kind).add(e.src);
        if (e.pos != null) events.add(e.pos.x).add(e.pos.y).add(e.pos.z);
        else events.empty().empty().empty();
        events.add(e.size).add(e.uuid);
        if (e.clear != Integer.MIN_VALUE) events.add(e.clear).add(e.solid).add(e.pre);
        else events.empty().empty().empty();
        events.add(e.data).end();
    }

    /** Сущности мира повтора: время жизни, путь (за кем следим и игроки), данные (за кем следим). */
    private void sample(MinecraftServer server, int t) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e.getClass().getName().endsWith(".ReplayPlayer")) continue;
                Tracked k = tracked.get(e);
                if (k == null) {
                    String type = EntityType.getKey(e.getType()).toString();
                    k = new Tracked(entitySeq++, e, type, settings.subjects().matcher(type).matches(), t);
                    tracked.put(e, k);
                }
                k.lastSeen = t;
                k.dim = level.dimension();
                if (!k.subject && !k.player) continue;
                Vec3 p = e.position();
                if (!p.equals(k.pos) || e.getYRot() != k.yaw || e.getXRot() != k.pitch) {
                    Vec3 v = e.getDeltaMovement();
                    tracks.add(t).add(k.eid).add(p.x).add(p.y).add(p.z).add(e.getYRot()).add(e.getXRot()).add(v.x).add(v.y).add(v.z).add(e.onGround()).end();
                    k.pos = p;
                    k.yaw = e.getYRot();
                    k.pitch = e.getXRot();
                }
                if (k.subject) {
                    List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
                    Map<Integer, Object> data = new LinkedHashMap<>();
                    if (values != null) {
                        for (SynchedEntityData.DataValue<?> dv : values) data.put(dv.id(), dv.value());
                    }
                    String json = Json.of(data);
                    if (!json.equals(k.data)) {
                        states.add(t).add(k.eid).add(json).end();
                        k.data = json;
                    }
                }
            }
        }
        for (Iterator<Tracked> it = tracked.values().iterator(); it.hasNext(); ) {
            Tracked k = it.next();
            if (k.lastSeen < t) {
                lifetime(k);
                it.remove();
            }
        }
    }

    private void lifetime(Tracked k) {
        Entity e = k.entity;
        Vec3 end = k.pos != null ? k.pos : e.position();
        entities.add(k.eid).add(e.getId()).add(e.getUUID().toString()).add(k.type).add(e instanceof Player pl ? pl.getGameProfile().getName() : null)
                .add(k.subject).add(k.player).add(k.dim.location().toString()).add(k.first).add(k.lastSeen)
                .add(k.firstPos.x).add(k.firstPos.y).add(k.firstPos.z).add(end.x).add(end.y).add(end.z).end();
    }

    /** Глаза зрителей и что им видно: сущности и дальние снаряды — раз в {@code every} тиков, события — всегда. */
    private void look(MinecraftServer server, int t, List<Event> now) throws ReflectiveOperationException {
        int local = link.localPlayerId(server);
        List<Viewer> list = viewers(server, t, local);
        boolean entitiesNow = t % settings.every() == 0;
        for (Viewer v : list) {
            viewers.add(t).add(v.id).add(v.level.dimension().location().toString()).add(v.eye.x).add(v.eye.y).add(v.eye.z)
                    .add(v.look ? v.yaw : Double.NaN).add(v.look ? v.pitch : Double.NaN).end();
            Rays rays = new Rays(v.level, known.getOrDefault(v.level.dimension(), new LongOpenHashSet()));
            Vec3[] axes = axes(v.yaw, v.pitch);
            for (Event e : now) {
                if (e.measure && e.pos != null && e.dim == v.level.dimension()) see(t, v, axes, rays, "p", Long.toString(e.id), e.pos);
            }
            if (!entitiesNow) continue;
            for (Tracked k : tracked.values()) {
                if (k.lastSeen != t || k.dim != v.level.dimension() || !(k.subject || k.player)) continue;
                Entity e = k.entity;
                if (v.id.equals("recorder") && e.getId() == local) continue;
                see(t, v, axes, rays, "e", Integer.toString(k.eid), e.getBoundingBox().getCenter());
            }
            for (Far f : farNow) {
                if (f.dim == v.level.dimension()) see(t, v, axes, rays, "f", f.uuid, f.pos);
            }
        }
    }

    private void see(int t, Viewer v, Vec3[] axes, Rays rays, String kind, String ref, Vec3 target) {
        Vec3 d = target.subtract(v.eye);
        double dist = d.length();
        if (dist > settings.range() || dist < 0.5) return;
        double z = d.dot(axes[0]);
        double cx = Double.NaN, cy = Double.NaN;
        if (v.look) {
            if (Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, z / dist)))) > MAX_ANGLE) return;
            cx = d.dot(axes[1]) / z;
            cy = d.dot(axes[2]) / z;
        }
        Rays.Hit hit = rays.cast(v.eye, target, 1.0);
        sight.add(t).add(v.id).add(kind).add(ref).add(dist).add(z).add(cx).add(cy).add(hit.hard()).add(hit.soft()).add(hit.unknown()).end();
    }

    /** Взгляд, правая и верхняя оси камеры (поворот — как у игрока: yaw 0 — юг, pitch вниз — плюс). */
    static Vec3[] axes(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        Vec3 forward = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        Vec3 right = new Vec3(-Math.cos(y), 0, -Math.sin(y));
        Vec3 up = right.cross(forward);
        return new Vec3[]{forward, right, up};
    }

    private List<Viewer> viewers(MinecraftServer server, int t, int local) {
        List<Viewer> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            Entity rec = local < 0 ? null : level.getEntity(local);
            if (rec != null) list.add(new Viewer("recorder", level, rec.getEyePosition(), rec.getYRot(), rec.getXRot(), true));
            if (settings.viewers().equals("players")) {
                for (Tracked k : tracked.values()) {
                    if (!k.player || k.entity.getId() == local || k.entity.level() != level || k.lastSeen != t) continue;
                    Player pl = (Player) k.entity;
                    list.add(new Viewer("player:" + pl.getGameProfile().getName(), level, pl.getEyePosition(), pl.getYHeadRot(), pl.getXRot(), true));
                }
            }
        }
        for (Camera c : cameras) {
            ServerLevel level = server.getLevel(c.dim);
            if (level == null) continue;
            ChunkPos at = new ChunkPos(BlockPos.containing(c.eye));
            int radius = Math.min(32, (int) Math.ceil(settings.range() / 16));
            // чанки вокруг камеры — с запасом до её начала: сервер повтора грузит их из своего сохранения не сразу
            boolean hold = t >= c.from - 200 && t <= c.to;
            if (hold && ticketed.add(c.id)) level.getChunkSource().addRegionTicket(ASK_TICKET, at, radius, at);
            if (!hold && ticketed.remove(c.id)) level.getChunkSource().removeRegionTicket(ASK_TICKET, at, radius, at);
            if (t >= c.from && t <= c.to) list.add(new Viewer("ask:" + c.id, level, c.eye, c.yaw, c.pitch, c.look));
        }
        return list;
    }

    private static List<Camera> cameras(Path ask) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(ask, StandardCharsets.UTF_8)).getAsJsonObject();
        List<Camera> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("cameras")) {
            JsonObject c = el.getAsJsonObject();
            JsonArray eye = c.getAsJsonArray("eye");
            JsonArray look = c.has("look") ? c.getAsJsonArray("look") : null;
            out.add(new Camera(c.get("id").getAsString(),
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.has("dim") ? c.get("dim").getAsString() : "minecraft:overworld")),
                    new Vec3(eye.get(0).getAsDouble(), eye.get(1).getAsDouble(), eye.get(2).getAsDouble()),
                    look == null ? 0 : look.get(0).getAsFloat(), look == null ? 0 : look.get(1).getAsFloat(), look != null,
                    c.has("from") ? c.get("from").getAsInt() : 0, c.has("to") ? c.get("to").getAsInt() : Integer.MAX_VALUE));
        }
        return out;
    }

    /** Таблицы закрыты, {@code meta.json} — что разобрано, сколько и чем. */
    @Override
    public void close() throws IOException {
        for (Tracked k : tracked.values()) lifetime(k);
        tracked.clear();
        for (Csv c : List.of(events, blocks, far, tracks, entities, states, sight, viewers, time)) c.close();
        JsonObject meta = new JsonObject();
        meta.addProperty("replay", settings.replay().toString());
        meta.addProperty("complete", done);
        meta.addProperty("last_tick", last);
        meta.addProperty("total_ticks", total());
        meta.addProperty("from", settings.from());
        meta.addProperty("to", settings.to());
        meta.addProperty("every", settings.every());
        meta.addProperty("range", settings.range());
        meta.addProperty("subjects", settings.subjects().pattern());
        meta.addProperty("viewers", settings.viewers());
        meta.addProperty("seconds", (System.nanoTime() - started) / 1e9);
        meta.addProperty("gaps", gaps);
        JsonObject replay = file.meta().deepCopy();
        replay.remove("chunks");
        replay.addProperty("segments", file.segments().size());
        meta.add("replay_meta", replay);
        JsonObject rows = new JsonObject();
        rows.addProperty("events", events.rows());
        rows.addProperty("blocks", blocks.rows());
        rows.addProperty("far", far.rows());
        rows.addProperty("tracks", tracks.rows());
        rows.addProperty("entities", entities.rows());
        rows.addProperty("states", states.rows());
        rows.addProperty("sight", sight.rows());
        rows.addProperty("viewers", viewers.rows());
        rows.addProperty("time", time.rows());
        meta.add("rows", rows);
        if (decoder != null) {
            meta.add("actions", counts(decoder.actions));
            meta.add("packets", counts(decoder.packets));
            meta.add("payloads", counts(decoder.payloads));
            JsonObject errors = new JsonObject();
            decoder.errors.forEach(errors::addProperty);
            meta.add("errors", errors);
        }
        JsonArray warnings = new JsonArray();
        timeline.warnings().forEach(warnings::add);
        notes.forEach(warnings::add);
        meta.add("warnings", warnings);
        JsonObject mods = new JsonObject();
        ModList.get().getMods().forEach(m -> mods.addProperty(m.getModId(), m.getVersion().toString()));
        meta.add("mods", mods);
        Files.writeString(settings.out().resolve("meta.json"), new GsonBuilder().setPrettyPrinting().create().toJson(meta), StandardCharsets.UTF_8);
        file.close();
        log.info("Replay scan: {} — {} of {} ticks, {} events, {} track rows, {} sight rows -> {}", settings.replay().getFileName(),
                last, total(), events.rows(), tracks.rows(), sight.rows(), settings.out());
    }

    private static JsonObject counts(Map<String, long[]> m) {
        JsonObject o = new JsonObject();
        m.forEach((k, v) -> o.addProperty(k, v[0]));
        return o;
    }
}
