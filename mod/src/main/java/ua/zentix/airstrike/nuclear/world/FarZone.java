package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Зона за волной, которой нет на диске целой, — в мир ради LOD Distant Horizons вдали ({@link FarLods}). Копию с руинами
 * с диска можно собрать только для чанка, сгенерированного до конца ({@code Status} — {@code minecraft:full}) и с таким
 * же окном 5×5 вокруг (план руин читает соседей): у края исследованного мира на диске лежат лишь недогенерированные
 * чанки (кольцо 1 — до света, 2 — до пещер, 3 — биомы без рельефа, дальше — начала структур) или их нет вовсе, и LOD
 * таких мест DH строит своим генератором — целыми. Так же не годятся чанки, которые ваниль обновляет только при загрузке
 * (формат до 1.18, догенерация под нулём у мира, поднятого с 1.17). Игра Артёма 01.10.2026 (15 кт у земли): из 14323
 * чанков тяжёлой зоны не в памяти 6273 сами не годились для копии.
 * <p>
 * Такой чанк, до которого дошла волна, берётся в мир квадратом 5×5 (те же квадраты, что у зоны за волной
 * {@link NuclearPrep}; вместе с квадратами, которые задевает его окно руин {@link RuinPlanner#REACH}: их чанки, целые
 * на диске, плана с диска не получили и подготовкой не грузятся) тикетом загрузки без тика ({@link AreaLoader},
 * {@code ticks = false}): ваниль догенерирует его в фоне. Руины встают обычным путём загрузки — {@code ChunkEvent.Load},
 * очередь руин ({@link ScarQueue}: отметки {@code chunk_scar} нет), запись плана ({@link RuinPlan#apply}) и его отметка
 * в {@link DhUpdates}; квадрат отпускается, когда руины всех его чанков стоят (или ждут только соседей — тогда их держат
 * свои тикеты, {@link ScarQueue#holdForTile}) и DH их уже получил. Мир генерируется так же, как если бы игрок дошёл
 * туда сам, — только раньше: чанк, загруженный потом, руины уже несёт.
 * <p>
 * Только при DH и только у игроков (дальность DH {@link FarLods#FAR_CHUNKS}): без них LOD никто не увидит, а
 * генерировать мир незачем; настройка мира {@code nuclear.far_zone} выключает это совсем. Не больше {@link #LOADING} квадратов в загрузке и {@link #HELD} всего, отпускается не больше
 * {@link #RELEASE_PER_TICK} за тик (выгрузка пишет чанки на диск в тике сервера). Состояние не сохраняется; остановка
 * сервера и «Отбой» отпускают все квадраты ({@link #clear}) до {@code util/StopDrain}.
 */
final class FarZone {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_far_zone", Comparator.<UUID>naturalOrder());
    /** Квадраты — как у зоны за волной ({@link NuclearPrep}): 5×5 чанков, тикет — сам квадрат. */
    static final int TILE_RADIUS = 2, TILE = TILE_RADIUS * 2 + 1;
    /** Квадратов в загрузке (генерации) сразу и всего взятых (в загрузке и с руинами в очереди). */
    static final int LOADING = 2, HELD = 4;
    /** Сколько квадратов отпускать за тик: каждый — до 25 выгрузок чанков с записью на диск в этом тике сервера. */
    private static final int RELEASE_PER_TICK = 2;
    /** Квадрат держится не дольше, тиков: генерация или руины, которые не встают, не держат слот вечно. */
    static final int HOLD_LIMIT = 6000;

    private enum State { WAIT, LOADING, READY }

    private static final class Tile {
        final ChunkPos centre;
        State state = State.WAIT;
        long heldAt;

        Tile(ChunkPos centre) {
            this.centre = centre;
        }

        AreaLoader.Area area() {
            return new AreaLoader.Area(TYPE, centre, TILE_RADIUS, new UUID(0L, centre.toLong()), false);
        }
    }

    /** Квадраты по порядку постановки (по порядку волны): ждут, грузятся, ждут руин. */
    private final Long2ObjectLinkedOpenHashMap<Tile> tiles = new Long2ObjectLinkedOpenHashMap<>();
    /** Для строки в лог и проверок: квадратов взято, отпущено, отпущено по сроку, брошено (игроки ушли). */
    private long taken, released, expired, dropped;
    /** Самое большее квадратов в загрузке разом (проверки). */
    private int loadingPeak;

    /** Центр квадрата 5×5, в который входит чанк. */
    static ChunkPos tileOf(int x, int z) {
        return new ChunkPos(Math.floorDiv(x, TILE) * TILE + TILE_RADIUS, Math.floorDiv(z, TILE) * TILE + TILE_RADIUS);
    }

    /**
     * Чанк зоны за волной, которого нет на диске целым: в очередь — его квадрат и квадраты, которые задевает его окно
     * руин (их чанки, целые на диске, план с диска не получили).
     */
    void offer(long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        // окно не шире квадрата: его углы задевают все квадраты, которые задевает оно (свой — первым)
        ChunkPos own = tileOf(x, z);
        if (!tiles.containsKey(own.toLong())) tiles.put(own.toLong(), new Tile(own));
        for (int dx : new int[]{-RuinPlanner.REACH, RuinPlanner.REACH}) {
            for (int dz : new int[]{-RuinPlanner.REACH, RuinPlanner.REACH}) {
                ChunkPos c = tileOf(x + dx, z + dz);
                if (!tiles.containsKey(c.toLong())) tiles.put(c.toLong(), new Tile(c));
            }
        }
    }

    boolean busy() {
        return !tiles.isEmpty();
    }

    /** Поток сервера, после {@link FarLods} (его отметки — раньше). */
    void tick(ServerLevel level) {
        if (tiles.isEmpty()) return;
        long now = level.getGameTime();
        ScarQueue scars = NuclearWorld.get(level).scars();
        int loading = 0, held = 0, releasedNow = 0;
        List<Tile> done = new ArrayList<>();
        for (Tile t : tiles.values()) {
            if (t.state == State.WAIT) continue;
            if (now - t.heldAt > HOLD_LIMIT) {
                Airstrike.LOG.warn("LOD вдали: квадрат {} держался {} тиков ({}) — отпущен без руин всех чанков", t.centre, now - t.heldAt,
                        t.state == State.LOADING ? "генерация не кончилась" : "руины не встали");
                expired++;
                done.add(t);
                continue;
            }
            if (t.state == State.LOADING && ready(level, t)) t.state = State.READY;
            if (t.state == State.READY && releasedNow < RELEASE_PER_TICK && finished(level, scars, t)) {
                releasedNow++;
                released++;
                done.add(t);
                continue;
            }
            held++;
            if (t.state == State.LOADING) loading++;
        }
        for (Tile t : done) release(level, t);
        // новые — по порядку, пока есть слоты; квадрат, от которого ушли игроки, не нужен
        for (var it = tiles.values().iterator(); it.hasNext() && loading < LOADING && held < HELD; ) {
            Tile t = it.next();
            if (t.state != State.WAIT) continue;
            if (!FarLods.nearPlayer(level, t.centre.toLong())) {
                it.remove();
                dropped++;
                continue;
            }
            StrikeWorld.get(level).areas().hold(level, t.area());
            t.state = State.LOADING;
            t.heldAt = now;
            taken++;
            loading++;
            held++;
        }
        loadingPeak = Math.max(loadingPeak, loading);
    }

    private static boolean ready(ServerLevel level, Tile t) {
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) if (!Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) return false;
        }
        return true;
    }

    /**
     * Руины всех чанков квадрата стоят и ушли в DH — или оставшиеся ждут только соседей (тогда их держат свои тикеты,
     * как у квадратов зоны за волной).
     */
    private static boolean finished(ServerLevel level, ScarQueue scars, Tile t) {
        int waiting = 0;
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                long c = ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz);
                if (scars.queued(c)) {
                    if (!scars.waitsNeighbours(c)) return false;
                    waiting++;
                } else if (DhUpdates.pending(level, c)) {
                    return false;
                }
            }
        }
        if (waiting > scars.tileHoldsLeft()) return false;
        if (waiting > 0) {
            for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) scars.holdForTile(level, ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz));
            }
        }
        return true;
    }

    private void release(ServerLevel level, Tile t) {
        StrikeWorld.get(level).areas().release(level, t.area());
        tiles.remove(t.centre.toLong());
    }

    /** «Отбой», DH ушёл или остановка сервера: все квадраты отпущены, очередь пуста. */
    void clear(ServerLevel level) {
        for (Tile t : tiles.values()) if (t.state != State.WAIT) StrikeWorld.get(level).areas().release(level, t.area());
        tiles.clear();
    }

    /** Для строки в лог: квадратов ждёт, держится, взято, отпущено, по сроку, брошено. */
    String summary() {
        long held = tiles.values().stream().filter(t -> t.state != State.WAIT).count();
        return "квадратов ждёт " + (tiles.size() - held) + ", держится " + held + ", взято " + taken + ", отпущено " + released + " (по сроку " + expired
                + "), брошено без игроков " + dropped;
    }

    /** Для проверок: ждут, держатся, взято, отпущено, по сроку, брошено, самое большее в загрузке разом. */
    long[] stats() {
        long held = tiles.values().stream().filter(t -> t.state != State.WAIT).count();
        return new long[] {tiles.size() - held, held, taken, released, expired, dropped, loadingPeak};
    }
}
