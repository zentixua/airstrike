package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Руины заранее — пока летит МБР (полторы минуты): тяжёлая зона (у земли от 5 psi) грузится с диска и для каждого её
 * чанка строится план руин ({@link RuinPlanner}). Когда волна доходит до чанка, остаётся только подменить секции
 * ({@link RuinPlan#apply}) — разрушения идут вместе с фронтом, а не догоняют его.
 * <p>
 * Грузится только то, что уже есть на диске полностью сгенерированным (поле {@code Status} чанка читается
 * в фоне через {@code chunkScanner}) или уже в памяти: ради удара мир не генерируется. Чанки держатся тикетами загрузки
 * без тика ({@link AreaLoader}, {@code ticks = false}) квадратами 5×5, ближние к цели первыми и не больше нескольких
 * квадратов в загрузке сразу; квадрат, где хоть один чанк не сгенерирован, не берётся (его чанки пройдут, как раньше,
 * при загрузке). Тикеты отпускаются, когда все готовые руины поставлены (или через минуту после подрыва), при отбое и
 * при ошибке. Ничего не сохраняется: после перезапуска подготовка начинается заново по запланированному удару.
 * <p>
 * Место подрыва и земля под ним — как у самого подрыва ({@link NuclearWarhead#geometry}); план не зависит ни от времени,
 * ни от сида подрыва — только от места, мощности и масштаба, и подходит подрыву, если тот случился там же.
 */
public final class NuclearPrep {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_nuclear_prep", Comparator.<UUID>naturalOrder());
    /** Давление у земли, до которого руины строятся заранее. */
    static final double PSI = 5;
    /** Квадраты загрузки: радиус тикета (5×5 чанков). */
    private static final int TILE_RADIUS = 2;
    private static final int TILE = TILE_RADIUS * 2 + 1;
    /** Квадратов в загрузке одновременно (не все сразу: игрокам тоже надо грузить мир). */
    private static final int LOADING_TILES = 6;
    /** Чтений заголовков чанков с диска одновременно. */
    private static final int SCANS = 64;
    /** Подготовка не начинается, если до подрыва меньше, тиков. */
    private static final int MIN_LEAD = 100;
    /** После подрыва тикеты держатся не дольше, тиков. */
    private static final int HOLD_AFTER = 1200;
    /** Как часто после подрыва отпускать квадраты, чьи руины уже стоят, тиков. */
    private static final int RELEASE_PERIOD = 10;
    /** Насколько место подрыва может отличаться от места плана, блоки. */
    private static final double SAME_PLACE = 8;

    private enum TileState { SCAN, SKIP, WAIT, LOADING, READY }

    private static final class Tile {
        final ChunkPos centre;
        TileState state = TileState.SCAN;
        /** Чанков квадрата, чей ответ с диска ещё не пришёл или не запрошен. */
        int unknown = TILE * TILE;
        boolean missing;

        Tile(ChunkPos centre) {
            this.centre = centre;
        }

        AreaLoader.Area area(int strike) {
            return new AreaLoader.Area(TYPE, centre, TILE_RADIUS, new UUID(strike, centre.toLong()), false);
        }
    }

    private static final class Prep {
        final int strike;
        final long detonateTime;
        @Nullable
        Detonation geometry;
        /** Чанки тяжёлой зоны, ближние первыми. */
        long[] order = new long[0];
        int nextPlan;
        final List<Tile> tiles = new ArrayList<>();
        final Long2ObjectOpenHashMap<Tile> tileOf = new Long2ObjectOpenHashMap<>();
        /** Очередь чанков на чтение с диска и ответы (приходят из потока ввода-вывода). */
        final LongArrayList toScan = new LongArrayList();
        int nextScan, scanning;
        final ConcurrentLinkedQueue<long[]> scanned = new ConcurrentLinkedQueue<>();
        final Long2ObjectOpenHashMap<RuinPlan> plans = new Long2ObjectOpenHashMap<>();
        final LongArrayList planned = new LongArrayList();
        /** Отдан подрыву с этим номером (тикеты ещё держатся); -1 — ещё летит. */
        int detonation = -1;
        long handedOff;
        boolean announced;

        Prep(int strike, long detonateTime) {
            this.strike = strike;
            this.detonateTime = detonateTime;
        }
    }

    private final List<Prep> preps = new ArrayList<>();
    private final WorkClock clock = WorkClock.decaying(0.5);

    /** Сколько руин готово заранее по всем ударам (проверки, статус). */
    public int plannedChunks() {
        return preps.stream().mapToInt(p -> p.plans.size()).sum();
    }

    /** Сколько квадратов держится тикетами (проверки). */
    public int heldTiles() {
        return (int) preps.stream().flatMap(p -> p.tiles.stream()).filter(t -> t.state == TileState.LOADING || t.state == TileState.READY).count();
    }

    // ---------------------------------------------------------------- тик

    /**
     * Подготовка под бюджетом: свой срок ({@code ms} на тик) и общий бюджет ядерной работы.
     *
     * @param shared общие часы тика (уже запущены)
     */
    public void tick(ServerLevel level, NuclearEvents events, ScarQueue scars, WorkClock shared) {
        long now = level.getGameTime();
        int ms = AirstrikeConfig.SERVER.nukePrepMsPerTick.get();
        // новые удары: подготовка с пуска
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            if (ms <= 0 || !AirstrikeConfig.SERVER.nukeBlockDamage.get()) break;
            if (s.detonateTime() - now < MIN_LEAD || preps.stream().anyMatch(p -> p.strike == s.id())) continue;
            preps.add(new Prep(s.id(), s.detonateTime()));
        }
        clock.start(Math.max(1, ms) * 1_000_000L);
        for (Iterator<Prep> it = preps.iterator(); it.hasNext(); ) {
            Prep p = it.next();
            try {
                if (p.detonation >= 0) {
                    // отдан подрыву: держим, пока очередь не поставит все готовые руины
                    if (!scars.hasPrepared(p.detonation) || now - p.handedOff > HOLD_AFTER) {
                        release(level, p);
                        scars.dropPrepared(p.detonation);
                        it.remove();
                    } else if (now % RELEASE_PERIOD == 0) {
                        releaseDone(level, p, scars);
                    }
                    continue;
                }
                NuclearEvents.ScheduledStrike s = events.scheduled().stream().filter(x -> x.id() == p.strike).findFirst().orElse(null);
                if (s == null) {
                    // отбой или подрыв не там, где план
                    release(level, p);
                    it.remove();
                    continue;
                }
                work(level, s, p, shared);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Подготовка руин удара №{} упала с ошибкой; снята", p.strike, e);
                release(level, p);
                it.remove();
            }
        }
    }

    private void work(ServerLevel level, NuclearEvents.ScheduledStrike s, Prep p, WorkClock shared) {
        if (p.geometry == null) {
            // место подрыва — как у самого подрыва: земля под целью из готового чанка (его держит район цели с пуска)
            if (!Terrain.ready(level, BlockPos.containing(s.target()))) return;
            p.geometry = NuclearWarhead.geometry(level, s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target(), s.yieldKt(), s.airBurst(),
                    AirstrikeConfig.SERVER.nukeEffectsScale.get().floatValue());
            layout(p);
        }
        scan(level, p);
        load(level, p);
        plan(level, p, shared);
        if (!p.announced && p.nextPlan >= p.order.length) {
            p.announced = true;
            // строка для проверок и съёмки: руины удара готовы заранее
            long bytes = 0;
            for (RuinPlan plan : p.plans.values()) bytes += plan.bytes();
            Airstrike.LOG.info("Руины удара №{} готовы: {} чанков из {} (до подрыва {} с), планы {} МБ, квадратов {}", p.strike, p.plans.size(),
                    p.order.length, Math.max(0, p.detonateTime - level.getGameTime()) / 20, bytes >> 20, heldTiles(p));
        }
    }

    /** Радиус тяжёлой зоны у земли, блоки (бисекция по давлению, монотонному с расстоянием). */
    static double heavyRadius(Detonation d) {
        double lo = 0, hi = d.radiusMax();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            if (d.psi(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) >= PSI) lo = r;
            else hi = r;
        }
        return lo;
    }

    /** Чанки зоны по расстоянию и квадраты загрузки по расстоянию. */
    private static void layout(Prep p) {
        Detonation d = p.geometry;
        double radius = heavyRadius(d) + 16;
        int cx = Mth.floor(d.burst().x) >> 4, cz = Mth.floor(d.burst().z) >> 4, r = Mth.ceil(radius / 16) + 1;
        LongArrayList in = new LongArrayList();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                ChunkPos c = new ChunkPos(x, z);
                if (horizontal(c, d) <= radius) in.add(c.toLong());
            }
        }
        long[] order = in.toLongArray();
        p.order = java.util.Arrays.stream(order).boxed().sorted(Comparator.comparingDouble(c -> horizontal(new ChunkPos(c), d)))
                .mapToLong(Long::longValue).toArray();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long c : p.order) {
            ChunkPos pos = new ChunkPos(c);
            ChunkPos centre = new ChunkPos(Math.floorDiv(pos.x, TILE) * TILE + TILE_RADIUS, Math.floorDiv(pos.z, TILE) * TILE + TILE_RADIUS);
            if (!seen.add(centre.toLong())) continue;
            Tile t = new Tile(centre);
            p.tiles.add(t);
            for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                    long k = ChunkPos.asLong(centre.x + dx, centre.z + dz);
                    p.tileOf.put(k, t);
                    p.toScan.add(k);
                }
            }
        }
    }

    /** Горизонтальное расстояние от эпицентра до ближайшей точки чанка. */
    static double horizontal(ChunkPos p, Detonation d) {
        double x = Math.max(p.getMinBlockX(), Math.min(d.burst().x, p.getMaxBlockX() + 1));
        double z = Math.max(p.getMinBlockZ(), Math.min(d.burst().z, p.getMaxBlockZ() + 1));
        return Math.hypot(x - d.burst().x, z - d.burst().z);
    }

    /** Есть ли чанки на диске полностью сгенерированными: в памяти — сразу, остальные — чтением в фоне. */
    private static void scan(ServerLevel level, Prep p) {
        for (long[] r; (r = p.scanned.poll()) != null; ) {
            p.scanning--;
            answer(p, r[0], r[1] != 0);
        }
        while (p.nextScan < p.toScan.size() && p.scanning < SCANS) {
            long c = p.toScan.getLong(p.nextScan++);
            if (inMemory(level, c) != null) {
                answer(p, c, true);
                continue;
            }
            CollectFields status = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
            p.scanning++;
            ConcurrentLinkedQueue<long[]> out = p.scanned;
            level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(c), status).whenComplete((v, e) -> {
                boolean full = e == null && status.getResult() instanceof CompoundTag tag && "minecraft:full".equals(tag.getString("Status"));
                out.add(new long[]{c, full ? 1 : 0});
            });
        }
    }

    private static void answer(Prep p, long chunk, boolean full) {
        Tile t = p.tileOf.get(chunk);
        if (t == null) return;
        if (!full) t.missing = true;
        if (--t.unknown == 0 && t.state == TileState.SCAN) t.state = t.missing ? TileState.SKIP : TileState.WAIT;
    }

    /** Квадраты — ближние первыми, не больше нескольких в загрузке. */
    private static void load(ServerLevel level, Prep p) {
        int loading = 0;
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING) {
                if (ready(level, t)) t.state = TileState.READY;
                else loading++;
            }
        }
        for (Tile t : p.tiles) {
            if (loading >= LOADING_TILES) break;
            if (t.state == TileState.SCAN) break; // порядок — по расстоянию: дальние ждут, пока ближние не прочитаны
            if (t.state != TileState.WAIT) continue;
            StrikeWorld.get(level).areas().hold(level, t.area(p.strike));
            t.state = TileState.LOADING;
            loading++;
        }
    }

    private static boolean ready(ServerLevel level, Tile t) {
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) if (!Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) return false;
        }
        return true;
    }

    /** Руины чанков по порядку: готовый чанк — план; ждём только чанки квадратов, которые грузятся. */
    private void plan(ServerLevel level, Prep p, WorkClock shared) {
        while (p.nextPlan < p.order.length && clock.canStart() && shared.canStart()) {
            long c = p.order[p.nextPlan];
            Tile t = p.tileOf.get(c);
            LevelChunk chunk = Terrain.ready(level, ChunkPos.getX(c), ChunkPos.getZ(c)) ? level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) : null;
            if (chunk == null) {
                // не будет готов (не сгенерирован) — пропускаем; грузится — ждём
                if (t == null || t.state == TileState.SKIP) {
                    p.nextPlan++;
                    continue;
                }
                return;
            }
            long c0 = shared.begin();
            try {
                p.plans.put(c, RuinPlanner.plan(level, p.geometry, chunk));
                p.planned.add(c);
            } finally {
                clock.record(shared.end(c0));
            }
            p.nextPlan++;
        }
    }

    // ---------------------------------------------------------------- подрыв

    /**
     * Подрыв: готовые руины того же места, мощности и масштаба — ему (очередь ставит их чанки первыми). Тикеты держатся,
     * пока руины не поставлены.
     *
     * @return готовые руины и порядок их чанков; null — не готовили
     */
    @Nullable
    public Handoff handOff(Detonation d, long now) {
        for (Prep p : preps) {
            Detonation g = p.geometry;
            if (p.detonation >= 0 || g == null || g.yieldKt() != d.yieldKt() || g.surface() != d.surface() || g.scale() != d.scale()
                    || g.burst().distanceTo(d.burst()) > SAME_PLACE) continue;
            p.detonation = d.id();
            p.handedOff = now;
            Airstrike.LOG.info("Подрыв №{}: руины заранее — {} чанков из {}", d.id(), p.plans.size(), p.order.length);
            return new Handoff(p.plans, p.planned.toLongArray());
        }
        return null;
    }

    public record Handoff(Long2ObjectOpenHashMap<RuinPlan> plans, long[] order) {}

    private static int heldTiles(Prep p) {
        int n = 0;
        for (Tile t : p.tiles) if (t.state == TileState.LOADING || t.state == TileState.READY) n++;
        return n;
    }

    /**
     * После подрыва: квадрат, где руины всех чанков (и чанков вокруг него — им для подмены нужны соседи) уже стоят,
     * больше не держится — память под тысячи чанков тяжёлой зоны освобождается по ходу волны, а не через минуту.
     */
    private static void releaseDone(ServerLevel level, Prep p, ScarQueue scars) {
        for (Tile t : p.tiles) {
            if (t.state != TileState.READY && t.state != TileState.LOADING) continue;
            boolean done = true;
            for (int dx = -TILE_RADIUS - 1; done && dx <= TILE_RADIUS + 1; dx++) {
                for (int dz = -TILE_RADIUS - 1; done && dz <= TILE_RADIUS + 1; dz++) {
                    long c = ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz);
                    done = !scars.pendingPlan(p.detonation, c) && !scars.queued(c);
                }
            }
            if (!done) continue;
            StrikeWorld.get(level).areas().release(level, t.area(p.strike));
            t.state = TileState.SKIP;
        }
    }

    private static void release(ServerLevel level, Prep p) {
        for (Tile t : p.tiles) {
            if (t.state == TileState.LOADING || t.state == TileState.READY) StrikeWorld.get(level).areas().release(level, t.area(p.strike));
        }
        p.tiles.clear();
    }

    /** Отбой и выгрузка мира: все тикеты отпущены. */
    public void clear(ServerLevel level) {
        preps.forEach(p -> release(level, p));
        preps.clear();
    }

    @Nullable
    private static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }
}
