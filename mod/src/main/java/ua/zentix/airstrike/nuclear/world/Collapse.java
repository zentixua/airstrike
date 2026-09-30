package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import ua.zentix.airstrike.nuclear.Detonation;

/**
 * Руины чанка после разлома ({@link Blast}): второй физический процесс — <b>падает то, что потеряло опору</b>, — и его
 * последствия: листва без ствола, вода без дна, навесное без того, на чём висело.
 * <ul>
 * <li>Расстояние опоры до земли — обход 0-1 от низа окна (вверх 0, вбок и вниз +1, до {@link #C}): блок держится, пока
 * оно не больше его пролёта ({@link Blast.Props#span}); грунт в постройке (кладка без арматуры) — только если опора
 * не стала длиннее. Падает только то, что волна лишила опоры: {@code после > L}
 * и ({@code до ≤ L}, или {@code после ≥ до + 3} — дыра в перекрытии не режет щелей, или до удара опоры не было,
 * а блок касается сломанного) — природные своды и навесы, которые и так «висели», стоят.</li>
 * <li>Считается, только если волна сломала опорный блок, над которым или рядом с которым что-то уцелело; по окну
 * чанка (±16 блоков), а падает — в чанке (точно — на 8 блоков вокруг: пути опоры короче {@link #C}).</li>
 * <li>Листва держится за ствол (как в игре: не дальше 6 листьев), без ствола опадает.</li>
 * <li>Малая вода (бассейн, фонтан: до {@link #MAX_DRAIN} мест и 16 блоков в ширину), которой волна пробила дно или
 * стенку, стекает целиком; большая (река, море) затекает в пролом тиками жидкости.</li>
 * </ul>
 */
final class Collapse {
    static final int C = 8, INF = C + 1;
    private static final int SIDE = RuinWindow.SIDE, LO = 16, HI = 32;
    private static final int MAX_DRAIN = 4096, DRAIN_WIDTH = 16, LEAF_REACH = 6;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // буферы обхода (поток сервера, без повторного входа): растут и переиспользуются
    private static byte[] before = new byte[0], after = new byte[0];
    private static int[] ring = new int[0];

    final RuinWindow w;
    final Blast[] blasts = new Blast[9];
    final int yb, yt, h;
    final long[] gone;
    /** Вода, которая стекла. */
    final long[] drained;

    /** Результат по чанку: новое состояние места (ключ — {@link BlockPos#asLong}). */
    final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> changes = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** По столбцу чанка: сколько снесено блоков, дающих завал, и из них деревянных; земля после руин (MIN — нет). */
    final int[] removed = new int[256], wood = new int[256], ground = new int[256];
    /** Сломанная волной листва (место) — пожары от света. */
    final LongArrayList leaves = new LongArrayList();
    /** Жидкость у изменённых мест: ей тик (и в соседних чанках — у мест этого). */
    final LongArrayList fluidTicks = new LongArrayList();

    private final Detonation d;

    private Collapse(ServerLevel level, RuinContext ctx, ChunkPos pos) {
        this.w = new RuinWindow(level, ctx, pos);
        this.d = ctx.d;
        for (int k = 0; k < 9; k++) {
            if (w.chunk(k) == null) continue;
            blasts[k] = ctx.blast(level, new ChunkPos(pos.x + k % 3 - 1, pos.z + k / 3 - 1));
        }
        int low = Integer.MAX_VALUE, high = w.minY;
        for (int wz = 0; wz < SIDE; wz++) {
            for (int wx = 0; wx < SIDE; wx++) {
                int s = w.solid(wx, wz), t = w.top(wx, wz);
                if (s != Integer.MIN_VALUE) low = Math.min(low, s);
                if (t != Integer.MIN_VALUE) high = Math.max(high, t);
            }
        }
        // разлом бывает и ниже самой низкой опоры (пролом под водой, листва над оврагом)
        for (int k = 0; k < 9; k++) {
            if (blasts[k] == null || blasts[k].removed().length == 0) continue;
            int c = blasts[k].removed()[0];
            low = Math.min(low, w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15));
        }
        if (low == Integer.MAX_VALUE) low = high;
        this.yb = Math.max(w.minY, low - 16);
        this.yt = Math.min(w.maxY - 1, Math.max(high, yb) + 1);
        this.h = yt - yb + 1;
        this.gone = new long[(SIDE * SIDE * h + 63) >> 6];
        this.drained = new long[gone.length];
        for (int k = 0; k < 9; k++) {
            if (blasts[k] == null) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int c : blasts[k].removed()) {
                int y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                if (y < yb || y > yt) continue;
                set(gone, idx(bx + (c & 15), y, bz + ((c >> 4) & 15)));
            }
        }
    }

    static Collapse solve(ServerLevel level, RuinContext ctx, ChunkPos pos) {
        Collapse c = new Collapse(level, ctx, pos);
        c.run();
        return c;
    }

    Blast blast() {
        return blasts[4];
    }

    // ---------------------------------------------------------------- места

    int idx(int wx, int y, int wz) {
        return ((y - yb) * SIDE + wz) * SIDE + wx;
    }

    boolean inside(int wx, int y, int wz) {
        return wx >= 0 && wx < SIDE && wz >= 0 && wz < SIDE && y >= yb && y <= yt;
    }

    static boolean bit(long[] b, int i) {
        return (b[i >> 6] & 1L << (i & 63)) != 0;
    }

    static void set(long[] b, int i) {
        b[i >> 6] |= 1L << (i & 63);
    }

    boolean isGone(int wx, int y, int wz) {
        return inside(wx, y, wz) && bit(gone, idx(wx, y, wz));
    }

    Blast.Props props(int wx, int y, int wz) {
        return Blast.props(w.get(wx, y, wz));
    }

    boolean touchesGone(int wx, int y, int wz) {
        for (Direction dir : Direction.values()) if (isGone(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ())) return true;
        return false;
    }

    // ---------------------------------------------------------------- решение

    private void run() {
        // третий раунд волны: что уцелело у проломов соседей (точно на 9 блоков вокруг чанка — столько читает опора)
        IntArrayList breaks = new IntArrayList();
        for (int k = 0; k < 9; k++) {
            Blast b = blasts[k];
            if (b == null) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int c : b.removed()) {
                int wx = bx + (c & 15), wz = bz + ((c >> 4) & 15), y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                if (inside(wx, y, wz)) breaks.add(idx(wx, y, wz));
            }
        }
        if (!breaks.isEmpty()) {
            for (int e : Blast.remnants(w, d, blasts, C + 1)) {
                int wx = e & 63, wz = (e >> 6) & 63, y = w.minY + (e >>> 12);
                if (!inside(wx, y, wz) || isGone(wx, y, wz)) continue;
                set(gone, idx(wx, y, wz));
                breaks.add(idx(wx, y, wz));
            }
        }
        boolean logs = false, support = false;
        for (int q = 0; q < breaks.size(); q++) {
            int i = breaks.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            Blast.Props p = props(wx, y, wz);
            if (p.log()) logs = true;
            if (support || !p.bearing() || p.log()) continue;
            // опору потеряло то, что уцелело над сломанным или рядом с ним
            if (standing(wx, y + 1, wz) || standing(wx + 1, y, wz) || standing(wx - 1, y, wz) || standing(wx, y, wz + 1) || standing(wx, y, wz - 1)) support = true;
        }
        if (!logs) for (int k = 0; k < 9 && !logs; k++) logs = blasts[k] != null && !blasts[k].trees().isEmpty();
        if (support) support();
        if (logs) leaves();
        drain();
        attachments();
        collect();
    }

    private boolean standing(int wx, int y, int wz) {
        return inside(wx, y, wz) && !isGone(wx, y, wz) && props(wx, y, wz).bearing();
    }

    // ---------------------------------------------------------------- опора

    private void support() {
        int n = SIDE * SIDE * h;
        if (before.length < n) {
            before = new byte[n];
            after = new byte[n];
            ring = new int[n + 1];
        }
        distances(before, false, n);
        distances(after, true, n);
        IntArrayList fall = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - C; wz < HI + C; wz++) {
                for (int wx = LO - C; wx < HI + C; wx++) {
                    int i = idx(wx, y, wz);
                    if (bit(gone, i)) continue;
                    Blast.Props p = props(wx, y, wz);
                    if (!p.bearing()) continue;
                    if (falls(i, wx, y, wz, after[i], p)) fall.add(i);
                }
            }
        }
        for (int i = 0; i < fall.size(); i++) set(gone, fall.getInt(i));
    }

    /**
     * Падает ли блок, чья опора после удара — {@code a}: дальше его пролёта, и опору отняла волна (до неё была ближе,
     * или стала дальше на 3 и больше, или её не было, а блок касается сломанного).
     */
    private boolean falls(int i, int wx, int y, int wz, int a, Blast.Props p) {
        int b = before[i];
        // грунт в постройке (терракота, кальцит, камень) — кладка без арматуры: треснув, она держится только так, как
        // держалась до удара, — консоль, которой стала опора подлиннее, падает
        if (a <= p.span()) return a > b && p.response().kind() == BlockResponse.Kind.GROUND;
        return b <= p.span() || a >= b + 3 || b == INF && touchesGone(wx, y, wz);
    }

    private boolean bearing(int i, int wx, int y, int wz, boolean afterBlast) {
        if (afterBlast && bit(gone, i)) return false;
        return props(wx, y, wz).bearing();
    }

    /**
     * 0-1 обход от опор низа окна: вверх 0, вбок и вниз +1, не дальше {@link #C}; дальше — {@link #INF}. После удара
     * падающий блок ({@link #falls}) никого не держит: обход через него не идёт — обрушение идёт каскадом за один обход
     * (места выходят из очереди по возрастанию расстояния, и первое — окончательное).
     */
    private void distances(byte[] dist, boolean afterBlast, int n) {
        java.util.Arrays.fill(dist, 0, n, (byte) INF);
        int cap = n + 1, head = 0, tail = 0;
        for (int wz = 0; wz < SIDE; wz++) {
            for (int wx = 0; wx < SIDE; wx++) {
                int i = idx(wx, yb, wz);
                if (!bearing(i, wx, yb, wz, afterBlast)) continue;
                dist[i] = 0;
                ring[tail] = i;
                tail = (tail + 1) % cap;
            }
        }
        while (head != tail) {
            int i = ring[head];
            head = (head + 1) % cap;
            int di = dist[i];
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            if (afterBlast && di > 0 && falls(i, wx, y, wz, di, props(wx, y, wz))) continue;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int cost = dir == Direction.UP ? 0 : 1, nd = di + cost;
                if (nd > C) continue;
                int m = idx(nx, ny, nz);
                if (dist[m] <= nd || !bearing(m, nx, ny, nz, afterBlast)) continue;
                dist[m] = (byte) nd;
                if (cost == 0) {
                    head = (head - 1 + cap) % cap;
                    ring[head] = m;
                } else {
                    ring[tail] = m;
                    tail = (tail + 1) % cap;
                }
            }
        }
    }

    // ---------------------------------------------------------------- листва

    /** Листва дальше {@link #LEAF_REACH} от уцелевшего ствола (по листве), которая до удара была ближе, — опадает. */
    private void leaves() {
        int n = SIDE * SIDE * h;
        if (before.length < n) {
            before = new byte[n];
            after = new byte[n];
            ring = new int[n + 1];
        }
        leafDistances(before, false, n);
        leafDistances(after, true, n);
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - C; wz < HI + C; wz++) {
                for (int wx = LO - C; wx < HI + C; wx++) {
                    int i = idx(wx, y, wz);
                    if (bit(gone, i) || after[i] <= LEAF_REACH || before[i] > LEAF_REACH) continue;
                    if (props(wx, y, wz).leaves()) set(gone, i);
                }
            }
        }
    }

    private void leafDistances(byte[] dist, boolean afterBlast, int n) {
        java.util.Arrays.fill(dist, 0, n, (byte) (LEAF_REACH + 1));
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            if (afterBlast && bit(gone, i) || !props(wx, y, wz).log()) continue;
            dist[i] = 0;
            ring[tail++] = i;
        }
        while (head < tail) {
            int i = ring[head++];
            int di = dist[i];
            if (di >= LEAF_REACH) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (dist[m] <= di + 1 || afterBlast && bit(gone, m) || !props(nx, ny, nz).leaves()) continue;
                dist[m] = (byte) (di + 1);
                ring[tail++] = m;
            }
        }
    }

    // ---------------------------------------------------------------- вода

    /** Малая вода у сломанного (пробито дно или стенка), с местами в чанке, — стекает целиком. */
    private void drain() {
        long[] seen = new long[gone.length];
        IntArrayList body = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO; wz < HI; wz++) {
                for (int wx = LO; wx < HI; wx++) {
                    int start = idx(wx, y, wz);
                    if (bit(seen, start) || bit(gone, start) || !props(wx, y, wz).fluid()) continue;
                    body.clear();
                    body.add(start);
                    set(seen, start);
                    boolean edge = false, touches = false;
                    int x0 = wx, x1 = wx, z0 = wz, z1 = wz;
                    for (int q = 0; q < body.size(); q++) {
                        int i = body.getInt(q), bx = i % SIDE, bz = (i / SIDE) % SIDE, by = i / (SIDE * SIDE) + yb;
                        for (Direction dir : Direction.values()) {
                            int nx = bx + dir.getStepX(), ny = by + dir.getStepY(), nz = bz + dir.getStepZ();
                            if (!inside(nx, ny, nz)) {
                                if (ny <= yt && props(nx, ny, nz).fluid()) edge = true;
                                continue;
                            }
                            int m = idx(nx, ny, nz);
                            if (bit(gone, m)) {
                                touches = true;
                                continue;
                            }
                            if (bit(seen, m) || !props(nx, ny, nz).fluid()) continue;
                            set(seen, m);
                            body.add(m);
                            x0 = Math.min(x0, nx);
                            x1 = Math.max(x1, nx);
                            z0 = Math.min(z0, nz);
                            z1 = Math.max(z1, nz);
                        }
                    }
                    if (!touches || edge || body.size() > MAX_DRAIN || x1 - x0 >= DRAIN_WIDTH || z1 - z0 >= DRAIN_WIDTH) continue;
                    for (int q = 0; q < body.size(); q++) {
                        set(drained, body.getInt(q));
                        set(gone, body.getInt(q));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- навесное

    /** Что держится за соседа, а не стоит само, — снято вместе с тем, за что держалось (два прохода: дверь на факеле). */
    private void attachments() {
        for (int pass = 0; pass < 2; pass++) {
            for (int y = Math.max(yb, w.minY + 1); y <= yt; y++) {
                for (int wz = LO; wz < HI; wz++) {
                    for (int wx = LO; wx < HI; wx++) {
                        int i = idx(wx, y, wz);
                        if (bit(gone, i)) continue;
                        BlockState st = w.get(wx, y, wz);
                        if (st.isAir() || Blast.props(st).fluid()) continue;
                        if (detached(wx, y, wz, st)) set(gone, i);
                    }
                }
            }
        }
    }

    private boolean detached(int wx, int y, int wz, BlockState st) {
        Block b = st.getBlock();
        if ((b instanceof DoorBlock || b instanceof DoublePlantBlock) && st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower = st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            return isGone(wx, lower ? y + 1 : y - 1, wz) || lower && isGone(wx, y - 1, wz);
        }
        if (b instanceof BedBlock) {
            Direction f = st.getValue(BedBlock.FACING);
            Direction other = st.getValue(BedBlock.PART) == BedPart.FOOT ? f : f.getOpposite();
            return isGone(wx + other.getStepX(), y, wz + other.getStepZ()) || isGone(wx, y - 1, wz);
        }
        if (b instanceof FaceAttachedHorizontalDirectionalBlock) {
            AttachFace face = st.getValue(BlockStateProperties.ATTACH_FACE);
            if (face == AttachFace.FLOOR) return isGone(wx, y - 1, wz);
            if (face == AttachFace.CEILING) return isGone(wx, y + 1, wz);
            Direction back = st.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            return isGone(wx + back.getStepX(), y, wz + back.getStepZ());
        }
        if (b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof LadderBlock) {
            Direction back = st.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            return isGone(wx + back.getStepX(), y, wz + back.getStepZ());
        }
        // опорное стоит само (его роняет обход опоры); остальное без коллизии — ковёр, факел, рельс, пыль, растение — на
        // том, что под ним
        if (Blast.props(st).bearing() || Blast.props(st).leaves()) return false;
        return isGone(wx, y - 1, wz);
    }

    // ---------------------------------------------------------------- выход

    private void collect() {
        java.util.Arrays.fill(ground, Integer.MIN_VALUE);
        Blast own = blasts[4];
        for (int wz = LO; wz < HI; wz++) {
            for (int wx = LO; wx < HI; wx++) {
                int column = (wz - LO) << 4 | (wx - LO);
                for (int y = yt; y >= yb; y--) {
                    int i = idx(wx, y, wz);
                    BlockState st = w.get(wx, y, wz);
                    Blast.Props p = Blast.props(st);
                    if (bit(gone, i)) {
                        if (st.isAir()) continue;
                        long at = BlockPos.asLong(w.x0 + wx, y, w.z0 + wz);
                        changes.put(at, p.waterlogged() && wet(wx, y, wz) ? Blocks.WATER.defaultBlockState() : AIR);
                        if (p.leaves() && own != null && own.gone(wx - LO, y, wz - LO)) leaves.add(at);
                        if (!p.fluid() && !p.leaves() && !p.log() && st.getBlock().defaultDestroyTime() >= 1) {
                            removed[column]++;
                            if (st.is(BlockTags.MINEABLE_WITH_AXE)) wood[column]++;
                        }
                        continue;
                    }
                    if (ground[column] == Integer.MIN_VALUE && p.bearing() && !p.log()) ground[column] = y;
                }
            }
        }
        // тики жидкости: у сломанного в чанке — и в соседних чанках; жидкость чанка у сломанного соседей
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - 1; wz <= HI; wz++) {
                for (int wx = LO - 1; wx <= HI; wx++) {
                    int i = idx(wx, y, wz);
                    if (bit(gone, i) || !props(wx, y, wz).fluid()) continue;
                    boolean mine = wx >= LO && wx < HI && wz >= LO && wz < HI;
                    boolean near = false;
                    for (Direction dir : Direction.values()) {
                        int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                        if (!isGone(nx, ny, nz)) continue;
                        if (mine || nx >= LO && nx < HI && nz >= LO && nz < HI) near = true;
                    }
                    if (near) fluidTicks.add(BlockPos.asLong(w.x0 + wx, y, w.z0 + wz));
                }
            }
        }
    }

    /** Блок с водой, сломанный у воды, которая осталась (причал у реки), — вода; высоко и в одиночку — воздух. */
    private boolean wet(int wx, int y, int wz) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
            if (!inside(nx, ny, nz) || bit(drained, idx(nx, ny, nz))) continue;
            BlockState n = w.get(nx, ny, nz);
            if (Blast.props(n).fluid() && !isGone(nx, ny, nz) || Blast.props(n).waterlogged() && !isGone(nx, ny, nz)) return true;
        }
        return false;
    }
}
