package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleBiFunction;

/**
 * Площадка сценария в мире GameTest (плоский мир): своё место на сетке вдали от площадок тестов и от других сценариев,
 * районы, которые тикают с первого тика полёта, и рельеф (холм, котлован).
 * <p>
 * Детерминизм. Место — одно и то же в любом прогоне (его задаёт ячейка сценария, а не порядок тестов): у полёта те же
 * числа с плавающей точкой и та же сетка чанков. Районы держит свой тикет региона, их чанки генерируются сразу
 * ({@code level.getChunk}), и полёт начинается, только когда все они тикают: где снаряд вернётся в мир и где уйдёт
 * из него, не зависит от скорости фоновой генерации. Районы снаряда (цель — {@code FlightTickets}, свои чанки —
 * {@code ChunkTickets}) лежат внутри районов площадки и ничего нового не грузят.
 */
final class ScenarioSite {
    private static final TicketType<ChunkPos> ZONE = TicketType.create("airstrike_test_scenario", Comparator.comparingLong(ChunkPos::toLong));
    /** Шаг сетки площадок: дальше самого длинного пути снаряда (B-2 — до 9,7 тыс. блоков) плюс телепорт цели. */
    private static final int SPACING = 24576;
    private static final int PER_ROW = 64;
    /** Начало сетки: далеко от точки появления мира и в пределах, где у координат ещё много знаков. */
    private static final int ORIGIN = 2_000_000;
    /** Район цели: сущности тикают в 11×11 чанках (±88 блоков), загружено 15×15 — шире районов снаряда у цели. */
    static final int TARGET_ZONE = 7;
    /** Район пусковой: тикает 5×5 чанков. */
    static final int LAUNCH_ZONE = 4;
    /** Коридор над холмом: полоса тикающих чанков в 3 шириной, загружено 9. */
    static final int CORRIDOR_ZONE = 4;

    private record Zone(ChunkPos centre, int distance) {}

    private final ServerLevel level;
    /** Середина площадки на земле: точка цели. */
    final Vec3 origin;
    private final List<Zone> zones = new ArrayList<>();

    ScenarioSite(ServerLevel level, int cell) {
        this.level = level;
        int cx = (ORIGIN + (cell % PER_ROW) * SPACING) >> 4, cz = (ORIGIN + (cell / PER_ROW) * SPACING) >> 4;
        ChunkPos c = new ChunkPos(cx, cz);
        this.origin = ground(c.getMiddleBlockX() + 0.5, c.getMiddleBlockZ() + 0.5, true);
    }

    /** Точка на земле плоского мира (без готового чанка — по генератору: высота та же). */
    Vec3 ground(double x, double z, boolean generator) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int y = !generator && Terrain.ready(level, bx >> 4, bz >> 4) ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, bx, bz)
                : level.getChunkSource().getGenerator().getBaseHeight(bx, bz, Heightmap.Types.MOTION_BLOCKING, level, level.getChunkSource().randomState());
        return new Vec3(x, y, z);
    }

    /** Район с центром в чанке точки: тикет региона уровня {@code 33 − distance}. */
    void zone(Vec3 at, int distance) {
        zones.add(new Zone(new ChunkPos(BlockPos.containing(at)), distance));
    }

    /** Район, взятый посреди полёта: тикет сразу, чанки догружает {@link InstantChunks} в конце тика. */
    void zoneNow(Vec3 at, int distance) {
        Zone z = new Zone(new ChunkPos(BlockPos.containing(at)), distance);
        zones.add(z);
        level.getChunkSource().addRegionTicket(ZONE, z.centre, z.distance, z.centre);
    }

    /** Коридор района с шагом в два чанка от {@code from} до {@code to}. */
    void corridor(Vec3 from, Vec3 to) {
        double length = from.subtract(to).horizontalDistance();
        for (double d = 0; d <= length; d += 32) zone(from.lerp(to, d / length), CORRIDOR_ZONE);
    }

    /** Взять районы и сгенерировать их чанки сразу. */
    void load() {
        for (Zone z : zones) level.getChunkSource().addRegionTicket(ZONE, z.centre, z.distance, z.centre);
        for (Zone z : zones) {
            for (int dx = -z.distance; dx <= z.distance; dx++)
                for (int dz = -z.distance; dz <= z.distance; dz++) level.getChunk(z.centre.x + dx, z.centre.z + dz);
        }
    }

    /** Все районы тикают (сущности), и чанки вокруг них готовы: полёт можно начинать. */
    boolean ticking() {
        for (Zone z : zones) {
            int r = z.distance - 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    ChunkPos c = new ChunkPos(z.centre.x + dx, z.centre.z + dz);
                    if (!Terrain.ready(level, c.x, c.z) || !level.isPositionEntityTicking(c.getMiddleBlockPosition(0))) return false;
                }
        }
        return true;
    }

    void release() {
        for (Zone z : zones) level.getChunkSource().removeRegionTicket(ZONE, z.centre, z.distance, z.centre);
    }

    /**
     * Холм поперёк курса: гребень в {@code place} блоках от цели против направления захода, высотой {@code height},
     * склоны 1 : 1,5; шириной по гребню 20 блоков. Только в пределах коридора (±48 блоков от курса).
     */
    void hill(Vec3 approach, double place, double height) {
        Vec3 crest = origin.subtract(approach.scale(place));
        double run = height * 1.5;
        relief(crest, run + 1, 48, (u, v) -> height - Math.max(Math.abs(u), Math.max(0, Math.abs(v) - 10)) / 1.5, approach);
    }

    /** Котлован: ровное дно радиусом {@code bottom} вокруг цели, склоны 1 : 2 до глубины {@code depth}, край 3 блока. */
    void quarry(double bottom, double depth) {
        double rim = bottom + depth * 2, outer = rim + 3;
        relief(origin, outer, outer, (u, v) -> {
            double r = Math.sqrt(u * u + v * v);
            return r > outer ? 0 : r >= rim ? depth : Math.max(0, (r - bottom) / 2);
        }, new Vec3(1, 0, 0));
    }

    /**
     * Рельеф высотой {@code h(u, v)} над землёй вокруг {@code centre} ({@code u} — вдоль {@code along}, {@code v} —
     * поперёк). Блоки только снаружи: столб от верха до уровня самого низкого соседа — стенки без щелей, внутри пусто
     * (карте высот, столкновениям и полёту над рельефом нужен только верх). Без обновления соседей.
     */
    private void relief(Vec3 centre, double halfU, double halfV, ToDoubleBiFunction<Double, Double> h, Vec3 along) {
        int span = (int) Math.ceil(Math.max(halfU, halfV) * Math.sqrt(2)) + 1;
        int ox = Mth.floor(centre.x), oz = Mth.floor(centre.z);
        int size = span * 2 + 1;
        int[][] height = new int[size][size];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                double x = i - span + 0.5, z = j - span + 0.5;
                double u = x * along.x + z * along.z, v = -x * along.z + z * along.x;
                height[i][j] = Math.abs(u) > halfU || Math.abs(v) > halfV ? 0 : Math.max(0, (int) Math.round(h.applyAsDouble(u, v)));
            }
        int groundY = Mth.floor(origin.y);
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                int top = height[i][j];
                if (top == 0) continue;
                int low = top;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int a = i + n[0], b = j + n[1];
                    low = Math.min(low, a < 0 || b < 0 || a >= size || b >= size ? 0 : height[a][b]);
                }
                int x = ox + i - span, z = oz + j - span;
                if (!Terrain.ready(level, x >> 4, z >> 4)) continue;
                for (int y = Math.max(0, low - 1); y < top; y++) {
                    level.setBlock(new BlockPos(x, groundY + y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
    }
}
