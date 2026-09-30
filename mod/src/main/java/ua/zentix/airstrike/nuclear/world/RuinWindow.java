package ua.zentix.airstrike.nuclear.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Исходный мир вокруг чанка — сам чанк и 8 соседей (48×48 столбцов), каким он был до этого подрыва: у соседа, чьи
 * руины уже стоят, — старые блоки мест его плана, а огонь и текущая вода, которых там не было, — воздух. Чего нет
 * (сосед не загружен) — сплошной неломаемый массив. Координаты окна: {@code wx, wz} от 0 до 47, y — мира.
 * <p>
 * Помнит, по каким чанкам построено ({@link #current}): {@code identityHashCode} и счётчик изменений каждого.
 */
final class RuinWindow {
    static final int SIDE = 48;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Чего нет в окне: массив, который волна не ломает и который держит. */
    static final BlockState MASS = Blocks.BEDROCK.defaultBlockState();

    final ChunkPos centre;
    final int minY, maxY, x0, z0;
    private final LevelChunk[] chunks = new LevelChunk[9];
    private final LevelChunkSection[][] sections = new LevelChunkSection[9][];
    /** У стоящих руин: план (старые блоки его мест). */
    private final RuinPlan[] olds = new RuinPlan[9];
    private final boolean[] ruined = new boolean[9];
    private final int[] ids = new int[9];
    private final long[] edits = new long[9];
    /** Исходный верх столбца (верхний не-воздух) и верх опоры (как {@code MOTION_BLOCKING_NO_LEAVES} − 1); MIN — пусто. */
    private final int[] top = new int[SIDE * SIDE], solid = new int[SIDE * SIDE];

    RuinWindow(ServerLevel level, RuinContext ctx, ChunkPos centre) {
        this.centre = centre;
        this.x0 = centre.getMinBlockX() - 16;
        this.z0 = centre.getMinBlockZ() - 16;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
        for (int k = 0; k < 9; k++) {
            int cx = centre.x + k % 3 - 1, cz = centre.z + k / 3 - 1;
            LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
            chunks[k] = c;
            if (c == null) continue;
            long key = ChunkPos.asLong(cx, cz);
            sections[k] = c.getSections();
            ids[k] = System.identityHashCode(c);
            edits[k] = ctx.edits(key, c);
            RuinContext.Applied a = ctx.applied(key);
            if (a != null) {
                ruined[k] = true;
                olds[k] = a.plan();
            }
        }
        for (int k = 0; k < 9; k++) {
            LevelChunk c = chunks[k];
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = (bz + lz) * SIDE + bx + lx;
                    if (c == null) {
                        top[i] = solid[i] = maxY - 1;
                        continue;
                    }
                    int t = c.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz), s = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                    if (ruined[k]) {
                        // руины ниже исходного: верх — по старым блокам мест плана; выше исходного (огонь, завал на месте
                        // воздуха) — вниз до исходного блока
                        if (olds[k] != null) {
                            int[] o = olds[k].oldTops(minY);
                            t = Math.max(t, o[lz << 4 | lx]);
                            s = Math.max(s, o[256 + (lz << 4 | lx)]);
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
        int k = (wz >> 4) * 3 + (wx >> 4);
        LevelChunkSection[] s = sections[k];
        if (s == null) return MASS;
        int i = (y - minY) >> 4;
        if (ruined[k]) {
            RuinPlan plan = olds[k];
            if (plan != null) {
                BlockState o = plan.oldState(wx & 15, y, wz & 15, minY);
                if (o != null) return o;
            }
            BlockState now = s[i].getBlockState(wx & 15, y & 15, wz & 15);
            // последствия руин: огонь и растёкшаяся вода — на месте воздуха
            if (now.getBlock() instanceof BaseFireBlock || !now.getFluidState().isEmpty() && !now.getFluidState().isSource()) return AIR;
            return now;
        }
        return s[i].getBlockState(wx & 15, y & 15, wz & 15);
    }

    boolean has(int wx, int wz) {
        return wx >= 0 && wx < SIDE && wz >= 0 && wz < SIDE && chunks[(wz >> 4) * 3 + (wx >> 4)] != null;
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

    @Nullable
    LevelChunk chunk(int k) {
        return chunks[k];
    }

    /** Отпечаток окна: по каким чанкам построено. */
    Stamp stamp() {
        int mask = 0;
        for (int k = 0; k < 9; k++) if (ruined[k]) mask |= 1 << k;
        return new Stamp(ids.clone(), edits.clone(), mask);
    }

    /**
     * По каким чанкам окна построено (разлом, план): их {@code identityHashCode} (0 — чанка не было), счётчики изменений
     * ({@link RuinContext#edits}) и какие уже стояли в руинах.
     */
    record Stamp(int[] ids, long[] edits, int ruined) {
        /** Окно такое же, как при построении: те же чанки, и ничего в них не меняли, кроме руин. */
        boolean current(ServerLevel level, RuinContext ctx, ChunkPos pos) {
            for (int k = 0; k < 9; k++) {
                int cx = pos.x + k % 3 - 1, cz = pos.z + k / 3 - 1;
                long key = ChunkPos.asLong(cx, cz);
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null ? ids[k] != 0 : System.identityHashCode(c) != ids[k] || ctx.edits(key, c) != edits[k]) return false;
                // руины соседа встали после построения, а их старые блоки уже отпущены — исходный мир не прочитать
                RuinContext.Applied a = ctx.applied(key);
                if (c != null && (ruined & 1 << k) == 0 && a != null && a.plan() == null) return false;
            }
            return true;
        }
    }
}
