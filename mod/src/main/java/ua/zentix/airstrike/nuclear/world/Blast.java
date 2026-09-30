package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.nuclear.Detonation;

import java.util.ArrayList;
import java.util.List;

/**
 * Что ударная волна сломала в одном чанке — первый из двух физических процессов руин (второй — {@link Collapse}):
 * <b>перепад давления ломает тонкое</b>. Считается по исходным блокам окна чанка ({@link RuinWindow}) и больше ни от чего
 * не зависит, поэтому разлом чанка один и тот же, кто бы и когда его ни посчитал (кэш — {@link RuinContext}).
 * <ul>
 * <li>Давление приходит только к граням у воздуха, связанного с небом: над верхом столбцов и не глубже
 * {@link #SKY_DEPTH} блоков внутрь через проёмы, а за проломами (раунды 2 и 3) — во весь связанный с ними воздух окна:
 * волна затекает по этажам до ядра. Бункер, пещера без выхода, подвал не трогаются.</li>
 * <li>На гранях к взрыву — отражённое давление {@code P = max(p, Pr·cos θ)}, {@code Pr = 2p(7p₀+4p)/(7p₀+p)}; у стен
 * угол — в горизонтальной плоскости (волна у земли идёт стеблем Маха), у крыш — по вертикали.</li>
 * <li>Блок в элементе толщиной {@code t} ≤ 3 (ряд полных кубов с открытыми концами по наименьшей оси) ломается при
 * {@code P ≥ T·k(t)·jitter}, {@code k = 1, 2, 3.5}; пробитый с лица элемент ломается на всю толщину. Грунт — только
 * с небом с обеих сторон (гребень, стенка, навес).</li>
 * <li>Опрокидывание: высокое и узкое (гибкость {@code H/t ≥ 4}, {@code t} ≤ {@link #MAX_TOPPLE} по направлению от
 * взрыва, сзади небо) ломается по всему сечению при {@code Pr·cos θ·H/t ≥ T·k(3)·jitter}. {@code H} — высота над местом,
 * пока сзади воздух: перекрытие за простенком кончает плечо на этаже. Так падают стены в два кирпича и ядра высоток,
 * а горы, скалы, мезы (толще) стоят.</li>
 * <li>Два раунда: второй видит проломы первого (и воздух за ними: там опрокидывается только столб не шире
 * {@link #MAX_TOPPLE} поперёк). Третий — по проломам всех соседей ({@link #remnants}). Деревья валятся
 * до волны по блокам, стволом целиком.</li>
 * <li>Неразрушимое (прочность &lt; 0 или ≥ 50: коренная порода, обсидиан, барьер, свет) не ломается, не падает
 * и держит, как земля ({@link Props#fixed}).</li>
 * </ul>
 * Охват: решение о блоке читает не дальше 16 блоков — ширины соседнего чанка в окне.
 */
record Blast(RuinWindow.Stamp stamp, int minY, int[] removed, List<long[]> trees, List<BlockState> treeLogs) {
    /** Насколько глубоко внутрь от проёма доходит давление, блоки. */
    static final int SKY_DEPTH = 4;
    /**
     * Сколько столбцов вокруг чанка решает первый раунд: второй раунд чанка читает проломы на 5 блоков вокруг и воздух,
     * открытый ими, ещё на {@link #SKY_DEPTH} — так он тот же, что посчитал бы любой сосед.
     */
    private static final int ROUND_ONE = 9;
    /** Атмосферное давление, psi. */
    private static final double P0 = 14.7;
    /** Во сколько раз элемент толщиной 1, 2, 3 прочнее тонкого. */
    static final double[] K = {0, 1, 2, 3.5};
    /** Гибкость, с которой элемент опрокидывается, и наибольшая его толщина. */
    private static final int SLENDER = 4, MAX_TOPPLE = 6;
    /**
     * Наибольшая толщина опрокидывания у грунта: природные столбы и мезы толще 4 стоят (ядро высотки из кальцита
     * или терракоты 4×4 — ещё падает, как и до ревью).
     */
    private static final int GROUND_TOPPLE = 4;
    private static final int SIDE = RuinWindow.SIDE, LO = 16, HI = 32;

    // ---------------------------------------------------------------- свойства состояний

    /**
     * Как блок ведёт себя в физике руин. {@code rubble} — даёт завал, когда рушится (прочность от 1: не трава, не
     * стекло), {@code wood} — завал из дерева (рубится топором).
     */
    record Props(BlockResponse response, boolean air, boolean full, boolean bearing, int span, boolean fluid, boolean waterlogged, boolean leaves,
                 boolean log, boolean fixed, boolean rubble, boolean wood) {
        float threshold() {
            return response.thresholdPsi();
        }

        /** Ломается волной (бревно — только стволом дерева, не по блокам). */
        boolean breakable() {
            return response.kind() != BlockResponse.Kind.NONE && !log;
        }
    }

    /** Свойства состояний для решателей руин: в потоке сервера — по общей таблице, в фоне — по опубликованной. */
    interface PropsView {
        Props get(BlockState st);
    }

    /** Все известные свойства (только поток сервера). */
    private static final Reference2ObjectOpenHashMap<BlockState, Props> PROPS = new Reference2ObjectOpenHashMap<>();
    /** Копия {@link #PROPS} для фоновых потоков: не меняется, новая — при {@link #publish}. */
    private static volatile Reference2ObjectOpenHashMap<BlockState, Props> published = new Reference2ObjectOpenHashMap<>();
    private static boolean dirty;
    /** Сколько раз таблицу сбрасывали: снимок, снятый до сброса, заново заносит свои состояния ({@link ChunkShot#current}). */
    static int generation;

    /** Теги перезагрузили: пороги другие (поток сервера — как и всё в руинах). */
    static void clearProps() {
        generation++;
        PROPS.clear();
        published = new Reference2ObjectOpenHashMap<>();
        dirty = false;
    }

    /** Свойства состояния (поток сервера). */
    static Props props(BlockState st) {
        Props p = PROPS.get(st);
        if (p == null) {
            PROPS.put(st, p = computeProps(st));
            dirty = true;
        }
        return p;
    }

    /** Состояние из снимка ({@link ChunkShot}) — в таблицу (поток сервера); фоновым потокам — после {@link #publish}. */
    static void ensure(BlockState st) {
        props(st);
    }

    /** Таблица для фоновых задач, созданных после этого вызова (поток сервера): все свойства, известные сейчас. */
    static PropsView publish() {
        props(AIR);
        props(RuinWindow.MASS);
        props(WATER);
        if (dirty) {
            published = new Reference2ObjectOpenHashMap<>(PROPS);
            dirty = false;
        }
        Reference2ObjectOpenHashMap<BlockState, Props> table = published;
        return st -> {
            Props p = table.get(st);
            if (p == null) throw new IllegalStateException("Руины: свойства состояния " + st + " не в таблице снимка");
            return p;
        };
    }

    /** Свойства в потоке сервера. */
    static final PropsView SERVER = Blast::props;

    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            WATER = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();

    private static Props computeProps(BlockState st) {
        BlockResponse r = BlockResponse.of(st);
        boolean liquid = st.getBlock() instanceof LiquidBlock;
        var shape = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        boolean full = !liquid && Block.isShapeFullBlock(shape);
        boolean leaves = st.is(BlockTags.LEAVES), log = st.is(BlockTags.LOGS);
        // опора — всё с коллизией (стекло, цепи, заборы тоже); листва держится за ствол, а не опирается ({@link Collapse})
        boolean bearing = !liquid && !leaves && !shape.isEmpty();
        // пролёт: сколько блоков вбок (или вниз) блок держится без опоры под собой
        int span;
        if (st.getBlock() instanceof FallingBlock || r.thresholdPsi() <= 1) span = 0;
        else if (log || r.kind() == BlockResponse.Kind.NONE || r.kind() == BlockResponse.Kind.GROUND || r.thresholdPsi() >= 12) span = 6;
        else span = 4;
        boolean waterlogged = !liquid && st.hasProperty(BlockStateProperties.WATERLOGGED) && st.getValue(BlockStateProperties.WATERLOGGED);
        // неразрушимое (коренная порода, барьер, свет, рамка портала, обсидиан): не падает, не отрывается и держит
        float destroy = st.getBlock().defaultDestroyTime();
        boolean fixed = !st.isAir() && !liquid && (destroy < 0 || destroy >= 50);
        return new Props(r, st.isAir(), full, bearing, span, liquid, waterlogged, leaves, log, fixed, destroy >= 1, st.is(BlockTags.MINEABLE_WITH_AXE));
    }

    /** Отражённое давление при падении по нормали (воздух, γ = 1.4), psi. */
    static double reflected(double p) {
        return 2 * p * (7 * P0 + 4 * p) / (7 * P0 + p);
    }

    // ---------------------------------------------------------------- результат

    /** Место чанка (как ключи {@link RuinPlan}: секция, y, z, x) сломано волной или повалено со стволом. */
    boolean gone(int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (i < 0) return false;
        return java.util.Arrays.binarySearch(removed, i << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx) >= 0;
    }

    /** Разлом чанка окна {@code w} (любой поток: окно — из снимков). */
    static Blast solve(RuinWindow w, Detonation d, boolean blockDamage, boolean treeFall) {
        Solver s = new Solver(w, d);
        try {
            return s.run(blockDamage, treeFall);
        } finally {
            s.close();
        }
    }

    /** Буферы решателя — свои у каждого потока: один решатель за раз берёт их, вложенный — свои. */
    private static final class Buffers {
        byte[] sky = new byte[0];
        long[] gone = new long[0];
        boolean busy;
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    /** Очереди руин пусты: буферы решателя этого потока больше не нужны. */
    static void releaseBuffers() {
        Buffers b = BUFFERS.get();
        if (b.busy) return;
        b.sky = new byte[0];
        b.gone = new long[0];
    }

    /**
     * Третий раунд — по разлому всех чанков окна (его строит {@link Collapse}): что осталось стоять у проломов соседей
     * (угол дома, стена у перекрытия, ядро за выбитыми этажами), теперь тоньше и открыто с другой стороны — решается
     * заново теми же правилами в столбцах чанка ± {@code margin}. Места — окна: {@code wx | wz << 6 | (y − minY) << 12}.
     */
    static int[] remnants(RuinWindow w, Detonation d, Blast[] blasts, int margin, boolean blockDamage) {
        if (!blockDamage) return new int[0];
        Solver s = new Solver(w, d);
        try {
            return s.remnants(blasts, margin);
        } finally {
            s.close();
        }
    }

    // ---------------------------------------------------------------- решение

    private static final class Solver {
        /** Третий раунд ({@link Blast#remnants}): проломы всех чанков окна — пролом и есть. */
        int[] remnants(Blast[] blasts, int margin) {
            skyAir();
            IntArrayList breaks = new IntArrayList();
            for (int k = 0; k < 9; k++) {
                if (blasts[k] == null) continue;
                int bx = (k % 3) << 4, bz = (k / 3) << 4;
                for (int c : blasts[k].removed()) {
                    int wx = bx + (c & 15), wz = bz + ((c >> 4) & 15), y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                    if (!inside(wx, y, wz)) continue;
                    int i = idx(wx, y, wz);
                    set(gone, i);
                    breaks.add(i);
                }
            }
            IntArrayList out = new IntArrayList();
            if (!breaks.isEmpty()) aroundBreaks(breaks, margin, out);
            int[] res = new int[out.size()];
            for (int q = 0; q < res.length; q++) {
                int i = out.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                res[q] = wx | wz << 6 | (y - w.minY) << 12;
            }
            return res;
        }

        final RuinWindow w;
        final Detonation d;
        final Vec3 burst;
        final int yb, yt, h;
        /** Самый низкий верх в окрестности {@link #SKY_DEPTH} + 1: ниже него проёмов с небом нет. */
        final int[] lowTop = new int[SIDE * SIDE];
        /** Глубина воздуха, связанного с небом (1…{@link #SKY_DEPTH}); над верхом столбца — небо без записи. */
        final byte[] sky;
        final long[] gone;
        /** Буферы {@link #sky} и {@link #gone} — потока ({@link #BUFFERS}). */
        final Buffers pooled;
        final List<long[]> trees = new ArrayList<>();
        final List<BlockState> treeLogs = new ArrayList<>();

        Solver(RuinWindow w, Detonation d) {
            this.w = w;
            this.d = d;
            this.burst = d.burst();
            int minTop = Integer.MAX_VALUE, maxTop = w.minY;
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int t = w.top(wx, wz);
                    if (t == Integer.MIN_VALUE) continue;
                    maxTop = Math.max(maxTop, t);
                }
            }
            int r = SKY_DEPTH + 1;
            int[] rows = new int[SIDE * SIDE];
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int m = Integer.MAX_VALUE;
                    for (int x = Math.max(0, wx - r); x <= Math.min(SIDE - 1, wx + r); x++) m = Math.min(m, topOr(x, wz));
                    rows[wz * SIDE + wx] = m;
                }
            }
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int m = Integer.MAX_VALUE;
                    for (int z = Math.max(0, wz - r); z <= Math.min(SIDE - 1, wz + r); z++) m = Math.min(m, rows[z * SIDE + wx]);
                    lowTop[wz * SIDE + wx] = m;
                    minTop = Math.min(minTop, m);
                }
            }
            // ниже самого низкого проёма минус глубина неба волна ничего не видит
            this.yb = Math.max(w.minY, Math.min(minTop, maxTop) - SKY_DEPTH - 2);
            this.yt = Math.min(w.maxY - 1, maxTop + 1);
            this.h = Math.max(1, yt - yb + 1);
            int n = SIDE * SIDE * h, words = (n + 63) >> 6;
            Buffers b = BUFFERS.get();
            if (b.busy) {
                this.sky = new byte[n];
                this.gone = new long[words];
                this.pooled = null;
            } else {
                if (b.sky.length < n) b.sky = new byte[n];
                if (b.gone.length < words) b.gone = new long[words];
                java.util.Arrays.fill(b.sky, 0, n, (byte) 0);
                java.util.Arrays.fill(b.gone, 0, words, 0L);
                this.sky = b.sky;
                this.gone = b.gone;
                b.busy = true;
                this.pooled = b;
            }
        }

        /** Буферы — обратно в общие. */
        void close() {
            if (pooled != null) pooled.busy = false;
        }

        int topOr(int wx, int wz) {
            int t = w.top(wx, wz);
            return t == Integer.MIN_VALUE ? w.minY - 1 : t;
        }

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

        /** Воздух, связанный с небом: над верхом столбца, внутри не глубже {@link #SKY_DEPTH}, или пролом. */
        boolean open(int wx, int y, int wz) {
            if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return false;
            if (y >= w.maxY) return true;
            if (y > w.top(wx, wz)) return w.has(wx, wz);
            if (y < yb || y > yt) return false;
            int i = idx(wx, y, wz);
            return sky[i] > 0 || bit(gone, i);
        }

        Blast run(boolean blockDamage, boolean treeFall) {
            if (blockDamage) {
                skyAir();
                if (treeFall) fellTrees();
                IntArrayList round = new IntArrayList();
                for (int wz = LO - ROUND_ONE; wz < HI + ROUND_ONE; wz++) {
                    for (int wx = LO - ROUND_ONE; wx < HI + ROUND_ONE; wx++) {
                        int t = w.top(wx, wz);
                        if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                        int low = Math.max(yb, Math.min(t, lowTop[wz * SIDE + wx]) - SKY_DEPTH - 1);
                        double pmax = reflected(d.psi(new Vec3(w.x0 + wx + 0.5, Mth.clamp(burst.y, low, t + 1), w.z0 + wz + 0.5)));
                        for (int y = Math.min(t, yt); y >= low; y--) {
                            Props p = w.props(wx, y, wz);
                            if (p.air() || isGone(wx, y, wz)) continue;
                            if (!p.breakable()) continue;
                            // отсечка: блок не сломается и опрокидыванием (гибкость — не больше высоты над ним: 64 блока)
                            if (p.threshold() * 0.85 > pmax * 64 / K[3]) continue;
                            decide(wx, y, wz, p, pmax, round, true);
                        }
                    }
                }
                for (int i = 0; i < round.size(); i++) set(gone, round.getInt(i));
                // проломы первого раунда впускают волну во весь воздух за ними (залы, этажи, ядро за фасадом);
                // второй раунд — в самом чанке: блоки у проломов и у этого воздуха
                IntArrayList second = new IntArrayList();
                aroundBreaks(round, 0, second);
                for (int i = 0; i < second.size(); i++) set(gone, second.getInt(i));
            }
            // сломанное в самом чанке — ключами плана
            IntArrayList out = new IntArrayList();
            for (int y = yb; y <= yt; y++) {
                for (int wz = LO; wz < HI; wz++) {
                    for (int wx = LO; wx < HI; wx++) {
                        if (!bit(gone, idx(wx, y, wz))) continue;
                        out.add(((y - w.minY) >> 4) << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | (wz - LO) << 4 | (wx - LO));
                    }
                }
            }
            int[] removed = out.toIntArray();
            java.util.Arrays.sort(removed);
            return new Blast(w.stamp(), w.minY, removed, trees, treeLogs);
        }

        /**
         * Волна входит в проломы {@code breaks}: весь воздух, связанный с ними, в столбцах чанка ± {@link #ROUND_ONE}
         * получает давление падающей волны (становится воздухом с небом), и блоки у проломов и у этого воздуха в столбцах
         * чанка ± {@code margin} решаются заново — в {@code out}. Дальше {@link #ROUND_ONE} заливка не идёт: решения там
         * не нужны ни этому чанку, ни обрушению (его пути опоры короче), а высотка в окне 48×48 — десятки мс на единицу.
         */
        void aroundBreaks(IntArrayList breaks, int margin, IntArrayList out) {
            IntArrayList reach = new IntArrayList(breaks);
            for (int q = 0; q < reach.size(); q++) {
                int c = reach.getInt(q), wx = c % SIDE, wz = (c / SIDE) % SIDE, y = c / (SIDE * SIDE) + yb;
                int dc = bit(gone, c) ? 0 : sky[c];
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (nx < LO - ROUND_ONE || nx >= HI + ROUND_ONE || nz < LO - ROUND_ONE || nz >= HI + ROUND_ONE) continue;
                    if (!inside(nx, ny, nz) || ny > w.top(nx, nz)) continue;
                    int n = idx(nx, ny, nz);
                    // место с небом уже пройдено (у воздуха — отметка глубины, проставленная при первом заходе)
                    if (bit(gone, n) || sky[n] != 0 || !w.air(nx, ny, nz)) continue;
                    sky[n] = (byte) Math.min(Byte.MAX_VALUE, dc + 1);
                    reach.add(n);
                }
            }
            IntOpenHashSet seen = new IntOpenHashSet();
            for (int q = 0; q < reach.size(); q++) {
                int c = reach.getInt(q), wx = c % SIDE, wz = (c / SIDE) % SIDE, y = c / (SIDE * SIDE) + yb;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (nx < LO - margin || nx >= HI + margin || nz < LO - margin || nz >= HI + margin || ny < yb || ny > yt) continue;
                    int n = idx(nx, ny, nz);
                    if (bit(gone, n) || !seen.add(n)) continue;
                    Props p = w.props(nx, ny, nz);
                    if (p.air() || !p.breakable()) continue;
                    decide(nx, ny, nz, p, Double.MAX_VALUE, out, false);
                }
            }
        }

        /** Сломать ли блок (толщина, отражение) и опрокинуть ли элемент; сломанные места — в {@code out}. */
        void decide(int wx, int y, int wz, Props p, double pmax, IntArrayList out, boolean first) {
            int x = w.x0 + wx, z = w.z0 + wz;
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            double ux = burst.x - cx, uy = burst.y - cy, uz = burst.z - cz, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (len < 1e-6) len = 1e-6;
            double vy = uy / len, hl = Math.hypot(ux, uz);
            double ex = hl < 1e-6 ? 0 : ux / hl, ez = hl < 1e-6 ? 0 : uz / hl;
            // грани у неба: самая прямая к взрыву (стены — по углу в горизонтальной плоскости, стебель Маха; крыши — по вертикали)
            double facing = -2;
            Direction.Axis faceAxis = null;
            for (Direction dir : Direction.values()) {
                if (!open(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ())) continue;
                double cos = dir.getAxis() == Direction.Axis.Y ? dir.getStepY() * vy : dir.getStepX() * ex + dir.getStepZ() * ez;
                if (cos > facing) {
                    facing = cos;
                    faceAxis = dir.getAxis();
                }
            }
            if (facing < -1) return; // не у неба
            long th = thickness(wx, y, wz, p);
            int t = (int) (th & 7);
            // массив: ломается только опрокидыванием — ему нужна высота над местом
            int height = 0;
            if (t > 3) {
                if (p.leaves() || hl < 1e-6) return;
                while (height < 64 && y + height + 1 <= w.maxY - 1 && !w.air(wx, y + height + 1, wz)) height++;
                if (height < SLENDER) return;
            }
            double psi = d.psi(new Vec3(cx, cy, cz)), pr = reflected(psi);
            int seed = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)));
            // грунт (земля, камень, терракота) ломается тонким элементом, только если с обеих сторон — воздух с небом:
            // гребень, стенка, навес; свод закрытой пещеры, бункера, подвала под толщей земли — нет (перепада нет)
            // (и неполный грунт — тропинка, грядка, грязь: иначе траншеи по тропам и полям)
            boolean thin = t <= 3 && (p.response().kind() != BlockResponse.Kind.GROUND || ends(wx, y, wz, th));
            if (thin) {
                double pressure = facing > 0 ? Math.max(psi, pr * facing) : psi;
                if (p.response().breaksAt(pressure / K[t], seed)) {
                    int axis = (int) (th >> 3 & 3);
                    if (facing > 0 && faceAxis.ordinal() == axis && t > 1) {
                        // пробит с лица: элемент ломается на всю толщину
                        int a = (int) (th >> 5 & 3), b = (int) (th >> 7 & 3);
                        int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
                        for (int i = -b; i <= a; i++) take(wx + dx * i, y + dy * i, wz + dz * i, out);
                    } else {
                        out.add(idx(wx, y, wz));
                    }
                    return;
                }
            }
            if (t <= 3) {
                if (p.leaves() || hl < 1e-6) return;
                while (height < 64 && y + height + 1 <= w.maxY - 1 && !w.air(wx, y + height + 1, wz)) height++;
                if (height < SLENDER) return;
            }
            topple(wx, y, wz, p, pr, ex, ez, height, seed, out, first);
        }

        /** Оба конца ряда толщины — воздух с небом (или пролом). */
        boolean ends(int wx, int y, int wz, long th) {
            int axis = (int) (th >> 3 & 3), a = (int) (th >> 5 & 3), b = (int) (th >> 7 & 3);
            int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
            return open(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1)) && open(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1));
        }

        /** Место в разлом, если оно ломается (жидкость и неломаемое внутри элемента остаются). */
        void take(int wx, int y, int wz, IntArrayList out) {
            if (!inside(wx, y, wz)) return;
            Props p = w.props(wx, y, wz);
            if (p.breakable() && p.full()) out.add(idx(wx, y, wz));
        }

        /**
         * Опрокидывание: ряд полных кубов от грани к взрыву по оси от эпицентра (не толще {@link #MAX_TOPPLE}, небо
         * с обеих сторон) и высота над местом {@code H}: при {@code H/t ≥} {@link #SLENDER} и давлении — ломается весь ряд.
         */
        void topple(int wx, int y, int wz, Props p, double pr, double ex, double ez, int height, int seed, IntArrayList out, boolean first) {
            boolean alongX = Math.abs(ex) >= Math.abs(ez);
            int step = alongX ? (ex > 0 ? -1 : 1) : (ez > 0 ? -1 : 1);
            double cos = alongX ? Math.abs(ex) : Math.abs(ez);
            int fx = alongX ? wx - step : wx, fz = alongX ? wz : wz - step;
            if (!open(fx, y, fz)) return; // лицом не к небу
            // грунт (скалы, столбы, мезы) опрокидывается не толще GROUND_TOPPLE — как до ревью; постройке — до MAX_TOPPLE
            int most = p.response().kind() == BlockResponse.Kind.GROUND ? GROUND_TOPPLE : MAX_TOPPLE;
            int run = 0;
            while (run <= most && full(alongX ? wx + step * run : wx, y, alongX ? wz : wz + step * run)) run++;
            if (run == 0 || run > most) return;
            int bx = alongX ? wx + step * run : wx, bz = alongX ? wz : wz + step * run;
            if (!open(bx, y, bz)) return; // сзади не небо: массив, склон
            // плечо — только та высота, где элемент стоит один, с воздухом сзади: простенок держит перекрытие над этажом
            height = freeHeight(wx, y, wz, bx, bz, height);
            if (height < SLENDER * run) return;
            // за проломами (второй и третий раунды) опрокидывается только столб — ядро, угол, простенок: стену вширь
            // держат поперечные стены и перекрытия
            if (!first) {
                int a = 0, b = 0;
                while (a < most && full(alongX ? wx : wx + a + 1, y, alongX ? wz + a + 1 : wz)) a++;
                while (b < most && full(alongX ? wx : wx - b - 1, y, alongX ? wz - b - 1 : wz)) b++;
                if (a + b + 1 > most) return;
            }
            if (!p.response().breaksAt(pr * cos * height / run / K[3], seed)) return;
            for (int i = 0; i < run; i++) {
                int ax = alongX ? wx + step * i : wx, az = alongX ? wz : wz + step * i;
                if (w.has(ax, az)) take(ax, y, az, out);
            }
        }

        /**
         * Свободная высота над местом (не больше {@code limit}): блоки столбца стоят (не выбиты), а за ними, в столбце
         * {@code bx, bz} за элементом, — воздух или пролом. Перекрытие или перемычка сзади — конец плеча.
         */
        int freeHeight(int wx, int y, int wz, int bx, int bz, int limit) {
            int k = 0;
            while (k < limit) {
                int yy = y + k + 1;
                if (isGone(wx, yy, wz) || w.air(wx, yy, wz)) break;
                if (!isGone(bx, yy, bz) && !w.air(bx, yy, bz)) break;
                k++;
            }
            return k;
        }

        /** Полный куб ряда опрокидывания: жидкость — тоже (за водой не небо), выбитое в раунде 1 — нет. */
        boolean full(int wx, int y, int wz) {
            if (isGone(wx, y, wz)) return false;
            Props p = w.props(wx, y, wz);
            return p.full() || p.fluid();
        }

        /** Твёрдый полный куб для толщины: жидкость — нет, выбитое в раунде 1 — нет. */
        boolean solid(int wx, int y, int wz) {
            return !isGone(wx, y, wz) && w.props(wx, y, wz).full();
        }

        /** Жидкость (не выбитая): за ней перепада нет. */
        boolean wet(int wx, int y, int wz) {
            return inside(wx, y, wz) && !isGone(wx, y, wz) && w.props(wx, y, wz).fluid();
        }

        /**
         * Толщина элемента через место: наименьшая по осям длина ряда твёрдых полных кубов (4 — массив). Ось, у которой
         * оба конца ряда — вода, не в счёт: воду с обеих сторон волна не продавит (стекло в пруду цело), а стекло
         * аквариума с водой только сзади — тонкое: лицо к воздуху получает давление, и хрупкое лопается.
         * Упаковано: биты 0–2 — толщина, 3–4 — ось, 5–6 и 7–8 — сколько кубов ряда в плюс и в минус по оси.
         */
        long thickness(int wx, int y, int wz, Props self) {
            if (!self.full()) return 1 | 1L << 3;
            int best = 4, bestAxis = 0, bestA = 0, bestB = 0;
            for (int axis = 0; axis < 3 && best > 1; axis++) {
                int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
                int a = 0, b = 0;
                while (a < 3 && solid(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1))) a++;
                if (a >= 3) continue;
                while (b < 3 && solid(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1))) b++;
                if (b >= 3 || a + b + 1 >= best) continue;
                if (wet(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1)) && wet(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1))) continue;
                best = a + b + 1;
                bestAxis = axis;
                bestA = a;
                bestB = b;
            }
            return best | (long) bestAxis << 3 | (long) bestA << 5 | (long) bestB << 7;
        }

        /** Воздух под верхом столбцов, связанный с небом через проёмы, — не глубже {@link #SKY_DEPTH}. */
        void skyAir() {
            IntArrayList queue = new IntArrayList();
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int t = w.top(wx, wz);
                    if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                    int low = Math.max(yb, Math.min(t, lowTop[wz * SIDE + wx]) - 1);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        int nx = wx + dir.getStepX(), nz = wz + dir.getStepZ();
                        if (nx < 0 || nx >= SIDE || nz < 0 || nz >= SIDE || !w.has(nx, nz)) continue;
                        // места столбца выше верха соседа смотрят в небо сбоку
                        for (int y = Math.max(low, topOr(nx, nz) + 1); y <= Math.min(t, yt); y++) {
                            int i = idx(wx, y, wz);
                            if (sky[i] != 0 || !w.air(wx, y, wz)) continue;
                            sky[i] = 1;
                            queue.add(i);
                        }
                    }
                    // и снизу открытого неба (навес, арка): воздух прямо под местом над верхом соседнего столбца — выше
                }
            }
            for (int q = 0; q < queue.size(); q++) {
                int i = queue.getInt(q), depth = sky[i];
                if (depth >= SKY_DEPTH) continue;
                int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (!inside(nx, ny, nz) || ny > w.top(nx, nz)) continue;
                    int n = idx(nx, ny, nz);
                    if (sky[n] != 0 || !w.air(nx, ny, nz)) continue;
                    sky[n] = (byte) (depth + 1);
                    queue.add(n);
                }
            }
        }

        /** Стволы на опоре валятся целиком от эпицентра (до волны по блокам; листва потом — без ствола, {@link Collapse}). */
        void fellTrees() {
            for (int wz = LO - ROUND_ONE; wz < HI + ROUND_ONE; wz++) {
                for (int wx = LO - ROUND_ONE; wx < HI + ROUND_ONE; wx++) {
                    int t = w.top(wx, wz);
                    if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                    for (int y = Math.min(t, yt); y > yb; y--) {
                        Props p = w.props(wx, y, wz);
                        if (!p.log() || isGone(wx, y, wz)) continue;
                        Props pb = w.props(wx, y - 1, wz);
                        if (pb.log() || pb.leaves() || !pb.bearing()) continue;
                        int x = w.x0 + wx, z = w.z0 + wz;
                        if (d.psi(new Vec3(x + 0.5, y + 0.5, z + 0.5)) < p.threshold()) continue;
                        // ствол — только дерево: над ним листва (иначе бревенчатая стена или столб)
                        int height = 0;
                        while (height < 24 && y + height <= yt && w.props(wx, y + height, wz).log()) height++;
                        if (!w.props(wx, y + height, wz).leaves() && !nearLeaves(wx, y + height - 1, wz)) continue;
                        BlockState st = w.get(wx, y, wz);
                        for (int i = 0; i < height; i++) set(gone, idx(wx, y + i, wz));
                        if (wx >= LO && wx < HI && wz >= LO && wz < HI) {
                            double dx = x - burst.x, dz = z - burst.z;
                            Direction dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                            trees.add(new long[]{BlockPos.asLong(x, y, z), height, dir.get3DDataValue()});
                            treeLogs.add(st);
                        }
                        break; // ниже комля — опора
                    }
                }
            }
        }

        boolean nearLeaves(int wx, int y, int wz) {
            for (Direction dir : Direction.values()) if (w.props(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ()).leaves()) return true;
            return false;
        }
    }
}
