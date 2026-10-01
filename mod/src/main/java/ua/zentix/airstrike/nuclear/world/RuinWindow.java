package ua.zentix.airstrike.nuclear.world;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Исходный мир вокруг чанка — сам чанк и 8 соседей (48×48 столбцов), каким он был до этого подрыва: у соседа, чьи
 * руины уже стоят, — старые блоки мест его плана, а огонь и текущая вода, которых там не было, — воздух. Чего нет
 * (сосед не загружен) — сплошной неломаемый массив. Координаты окна: {@code wx, wz} от 0 до 47, y — мира.
 * <p>
 * Строится из снимков чанков ({@link ChunkShot}), в любом потоке: мир окно не читает. Помнит, по каким снимкам построено
 * ({@link #stamp}).
 */
final class RuinWindow {
    static final int SIDE = 48;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Чего нет в окне: массив, который волна не ломает и который держит. */
    static final BlockState MASS = Blocks.BEDROCK.defaultBlockState();

    final ChunkPos centre;
    final int minY, maxY, x0, z0;
    final Blast.PropsView view;
    private final Blast.Props airProps, massProps;
    private final ChunkShot[] shots;
    /** Свойства мест по секциям чанков окна: {@code k * sections + секция} (из снимков, по мере чтения). */
    private final ChunkShot.Sec[] props;
    private final int sections;
    /** Исходный верх столбца (верхний не-воздух) и верх опоры (как {@code MOTION_BLOCKING_NO_LEAVES} − 1); MIN — пусто. */
    private final int[] top = new int[SIDE * SIDE], solid = new int[SIDE * SIDE];

    /**
     * @param shots снимки чанка и соседей (номер {@code k = (dz + 1) * 3 + dx + 1}); null — чанка нет
     */
    RuinWindow(ChunkPos centre, int minY, int maxY, ChunkShot[] shots, Blast.PropsView view) {
        this.centre = centre;
        this.x0 = centre.getMinBlockX() - 16;
        this.z0 = centre.getMinBlockZ() - 16;
        this.minY = minY;
        this.maxY = maxY;
        this.shots = shots;
        this.view = view;
        this.airProps = view.get(AIR);
        this.massProps = view.get(MASS);
        this.sections = (maxY - minY) >> 4;
        this.props = new ChunkShot.Sec[9 * sections];
        for (int k = 0; k < 9; k++) {
            ChunkShot c = shots[k];
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = (bz + lz) * SIDE + bx + lx;
                    if (c == null) {
                        top[i] = solid[i] = maxY - 1;
                        continue;
                    }
                    int t = c.surface[lz << 4 | lx], s = c.solid[lz << 4 | lx];
                    if (c.ruined) {
                        // руины ниже исходного: верх — по старым блокам мест плана; выше исходного (огонь, завал на месте
                        // воздуха) — вниз до исходного блока
                        if (c.oldTops != null) {
                            t = Math.max(t, c.oldTops[lz << 4 | lx]);
                            s = Math.max(s, c.oldTops[256 + (lz << 4 | lx)]);
                        }
                        int wx = bx + lx, wz = bz + lz;
                        while (t >= minY && get(wx, t, wz).isAir()) t--;
                        while (s >= minY && !Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(get(wx, s, wz))) s--;
                    }
                    top[i] = t < minY ? Integer.MIN_VALUE : t;
                    solid[i] = s < minY ? Integer.MIN_VALUE : s;
                }
            }
        }
    }

    /** Исходное состояние места окна. */
    BlockState get(int wx, int y, int wz) {
        if (y < minY || y >= maxY) return AIR;
        if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return MASS;
        ChunkShot s = shots[(wz >> 4) * 3 + (wx >> 4)];
        return s == null ? MASS : s.get(wx & 15, y, wz & 15);
    }

    /** Свойства исходного блока места окна. */
    Blast.Props props(int wx, int y, int wz) {
        if (y < minY || y >= maxY) return airProps;
        if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return massProps;
        int k = (wz >> 4) * 3 + (wx >> 4);
        ChunkShot s = shots[k];
        if (s == null) return massProps;
        int sec = (y - minY) >> 4, slot = k * sections + sec;
        ChunkShot.Sec p = props[slot];
        if (p == null) p = props[slot] = s.props(sec, view);
        return p.get((y & 15) << 8 | (wz & 15) << 4 | wx & 15);
    }

    /** Исходный блок места — воздух. */
    boolean air(int wx, int y, int wz) {
        return props(wx, y, wz).air();
    }

    /** Может ли в секции {@code section} (номер от низа мира) чанка {@code k} окна быть состояние из {@code test}. */
    boolean mayHave(int k, int section, java.util.function.Predicate<BlockState> test) {
        ChunkShot s = shots[k];
        return s != null && s.mayHave(section, test);
    }

    boolean has(int wx, int wz) {
        return wx >= 0 && wx < SIDE && wz >= 0 && wz < SIDE && shots[(wz >> 4) * 3 + (wx >> 4)] != null;
    }

    /** Исходный верх столбца (верхний не-воздух), {@code Integer.MIN_VALUE} — пустой столбец; вне окна — до неба. */
    int top(int wx, int wz) {
        if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return maxY - 1;
        return top[wz * SIDE + wx];
    }

    /** Исходный верх опоры без листвы. */
    int solid(int wx, int wz) {
        return solid[wz * SIDE + wx];
    }

    /** Есть ли в окне чанк {@code k}. */
    boolean present(int k) {
        return shots[k] != null;
    }

    /** По каким снимкам окно построено. */
    Stamp stamp() {
        return Stamp.of(shots);
    }

    /** По каким снимкам окна построено (разлом, план): их номера ({@link ChunkShot#serial}; 0 — чанка не было). */
    record Stamp(long[] serials) {
        static Stamp of(ChunkShot[] shots) {
            long[] serials = new long[9];
            for (int k = 0; k < 9; k++) if (shots[k] != null) serials[k] = shots[k].serial;
            return new Stamp(serials);
        }

        /** Построено по тем же снимкам. */
        boolean same(Stamp o) {
            return java.util.Arrays.equals(serials, o.serials);
        }
    }
}
