package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /** Буферы обхода — свои у каждого потока (без повторного входа): растут и переиспользуются. */
    private static final class Buffers {
        byte[] before = new byte[0], after = new byte[0];
        int[] ring = new int[0];
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);
    private byte[] before, after;
    private int[] ring;

    final RuinWindow w;
    final Blast[] blasts = new Blast[9];
    final int yb, yt, h;
    /** Самая низкая исходная поверхность окна (верх опоры столбца): всё несущее не выше неё — земля, опора. */
    final int surface;
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
    private final boolean blockDamage;

    private Collapse(RuinContext.Grid g) {
        this.w = g.windowOf(0, 0);
        this.d = g.ctx().d;
        this.blockDamage = g.blockDamage();
        for (int k = 0; k < 9; k++) {
            if (!w.present(k)) continue;
            blasts[k] = g.blast(k % 3 - 1, k / 3 - 1);
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
        int ground = Integer.MAX_VALUE;
        for (int wz = 0; wz < SIDE; wz++) {
            for (int wx = 0; wx < SIDE; wx++) {
                int s = w.solid(wx, wz);
                if (s != Integer.MIN_VALUE && w.has(wx, wz)) ground = Math.min(ground, s);
            }
        }
        this.surface = ground == Integer.MAX_VALUE ? w.minY - 1 : ground;
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

    /** Руины чанка в центре снимков (любой поток): разломы окна — из кэша подрыва или заново. */
    static Collapse solve(RuinContext.Grid g) {
        Collapse c = new Collapse(g);
        c.run();
        return c;
    }

    /** По каким снимкам построено окно чанка. */
    RuinWindow.Stamp stamp() {
        return w.stamp();
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
        return w.props(wx, y, wz);
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
            for (int e : Blast.remnants(w, d, blasts, C + 1, blockDamage)) {
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
        if (!breaks.isEmpty()) drain();
        attachments();
        collect();
    }

    private boolean standing(int wx, int y, int wz) {
        return inside(wx, y, wz) && !isGone(wx, y, wz) && props(wx, y, wz).bearing();
    }

    // ---------------------------------------------------------------- опора

    private void support() {
        int n = SIDE * SIDE * h;
        buffers(n);
        long[] fixed = fixedCells();
        distances(before, false, n, fixed);
        distances(after, true, n, fixed);
        long[] down = new long[gone.length];
        IntArrayList fall = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - C; wz < HI + C; wz++) {
                for (int wx = LO - C; wx < HI + C; wx++) {
                    int i = idx(wx, y, wz);
                    if (bit(gone, i)) continue;
                    Blast.Props p = props(wx, y, wz);
                    if (!p.bearing()) continue;
                    if (falls(i, after[i], p)) {
                        fall.add(i);
                        set(down, i);
                    }
                }
            }
        }
        // постройка, которую и до удара не держало ничего в пределах {@link #C} (зал шире 2C, стоящее на висящей
        // площадке): падает всё, что касается сломанного или падающего, — и дальше по связанным, до неподвижной точки.
        // Грунт так не падает: природные своды и навесы, которые «висели» и до удара, стоят
        IntArrayList queue = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - C; wz < HI + C; wz++) {
                for (int wx = LO - C; wx < HI + C; wx++) {
                    int i = idx(wx, y, wz);
                    if (!unsupported(i, wx, y, wz, down)) continue;
                    if (touches(wx, y, wz, gone) || touches(wx, y, wz, down)) {
                        set(down, i);
                        queue.add(i);
                    }
                }
            }
        }
        for (int q = 0; q < queue.size(); q++) {
            int i = queue.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            fall.add(i);
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (nx < LO - C || nx >= HI + C || nz < LO - C || nz >= HI + C || !inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (!unsupported(m, nx, ny, nz, down)) continue;
                set(down, m);
                queue.add(m);
            }
        }
        for (int i = 0; i < fall.size(); i++) set(gone, fall.getInt(i));
    }

    /** Несущее не из грунта, без опоры в пределах {@link #C} и до удара, и после, ещё не падает. */
    private boolean unsupported(int i, int wx, int y, int wz, long[] down) {
        if (bit(gone, i) || bit(down, i) || before[i] < INF || after[i] < INF) return false;
        Blast.Props p = props(wx, y, wz);
        return p.bearing() && !p.fixed() && p.response().kind() != BlockResponse.Kind.GROUND;
    }

    /** Касается сломанного или падающего несущего (цветок, факел, листва рядом каскада не начинают). */
    private boolean touches(int wx, int y, int wz, long[] bits) {
        for (Direction dir : Direction.values()) {
            int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
            if (inside(nx, ny, nz) && bit(bits, idx(nx, ny, nz)) && props(nx, ny, nz).bearing()) return true;
        }
        return false;
    }

    /**
     * Падает ли блок, чья опора после удара — {@code a}: дальше его пролёта, и опору отняла волна (до неё была ближе,
     * или стала дальше на 3 и больше; не дальше {@link #INF}). Блок без опоры и до удара ({@code INF}) — отдельно
     * ({@link #support}). Неразрушимое не падает.
     */
    private boolean falls(int i, int a, Blast.Props p) {
        if (p.fixed()) return false;
        int b = before[i];
        // грунт в постройке (терракота, кальцит, камень) — кладка без арматуры: треснув, она держится только так, как
        // держалась до удара, — консоль, которой стала опора подлиннее, падает
        if (a <= p.span()) return a > b && p.response().kind() == BlockResponse.Kind.GROUND;
        return b <= p.span() || b < INF && a >= Math.min(b + 3, INF);
    }

    private boolean bearing(int i, int wx, int y, int wz, boolean afterBlast) {
        if (afterBlast && bit(gone, i)) return false;
        return props(wx, y, wz).bearing();
    }

    /** Неразрушимое несущее в окне (только в секциях, чья палитра его знает) — опоры, как земля. */
    private long[] fixedCells() {
        long[] out = new long[gone.length];
        for (int k = 0; k < 9; k++) {
            if (!w.present(k)) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int sec = (yb - w.minY) >> 4; sec <= (yt - w.minY) >> 4; sec++) {
                if (!w.mayHave(k, sec, st -> w.view.get(st).fixed() && w.view.get(st).bearing())) continue;
                for (int y = Math.max(yb, w.minY + (sec << 4)); y <= Math.min(yt, w.minY + (sec << 4) + 15); y++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            Blast.Props p = props(bx + lx, y, bz + lz);
                            if (p.fixed() && p.bearing()) set(out, idx(bx + lx, y, bz + lz));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * 0-1 обход от опор (вверх 0, вбок и вниз +1, не дальше {@link #C}; дальше — {@link #INF}). Опоры — земля: всё
     * несущее на низу окна и не выше самой низкой исходной поверхности окна ({@link #surface}), и неразрушимое. После
     * удара падающий блок ({@link #falls}) никого не держит: обход через него не идёт — обрушение идёт каскадом за один
     * обход (места выходят из очереди по возрастанию расстояния, и первое — окончательное).
     */
    private void distances(byte[] dist, boolean afterBlast, int n, long[] fixed) {
        java.util.Arrays.fill(dist, 0, n, (byte) INF);
        int cap = n + 1, head = 0, tail = 0;
        for (int y = yb; y <= yt; y++) {
            boolean ground = y == yb || y <= surface;
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int i = idx(wx, y, wz);
                    if (!(ground || bit(fixed, i)) || !bearing(i, wx, y, wz, afterBlast)) continue;
                    dist[i] = 0;
                    ring[tail] = i;
                    tail = (tail + 1) % cap;
                }
            }
            if (!ground && y > surface) {
                // выше земли — только неразрушимое: его места уже собраны, остальные слои не перебираются
                for (int wd = (y - yb) * SIDE * SIDE >> 6, e = ((yt - yb + 1) * SIDE * SIDE + 63) >> 6; wd < e; wd++) {
                    long bits = fixed[wd];
                    while (bits != 0) {
                        int i = wd << 6 | Long.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        if (i >= n || dist[i] == 0) continue;
                        int wx = i % SIDE, wz = (i / SIDE) % SIDE, yy = i / (SIDE * SIDE) + yb;
                        if (!bearing(i, wx, yy, wz, afterBlast)) continue;
                        dist[i] = 0;
                        ring[tail] = i;
                        tail = (tail + 1) % cap;
                    }
                }
                break;
            }
        }
        while (head != tail) {
            int i = ring[head];
            head = (head + 1) % cap;
            int di = dist[i];
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            if (afterBlast && di > 0 && falls(i, di, props(wx, y, wz))) continue;
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

    private void buffers(int n) {
        Buffers b = BUFFERS.get();
        if (b.before.length < n) {
            b.before = new byte[n];
            b.after = new byte[n];
            b.ring = new int[n + 1];
        }
        before = b.before;
        after = b.after;
        ring = b.ring;
    }

    /** Очереди руин пусты: буферы обхода этого потока больше не нужны (окно высокого города — десятки МБ). */
    static void releaseBuffers() {
        Buffers b = BUFFERS.get();
        b.before = b.after = new byte[0];
        b.ring = new int[0];
    }

    // ---------------------------------------------------------------- листва

    /**
     * Листва дальше {@link #LEAF_REACH} от уцелевшего ствола (по листве), которая до удара была ближе, — опадает.
     * Измениться это могло только у листвы не дальше {@link #LEAF_REACH} от сломанного бревна или листа, поэтому
     * обход — только вокруг них (стволы — в пределах двойного охвата), а не по всему окну.
     */
    private void leaves() {
        // что сломано из дерева: семена
        IntArrayList seeds = new IntArrayList();
        for (int wd = 0; wd < gone.length; wd++) {
            long bits = gone[wd];
            while (bits != 0) {
                int i = wd << 6 | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                Blast.Props p = props(wx, y, wz);
                if (p.leaves() || p.log()) seeds.add(i);
            }
        }
        if (seeds.isEmpty()) return;
        // район: листва до 2·LEAF_REACH + 1 шагов от семян (и брёвна рядом с ней); ближние LEAF_REACH — кандидаты
        it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap reach = new it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap();
        IntArrayList logs = new IntArrayList(), queue = new IntArrayList();
        for (int i : seeds) {
            reach.put(i, (byte) 0);
            queue.add(i);
        }
        for (int q = 0; q < queue.size(); q++) {
            int i = queue.getInt(q), di = reach.get(i);
            if (di > 2 * LEAF_REACH) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (reach.containsKey(m)) continue;
                Blast.Props p = props(nx, ny, nz);
                if (p.log()) {
                    reach.put(m, (byte) (di + 1));
                    logs.add(m);
                } else if (p.leaves()) {
                    reach.put(m, (byte) (di + 1));
                    queue.add(m);
                }
            }
        }
        for (int i : seeds) if (props(i % SIDE, i / (SIDE * SIDE) + yb, (i / SIDE) % SIDE).log()) logs.add(i);
        it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap was = leafDistances(reach, logs, false), now = leafDistances(reach, logs, true);
        for (var e : reach.int2ByteEntrySet()) {
            int i = e.getIntKey();
            if (e.getByteValue() > LEAF_REACH || bit(gone, i)) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            if (wx < LO - C || wx >= HI + C || wz < LO - C || wz >= HI + C || !props(wx, y, wz).leaves()) continue;
            if (was.getOrDefault(i, (byte) (LEAF_REACH + 1)) <= LEAF_REACH && now.getOrDefault(i, (byte) (LEAF_REACH + 1)) > LEAF_REACH) set(gone, i);
        }
    }

    /** Расстояние по листве района до ствола (до или после удара), не дальше {@link #LEAF_REACH}. */
    private it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap leafDistances(it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap reach, IntArrayList logs, boolean afterBlast) {
        it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap dist = new it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap();
        IntArrayList queue = new IntArrayList();
        for (int i : logs) {
            if (afterBlast && bit(gone, i) || dist.containsKey(i)) continue;
            dist.put(i, (byte) 0);
            queue.add(i);
        }
        for (int q = 0; q < queue.size(); q++) {
            int i = queue.getInt(q), di = dist.get(i);
            if (di >= LEAF_REACH) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (!reach.containsKey(m) || dist.containsKey(m) || afterBlast && bit(gone, m) || !props(nx, ny, nz).leaves()) continue;
                dist.put(m, (byte) (di + 1));
                queue.add(m);
            }
        }
        return dist;
    }

    // ---------------------------------------------------------------- вода

    /**
     * Малая вода у сломанного (пробито дно или стенка), с местами в чанке, — стекает целиком. Обход водоёма
     * останавливается, как только он большой ({@link #MAX_DRAIN} мест, {@link #DRAIN_WIDTH} в ширину) или у края окна:
     * море не обходится целиком.
     */
    private void drain() {
        long[] seen = new long[gone.length], big = new long[gone.length];
        IntArrayList body = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO; wz < HI; wz++) {
                for (int wx = LO; wx < HI; wx++) {
                    int start = idx(wx, y, wz);
                    if (bit(seen, start) || bit(gone, start) || !props(wx, y, wz).fluid()) continue;
                    body.clear();
                    body.add(start);
                    set(seen, start);
                    boolean stop = false, touches = false;
                    int x0 = wx, x1 = wx, z0 = wz, z1 = wz;
                    for (int q = 0; q < body.size() && !stop; q++) {
                        int i = body.getInt(q), bx = i % SIDE, bz = (i / SIDE) % SIDE, by = i / (SIDE * SIDE) + yb;
                        for (Direction dir : Direction.values()) {
                            int nx = bx + dir.getStepX(), ny = by + dir.getStepY(), nz = bz + dir.getStepZ();
                            if (!inside(nx, ny, nz)) {
                                if (ny <= yt && props(nx, ny, nz).fluid()) stop = true;
                                continue;
                            }
                            int m = idx(nx, ny, nz);
                            if (bit(gone, m)) {
                                touches = true;
                                continue;
                            }
                            if (!props(nx, ny, nz).fluid()) continue;
                            // часть большого водоёма, обход которого уже бросили
                            if (bit(big, m)) stop = true;
                            if (bit(seen, m)) continue;
                            set(seen, m);
                            body.add(m);
                            x0 = Math.min(x0, nx);
                            x1 = Math.max(x1, nx);
                            z0 = Math.min(z0, nz);
                            z1 = Math.max(z1, nz);
                        }
                        if (body.size() > MAX_DRAIN || x1 - x0 >= DRAIN_WIDTH || z1 - z0 >= DRAIN_WIDTH) stop = true;
                    }
                    if (stop) {
                        for (int q = 0; q < body.size(); q++) set(big, body.getInt(q));
                        continue;
                    }
                    if (!touches) continue;
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
                        Blast.Props p = props(wx, y, wz);
                        if (p.air() || p.fluid() || p.fixed()) continue;
                        // опорное стоит само (его роняет обход опоры), листва держится за ствол
                        if ((p.bearing() || p.leaves()) && !attached(w.get(wx, y, wz))) continue;
                        if (detached(wx, y, wz, w.get(wx, y, wz), p)) set(gone, i);
                    }
                }
            }
        }
    }

    /** Держится за соседа по своему виду (дверь, кровать, кнопка, настенное), а не стоит само. */
    private static boolean attached(BlockState st) {
        Block b = st.getBlock();
        return b instanceof DoorBlock || b instanceof DoublePlantBlock || b instanceof BedBlock || b instanceof FaceAttachedHorizontalDirectionalBlock
                || b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof LadderBlock;
    }

    private boolean detached(int wx, int y, int wz, BlockState st, Blast.Props p) {
        if (p.fixed()) return false;
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
        if (p.bearing() || p.leaves()) return false;
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
                    Blast.Props p = props(wx, y, wz);
                    if (bit(gone, i)) {
                        if (p.air()) continue;
                        long at = BlockPos.asLong(w.x0 + wx, y, w.z0 + wz);
                        changes.put(at, p.waterlogged() && wet(wx, y, wz) ? Blocks.WATER.defaultBlockState() : AIR);
                        if (p.leaves() && own != null && own.gone(wx - LO, y, wz - LO)) leaves.add(at);
                        if (!p.fluid() && !p.leaves() && !p.log() && p.rubble()) {
                            removed[column]++;
                            if (p.wood()) wood[column]++;
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
            Blast.Props n = props(nx, ny, nz);
            if ((n.fluid() || n.waterlogged()) && !isGone(nx, ny, nz)) return true;
        }
        return false;
    }
}
