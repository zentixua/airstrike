package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Зона за волной, которой нет на диске целой, — в мир ради LOD Distant Horizons вдали ({@link FarLods}). Копию с руинами
 * с диска можно собрать только для чанка, сгенерированного до конца ({@code Status} — {@code minecraft:full}) и с таким
 * же окном 5×5 вокруг (план руин читает соседей): у края исследованного мира на диске лежат лишь недогенерированные
 * чанки (кольцо 1 — до света, 2 — до пещер, 3 — биомы без рельефа, дальше — начала структур, обычно без блоков) или их
 * нет вовсе, и LOD таких мест DH строит своим генератором — целыми. Так же не годятся чанки, которые ваниль обновляет
 * только при загрузке (формат до 1.18, догенерация под нулём у мира, поднятого с 1.17). Игра Артёма 01.10.2026 (15 кт
 * у земли): из 14323 чанков тяжёлой зоны не в памяти 6273 сами не годились для копии; копии его мира: у удара №5 из
 * 21025 чанков квадрата 7901 нет на диске и 2282 — недогенерированные без блоков. Всё это для зоны одно и то же
 * ({@link DiskStatus#whole}).
 * <p>
 * Такой чанк, до которого дошла волна, берётся в мир квадратом 5×5 (те же квадраты, что у зоны за волной
 * {@link NuclearPrep}; вместе с квадратами, которые задевает его окно руин {@link RuinPlanner#REACH}: их чанки, целые
 * на диске, плана с диска не получили и подготовкой не грузятся) тикетом загрузки без тика ({@link AreaLoader},
 * {@code ticks = false}): ваниль догенерирует его в фоне, синхронно ничего не грузится. Руины встают обычным путём
 * загрузки — {@code ChunkEvent.Load}, очередь руин ({@link ScarQueue}: отметки {@code chunk_scar} нет), запись плана
 * ({@link RuinPlan#apply}) и его отметка в {@link DhUpdates}; квадрат отпускается, когда руины всех его чанков стоят
 * (или ждут только соседей — тогда их держат свои тикеты, {@link ScarQueue#holdForTile}) и DH их уже получил. Мир
 * генерируется так же, как если бы игрок дошёл туда сам, — только раньше: чанк, загруженный потом, руины уже несёт.
 * <p>
 * Только при DH и только у игроков (дальность DH {@link FarLods#FAR_CHUNKS}): без них LOD никто не увидит, а
 * генерировать мир незачем; настройка мира {@code nuclear.far_zone} выключает это совсем. Не больше {@link #LOADING}
 * квадратов в генерации и {@link #HELD} всего; квадрат, который не сгенерировался за {@link #LOAD_LIMIT} тиков (чанк
 * так и не стал полным) или не отпущен за {@link #HOLD_LIMIT}, отпускается без руин — зона идёт дальше. Отпускается не
 * больше {@link #RELEASE_PER_TICK} за тик (выгрузка пишет чанки на диск в тике сервера), из очереди за тик
 * просматривается не больше {@link #START_VISITS} квадратов: работа тика не зависит от размера зоны. Состояние не
 * сохраняется; остановка сервера, «Отбой», уход DH и выключенная настройка отпускают все квадраты ({@link #clear}) —
 * остановка до {@code util/StopDrain}.
 */
final class FarZone {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_far_zone", Comparator.<UUID>naturalOrder());
    /** Квадраты — как у зоны за волной ({@link NuclearPrep}): 5×5 чанков, тикет — сам квадрат. */
    static final int TILE_RADIUS = 2, TILE = TILE_RADIUS * 2 + 1;
    /** Квадратов в генерации сразу и всего взятых (в генерации и с руинами в очереди). */
    static final int LOADING = 2, HELD = 4;
    /** Сколько квадратов отпускать за тик: каждый — до 25 выгрузок чанков с записью на диск в этом тике сервера. */
    private static final int RELEASE_PER_TICK = 2;
    /** Квадратов из очереди за тик просматривается, не больше (брошенные без игроков — тоже). */
    static final int START_VISITS = 64;
    /** Генерация квадрата — не дольше, тиков: чанк, который так и не стал полным, не держит слот. */
    static final int LOAD_LIMIT = 2400;
    /** Квадрат держится всего не дольше, тиков: руины, которые не встают, не держат слот вечно. */
    static final int HOLD_LIMIT = 6000;

    private static final class Tile {
        final ChunkPos centre;
        /** Все 25 чанков полные (генерация кончилась). */
        boolean ready;
        long heldAt;

        Tile(ChunkPos centre) {
            this.centre = centre;
        }

        AreaLoader.Area area() {
            return new AreaLoader.Area(TYPE, centre, TILE_RADIUS, new UUID(0L, centre.toLong()), false);
        }
    }

    /** Середины всех квадратов, ждущих и взятых: квадрат в очередь дважды не встаёт. */
    private final LongOpenHashSet known = new LongOpenHashSet();
    /** Ждут слота — по порядку постановки (по порядку волны). */
    private final ArrayDeque<Tile> waiting = new ArrayDeque<>();
    /** Взяты: генерируются или ждут руин; не больше {@link #HELD}. */
    private final List<Tile> held = new ArrayList<>(HELD);
    /** Срок генерации (проверки ставят меньше). */
    private int loadLimit = LOAD_LIMIT;
    /** Для строки в лог и проверок: квадратов взято, отпущено, отпущено по сроку, брошено (игроки ушли). */
    private long taken, released, expired, dropped;
    /** Тиков генерации у сгенерированных квадратов и от взятия до отпуска у отпущенных — всего (средние в строку). */
    private long loadTicks, loaded, holdTicks;
    /** Самое большее квадратов в генерации разом (проверки). */
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
        add(tileOf(x, z));
        for (int dx : new int[]{-RuinPlanner.REACH, RuinPlanner.REACH}) {
            for (int dz : new int[]{-RuinPlanner.REACH, RuinPlanner.REACH}) add(tileOf(x + dx, z + dz));
        }
    }

    private void add(ChunkPos centre) {
        if (known.add(centre.toLong())) waiting.add(new Tile(centre));
    }

    boolean busy() {
        return !held.isEmpty() || !waiting.isEmpty();
    }

    void loadLimit(int ticks) {
        loadLimit = ticks;
    }

    /** Поток сервера, после {@link FarLods} (его отметки — раньше). Работа — по взятым квадратам и не больше {@link #START_VISITS} ждущих. */
    void tick(ServerLevel level) {
        if (held.isEmpty() && waiting.isEmpty()) return;
        long now = level.getGameTime();
        ScarQueue scars = NuclearWorld.get(level).scars();
        int loading = 0, releasedNow = 0;
        for (Iterator<Tile> it = held.iterator(); it.hasNext(); ) {
            Tile t = it.next();
            long age = now - t.heldAt;
            if (!t.ready && age > loadLimit || age > HOLD_LIMIT) {
                Airstrike.LOG.warn("LOD вдали: квадрат {} держался {} тиков ({}) — отпущен без руин всех чанков", t.centre, age,
                        t.ready ? "руины не встали" : "генерация не кончилась");
                expired++;
                it.remove();
                release(level, t);
                continue;
            }
            if (!t.ready && ready(level, t)) {
                t.ready = true;
                loadTicks += age;
                loaded++;
            }
            if (t.ready && releasedNow < RELEASE_PER_TICK && finished(level, scars, t)) {
                releasedNow++;
                released++;
                holdTicks += age;
                it.remove();
                release(level, t);
                continue;
            }
            if (!t.ready) loading++;
        }
        // новые — по порядку, пока есть слоты; квадрат, от которого ушли игроки, не нужен
        for (int n = 0; n < START_VISITS && !waiting.isEmpty() && loading < LOADING && held.size() < HELD; n++) {
            Tile t = waiting.poll();
            if (!FarLods.nearPlayer(level, t.centre.toLong())) {
                known.remove(t.centre.toLong());
                dropped++;
                continue;
            }
            StrikeWorld.get(level).areas().hold(level, t.area());
            t.heldAt = now;
            held.add(t);
            taken++;
            loading++;
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
        known.remove(t.centre.toLong());
    }

    /** «Отбой», DH ушёл, настройка выключена или остановка сервера: все квадраты отпущены, очередь пуста. */
    void clear(ServerLevel level) {
        for (Tile t : held) StrikeWorld.get(level).areas().release(level, t.area());
        held.clear();
        waiting.clear();
        known.clear();
    }

    /** Для строки в лог: квадратов ждёт, держится, взято, отпущено, по сроку, брошено; средние тики генерации и до отпуска. */
    String summary() {
        return "квадратов ждёт " + waiting.size() + ", держится " + held.size() + ", взято " + taken + ", отпущено " + released + " (по сроку " + expired
                + "), брошено без игроков " + dropped + "; генерация в среднем " + (loaded == 0 ? 0 : loadTicks / loaded) + " тиков, до отпуска "
                + (released == 0 ? 0 : holdTicks / released);
    }

    /** Для проверок: ждут, держатся, взято, отпущено, по сроку, брошено, самое большее в генерации разом. */
    long[] stats() {
        return new long[] {waiting.size(), held.size(), taken, released, expired, dropped, loadingPeak};
    }
}
