package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.defense.Interceptor;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.strike.FarFlights;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.StrikeService;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.TargetTracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Звук мира плана — на сервере: каждый звук, который сервер играет (кто бы его ни слышал), с местом и временем, и то,
 * по чему звук мода играет сам клиент, если его пакет уходит только тем, кто рядом. В повтор Flashback попадает только
 * то, что сервер послал игроку записи, а ваниль шлёт звук лишь в его дальности (у выстрела Point Blank — 80 блоков):
 * камера вывода у боя, записанного за 300 блоков, его не слышала бы. Монтаж ставит по этому журналу недостающее так,
 * как поставила бы игра у камеры ({@code tools/trailer/takes.py}: {@code world_sfx}).
 * <p>
 * Пишет поток сервера, открывает и закрывает журнал поток клиента (запись плана, {@link Film#startReplay}); тик повтора
 * поток клиента отдаёт через volatile ({@link #clientTick}). Строки JSON, {@code t} — тик повтора, в который ляжет
 * пакет, ушедший клиенту записи в этот момент (тот же номер, что у {@link EventLog}), {@code gt} — время игры сервера:
 * <ul>
 * <li>{@code {"sound":id,"source":…,"pos":[x,y,z],"volume":…,"pitch":…,"seed":…,"file":…,"attenuation":…,"fv":…,"fp":…,
 * "range":…,"send":…,"heard":…,"by":имя,"entity":{"type":…,"uuid":…}}} — звук ({@code PlayLevelSoundEvent} после
 * всех подписчиков): громкость и тон, как ушли клиенту; зерно — как его вытянул {@code Level.playSound} (нет — своё
 * у вызывающего, не узнать); вариант записи — как его выберет клиент по зерну ({@code file}, его затухание и
 * множители громкости и тона); {@code range} — дальность линейного затухания у клиента, {@code send} — дальность,
 * в которой сервер шлёт пакет; {@code heard} — игрок записи в ней (у звука сущности — и сущность у него есть);
 * {@code by} — игрок, у которого звук (выстрел бота Point Blank — с места стрелка);</li>
 * <li>{@code {"trigger":вид,"pos":[…],"radius":…,"heard":…}} — пакет мода, по которому клиент играет звук сам, ушёл
 * только тем, кто ближе {@code radius} ({@code siren} — тревога у цели, {@code nuke_warning} — ядерная тревога у
 * тех, кто в радиусе, {@code grid_failure} — подстанция выбита; щелчок реле квартала, {@code S2C.GridDistrict} в 96
 * блоках, — нет: сервер помнит только квартал, не чанк, у которого звук);</li>
 * <li>{@code {"flights":[[uuid,оружие,x,y,z,слышно,heard],…],"interceptors":[[x,y,z,слышно,heard],…]}} — раз в
 * {@link #FLIGHT_EVERY} тиков: снаряды и зенитные ракеты, докуда слышен их мотор и были ли они у игрока записи
 * (сущность у него или в его пакете дальних полётов).</li>
 * </ul>
 */
final class WorldSounds {
    /** Снаряды и ракеты — раз в столько тиков сервера. */
    static final int FLIGHT_EVERY = 5;

    /** Тик повтора для пакета, ушедшего клиенту записи сейчас; −1 — запись не идёт. */
    private static volatile int replayTick = -1;
    /** Журнал идущей записи плана; null — запись не идёт. */
    @Nullable
    private static volatile WorldSounds current;
    /** Подписки на шину — один раз, по первой записи (поток клиента); дальше всё решает {@link #current}. */
    private static boolean subscribed;

    private final Writer out;
    private final UUID recorder;
    private boolean closed;
    // ---- дальше — только поток сервера
    /** Состояние случайного источника звуков мира после прошлого звука: не сдвинулось — зерно не его. */
    private final Map<Level, Long> lastRandom = new IdentityHashMap<>();
    /** Запас тревоги снаряда на прошлом тике (≥ 0 — тревога ещё впереди). */
    private final Map<UUID, Integer> sirenLead = new HashMap<>();
    private final Set<Integer> nukes = new HashSet<>();
    private final Set<Long> outages = new HashSet<>();
    /** Первый тик сервера записи: что уже было до неё, не пишется. */
    private boolean primed;

    private WorldSounds(Path file, UUID recorder) {
        this.recorder = recorder;
        try {
            Files.createDirectories(file.getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Начать журнал плана (поток клиента, сразу после начала записи повтора). */
    static void start(Path file, UUID recorder, int written) {
        stop();
        if (!subscribed) {
            subscribed = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayLevelSoundEvent.AtPosition.class, WorldSounds::onPosition);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayLevelSoundEvent.AtEntity.class, WorldSounds::onEntity);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerTickEvent.Post.class, WorldSounds::onServerTick);
        }
        replayTick = written;
        current = new WorldSounds(file, recorder);
    }

    /**
     * Конец тика клиента записи ({@code ClientTickEvent.Post}, тик повтора {@code written}): запись кончает тик следом,
     * и всё, что придёт клиенту до конца следующего, ляжет в {@code written + 1}.
     */
    static void clientTick(int written) {
        if (current != null && written >= 0) replayTick = written + 1;
    }

    /** Закрыть журнал (конец записи плана). */
    static void stop() {
        WorldSounds w = current;
        current = null;
        replayTick = -1;
        if (w != null) w.close();
    }

    private synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void write(String line) {
        if (closed) return;
        try {
            out.write(line);
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nullable
    private ServerPlayer recorder(MinecraftServer server) {
        return server.getPlayerList().getPlayer(recorder);
    }

    // ================================================================ звуки

    private static void onPosition(PlayLevelSoundEvent.AtPosition e) {
        WorldSounds w = current;
        // звук клиента (та же шина в одном процессе) — не сервера; до первого тика записи — не в повторе
        if (w == null || replayTick < 0 || !(e.getLevel() instanceof ServerLevel level) || e.getSound() == null) return;
        Vec3 p = e.getPosition();
        w.sound(level, e, p, null);
    }

    private static void onEntity(PlayLevelSoundEvent.AtEntity e) {
        WorldSounds w = current;
        if (w == null || replayTick < 0 || e.getEntity() == null || !(e.getEntity().level() instanceof ServerLevel level) || e.getSound() == null) return;
        Entity entity = e.getEntity();
        w.sound(level, e, entity.position(), entity);
    }

    private void sound(ServerLevel level, PlayLevelSoundEvent e, Vec3 p, @Nullable Entity entity) {
        SoundEvent event = e.getSound().value();
        float volume = e.getNewVolume(), pitch = e.getNewPitch();
        // так шлёт ServerLevel.playSeededSound: всем, кроме того, у кого звук свой, ближе этого (строго)
        double send = event.getRange(volume);
        ServerPlayer rec = recorder(level.getServer());
        boolean heard = rec != null && rec.level() == level && rec.position().distanceToSqr(p) < send * send;
        if (heard && entity != null) {
            // звук сущности клиент играет, только если сущность у него есть (ClientPacketListener.handleSoundEntityEvent)
            heard = rec == entity || level.getChunkSource().chunkMap.getPlayersWatching(entity).contains(rec);
        }
        Long seed = seed(level);
        StringBuilder b = new StringBuilder(256);
        b.append(String.format(Locale.ROOT, "{\"t\":%d,\"gt\":%d,\"sound\":\"%s\",\"source\":\"%s\",\"pos\":[%.3f,%.3f,%.3f],"
                        + "\"volume\":%.4f,\"pitch\":%.4f", replayTick, level.getGameTime(), event.getLocation(), e.getSource().getName(),
                p.x, p.y, p.z, volume, pitch));
        float vf = 1, attenuation = 16;
        if (seed != null) {
            b.append(",\"seed\":").append(seed.longValue());
            Variant v = variant(event.getLocation(), seed);
            if (v != null) {
                vf = v.volume;
                attenuation = v.attenuation;
                b.append(String.format(Locale.ROOT, ",\"file\":\"%s\",\"attenuation\":%d,\"fv\":%.4f,\"fp\":%.4f", v.file, v.attenuation, v.volume, v.pitch));
            }
        }
        // дальность линейного затухания у клиента, как у SoundEngine.play: max(громкость звука, 1) × затухание варианта
        b.append(String.format(Locale.ROOT, ",\"range\":%.1f,\"send\":%.1f,\"heard\":%b", Math.max(volume * vf, 1f) * attenuation, send, heard));
        String by = entity instanceof Player pl ? pl.getGameProfile().getName() : playerAt(level, p);
        if (by != null) b.append(",\"by\":\"").append(by).append('"');
        if (entity != null) {
            b.append(String.format(Locale.ROOT, ",\"entity\":{\"type\":\"%s\",\"uuid\":\"%s\"}", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                    entity.getUUID()));
        }
        write(b.append('}').toString());
    }

    /**
     * Игрок, у которого звук: на том же месте до последнего знака (выстрел Point Blank — {@code SoundFeature.playFireSound}
     * с места стрелка, шаги и удары — с места игрока).
     */
    @Nullable
    private static String playerAt(ServerLevel level, Vec3 p) {
        for (ServerPlayer pl : level.players()) {
            if (pl.getX() == p.x && pl.getY() == p.y && pl.getZ() == p.z) return pl.getGameProfile().getName();
        }
        return null;
    }

    // ---------------------------------------------------------------- зерно звука

    private static final long MULTIPLIER = 25214903917L, INCREMENT = 11L, MASK = (1L << 48) - 1;
    /** Обратное к множителю по модулю 2⁴⁸: шаг генератора назад. */
    private static final long INVERSE = BigInteger.valueOf(MULTIPLIER).modInverse(BigInteger.ONE.shiftLeft(48)).longValue();
    @Nullable
    private static Field threadSafeRandom, randomSeed;
    private static boolean randomBroken;

    /**
     * Зерно звука, которое только что вытянул {@code Level.playSound} ({@code threadSafeRandom.nextLong()}), — по
     * состоянию генератора после него. Источник не сдвинулся с прошлого звука — зерно дал вызывающий
     * ({@code playSeededSound} напрямую): его не узнать, null.
     */
    @Nullable
    private Long seed(ServerLevel level) {
        AtomicLong state = randomState(level);
        if (state == null) return null;
        long now = state.get();
        Long before = lastRandom.put(level, now);
        if (before != null && before == now) return null;
        return lastLong(now);
    }

    /** {@code nextLong} генератора Java (LCG 48 бит), кончившего в состоянии {@code state}: два шага next(32). */
    static long lastLong(long state) {
        long previous = (state - INCREMENT) * INVERSE & MASK;
        int hi = (int) (previous >>> 16), lo = (int) (state >>> 16);
        return ((long) hi << 32) + lo;
    }

    @Nullable
    private static AtomicLong randomState(Level level) {
        if (randomBroken) return null;
        try {
            if (threadSafeRandom == null) {
                threadSafeRandom = Level.class.getDeclaredField("threadSafeRandom");
                threadSafeRandom.setAccessible(true);
            }
            Object random = threadSafeRandom.get(level);
            if (randomSeed == null) {
                randomSeed = random.getClass().getDeclaredField("seed");
                randomSeed.setAccessible(true);
            }
            return (AtomicLong) randomSeed.get(random);
        } catch (ReflectiveOperationException | RuntimeException e) {
            randomBroken = true;
            Incoming.LOG.warn("TRAILER звуки мира: зерна звуков не узнать ({}), вариант записи выберет монтаж", e.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- вариант записи

    record Variant(String file, int attenuation, float volume, float pitch) {}

    /**
     * Запись, которую клиент выберет из вариантов события по зерну пакета: {@code ClientLevel.playSeededSound} даёт звуку
     * {@code RandomSource.create(seed)}, движок берёт вариант {@code WeighedSoundEvents.getSound} и множители громкости и
     * тона варианта тем же источником. Звуки клиента после загрузки ресурсов не меняются: читать из потока сервера можно;
     * нет клиента или события — null (монтаж выберет по sounds.json сам).
     */
    @Nullable
    private static Variant variant(ResourceLocation event, long seed) {
        try {
            Minecraft mc = Minecraft.getInstance();
            WeighedSoundEvents events = mc == null ? null : mc.getSoundManager().getSoundEvent(event);
            if (events == null) return null;
            RandomSource random = RandomSource.create(seed);
            Sound s = events.getSound(random);
            if (s == null || s.getLocation().getPath().equals("empty")) return null;
            float vf = s.getVolume().sample(random), pf = s.getPitch().sample(random);
            return new Variant(s.getPath().toString(), s.getAttenuationDistance(), vf, pf);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================ пакеты мода и полёты

    private static void onServerTick(ServerTickEvent.Post e) {
        WorldSounds w = current;
        if (w == null || replayTick < 0) return;
        MinecraftServer server = e.getServer();
        ServerPlayer rec = w.recorder(server);
        boolean flights = server.getTickCount() % FLIGHT_EVERY == 0;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                w.triggers(level, rec);
                if (flights && w.primed) w.flights(level, rec);
            } catch (RuntimeException ex) {
                Incoming.LOG.warn("TRAILER звуки мира: {} в {}", ex.toString(), level.dimension().location());
            }
        }
        w.primed = true;
    }

    /** Пакеты мода, что уходят только тем, кто рядом: новое с прошлого тика. */
    private void triggers(ServerLevel level, @Nullable ServerPlayer rec) {
        long gt = level.getGameTime();
        // тревога у цели: снаряд снял свой запас тревоги (StrikeProjectile.checkSiren → StrikeService.siren)
        Set<UUID> alive = new HashSet<>();
        for (StrikeProjectile p : StrikeWorld.projectiles(level)) {
            alive.add(p.getUUID());
            int lead = intField(p, SIREN_LEAD);
            Integer was = sirenLead.put(p.getUUID(), lead);
            if (primed && was != null && was >= 0 && lead < 0 && IncomingConfig.SERVER.siren.get()) {
                Vec3 at = sirenPoint(p);
                String kind = p.weapon().siren() == WeaponType.SirenKind.MISSILE ? "missile" : "air_raid";
                trigger(level, gt, "siren", at, StrikeService.ALERT_RADIUS, heardWithin(rec, level, at, StrikeService.ALERT_RADIUS, false),
                        String.format(Locale.ROOT, ",\"kind\":\"%s\",\"weapon\":\"%s\"", kind, p.weapon().getSerializedName()));
            }
        }
        sirenLead.keySet().retainAll(alive);
        // ядерная тревога: предупреждение всем в мире, тревога — у тех, кто в радиусе (NuclearStrikes.warning);
        // подрыв без полёта («nuke now») предупреждения не шлёт
        for (NuclearEvents.ScheduledStrike s : NuclearEvents.get(level).scheduled()) {
            if (!nukes.add(s.id()) || !primed || s.detonateTime() <= s.launchTime()) continue;
            double r = IncomingConfig.SERVER.nukeWarningRadius.get();
            trigger(level, gt, "nuke_warning", s.target(), r, heardWithin(rec, level, s.target(), r, true),
                    String.format(Locale.ROOT, ",\"id\":%d,\"detonate\":%d", s.id(), s.detonateTime()));
        }
        // подстанция выбита: новая авария от узла (Blackouts.knockOut); общая авария ядерного удара (узел −1) пакета не шлёт
        PowerGrid grid = PowerGrid.get(level);
        for (Outage o : grid.outages()) {
            long key = (long) o.id() << 32 | level.dimension().location().hashCode() & 0xFFFFFFFFL;
            if (!outages.add(key) || !primed || o.node() < 0) continue;
            Node n = grid.node(o.node());
            if (n == null) continue;
            Vec3 at = Vec3.atCenterOf(n.pos());
            double r = FAILURE_RANGE;
            trigger(level, gt, "grid_failure", at, r, heardWithin(rec, level, at, r, false), "");
        }
    }

    private void trigger(ServerLevel level, long gt, String kind, Vec3 at, double radius, boolean heard, String extra) {
        write(String.format(Locale.ROOT, "{\"t\":%d,\"gt\":%d,\"trigger\":\"%s\",\"dim\":\"%s\",\"pos\":[%.2f,%.2f,%.2f],\"radius\":%.1f,\"heard\":%b%s}",
                replayTick, gt, kind, level.dimension().location(), at.x, at.y, at.z, radius, heard, extra));
    }

    /** Игрок записи получил пакет с радиусом: {@code sendToPlayersNear} — строго ближе, ядерная тревога — не дальше. */
    private static boolean heardWithin(@Nullable ServerPlayer rec, ServerLevel level, Vec3 at, double r, boolean inclusive) {
        if (rec == null || rec.level() != level) return false;
        double d2 = rec.position().distanceToSqr(at);
        return inclusive ? d2 <= r * r : d2 < r * r;
    }

    /** Снаряды и зенитные ракеты мира: докуда их слышно и были ли они у игрока записи. */
    private void flights(ServerLevel level, @Nullable ServerPlayer rec) {
        List<StrikeProjectile> all = FarFlights.flights(level);
        List<Interceptor> sam = DefenseWorld.get(level).flights();
        if (all.isEmpty() && sam.isEmpty()) return;
        Set<UUID> sent = new HashSet<>();
        boolean here = rec != null && rec.level() == level;
        if (here) {
            // то, что сервер шлёт ему пакетом дальних полётов со звуком (FarFlights.onServerTick: место — ноги игрока)
            for (var f : FarFlights.sample(level, all, rec.position(), rec, false)) {
                if (f.audible()) sent.add(f.id());
            }
        }
        StringBuilder b = new StringBuilder(64 + all.size() * 80);
        b.append(String.format(Locale.ROOT, "{\"t\":%d,\"gt\":%d,\"dim\":\"%s\",\"flights\":[", replayTick, level.getGameTime(), level.dimension().location()));
        for (int i = 0; i < all.size(); i++) {
            StrikeProjectile p = all.get(i);
            boolean heard = here && (sent.contains(p.getUUID()) || !p.isVirtual() && watching(level, p, rec));
            if (i > 0) b.append(',');
            b.append(String.format(Locale.ROOT, "[\"%s\",\"%s\",%.2f,%.2f,%.2f,%.0f,%b]", p.getUUID(), p.weapon().getSerializedName(), p.getX(), p.getY(),
                    p.getZ(), Hearing.range(p), heard));
        }
        b.append("],\"interceptors\":[");
        for (int i = 0; i < sam.size(); i++) {
            Interceptor f = sam.get(i);
            InterceptorEntity view = f.view();
            boolean heard = here && view != null && watching(level, view, rec);
            Vec3 at = f.position();
            if (i > 0) b.append(',');
            b.append(String.format(Locale.ROOT, "[%.2f,%.2f,%.2f,%.0f,%b]", at.x, at.y, at.z, INTERCEPTOR_RANGE, heard));
        }
        write(b.append("]}").toString());
    }

    private static boolean watching(ServerLevel level, Entity e, ServerPlayer rec) {
        return level.getChunkSource().chunkMap.getPlayersWatching(e).contains(rec);
    }

    // ---------------------------------------------------------------- закрытое в моде (отражением: код мода не трогаем)

    private static final Field SIREN_LEAD = field(StrikeProjectile.class, "sirenLead");
    private static final Field TRACKER = field(StrikeProjectile.class, "tracker");
    /** Дальность пакета выбитой подстанции ({@code Blackouts.FAILURE_RANGE}). */
    private static final double FAILURE_RANGE = constant(Blackouts.class, "FAILURE_RANGE", 320);
    /** Докуда слышно мотор зенитной ракеты ({@code client.sound.InterceptorSound.RANGE}). */
    private static final double INTERCEPTOR_RANGE = constant("ua.zentix.incoming.client.sound.InterceptorSound", "RANGE", 400);

    @Nullable
    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Incoming.LOG.warn("TRAILER звуки мира: нет поля {}.{} — тревоги у цели в журнал не попадут", owner.getSimpleName(), name);
            return null;
        }
    }

    private static int intField(Object o, @Nullable Field f) {
        try {
            return f == null ? -1 : f.getInt(o);
        } catch (IllegalAccessException e) {
            return -1;
        }
    }

    /** Точка тревоги — цель, за которой следит снаряд (как у {@code checkSiren}); нет — точка прицела. */
    private static Vec3 sirenPoint(StrikeProjectile p) {
        try {
            if (TRACKER != null && TRACKER.get(p) instanceof TargetTracker t) return t.point();
        } catch (IllegalAccessException ignored) {
            // точка прицела ниже
        }
        return p.aimPoint();
    }

    private static double constant(Class<?> owner, String name, double fallback) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.getDouble(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    private static double constant(String owner, String name, double fallback) {
        try {
            return constant(Class.forName(owner), name, fallback);
        } catch (ClassNotFoundException e) {
            return fallback;
        }
    }
}
