package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;

import java.util.ArrayList;
import java.util.List;

/**
 * Что ударная волна сломала в одном чанке — первый из двух физических процессов руин (второй — {@link Collapse}):
 * <b>перепад давления ломает тонкое</b>. Считается по исходным блокам окна чанка ({@link RuinWindow}) и больше ни от чего
 * не зависит, поэтому разлом чанка один и тот же, кто бы и когда его ни посчитал (кэш — {@link RuinContext}).
 * <ul>
 * <li>Давление приходит только к граням у воздуха, связанного с небом: над верхом столбцов и не глубже
 * {@link #SKY_DEPTH} блоков внутрь через проёмы. Бункер, пещера без выхода, подвал не трогаются.</li>
 * <li>На гранях к взрыву — отражённое давление {@code P = max(p, Pr·cos θ)}, {@code Pr = 2p(7p₀+4p)/(7p₀+p)}; у стен
 * угол — в горизонтальной плоскости (волна у земли идёт стеблем Маха), у крыш — по вертикали.</li>
 * <li>Блок в элементе толщиной {@code t} ≤ 3 (ряд полных кубов с открытыми концами по наименьшей оси) ломается при
 * {@code P ≥ T·k(t)·jitter}, {@code k = 1, 2, 3.5}; пробитый с лица элемент ломается на всю толщину.</li>
 * <li>Опрокидывание: высокое и узкое (гибкость {@code H/t ≥ 4}, {@code t} ≤ 4 по направлению от взрыва, с небом
 * с обеих сторон) ломается по всему сечению при {@code Pr·cos θ·H/t ≥ T·k(3)·jitter}: так падают стены в два кирпича
 * и ядра высоток, а горы, скалы, мезы (толще 4) стоят.</li>
 * <li>Два раунда: второй видит проломы первого (и воздух за ними: там опрокидывается только столб не шире
 * {@link #MAX_TOPPLE}). Третий — по проломам всех соседей ({@link #remnants}). Деревья валятся
 * до волны по блокам, стволом целиком.</li>
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
    private static final int SLENDER = 4, MAX_TOPPLE = 4;
    private static final int SIDE = RuinWindow.SIDE, LO = 16, HI = 32;

    // ---------------------------------------------------------------- свойства состояний

    /** Как блок ведёт себя в физике руин. */
    record Props(BlockResponse response, boolean full, boolean bearing, int span, boolean fluid, boolean waterlogged, boolean leaves, boolean log) {
        float threshold() {
            return response.thresholdPsi();
        }

        /** Ломается волной (бревно — только стволом дерева, не по блокам). */
        boolean breakable() {
            return response.kind() != BlockResponse.Kind.NONE && !log;
        }
    }

    private static final Reference2ObjectOpenHashMap<BlockState, Props> PROPS = new Reference2ObjectOpenHashMap<>();

    /** Теги перезагрузили: пороги другие (поток сервера — как и всё в руинах). */
    static void clearProps() {
        PROPS.clear();
    }

    static Props props(BlockState st) {
        Props p = PROPS.get(st);
        if (p == null) PROPS.put(st, p = computeProps(st));
        return p;
    }

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
        return new Props(r, full, bearing, span, liquid, waterlogged, leaves, log);
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

    static Blast solve(ServerLevel level, RuinContext ctx, ChunkPos pos) {
        return new Solver(new RuinWindow(level, ctx, pos), ctx.d).run();
    }

    /** Насколько глубоко третий раунд ({@link #remnants}) видит воздух за проломами, блоки. */
    private static final int REMNANT_DEPTH = 2;

    /**
     * Третий раунд — по разлому всех чанков окна (его строит {@link Collapse}): что осталось стоять у проломов соседей
     * (угол дома, стена у перекрытия, ядро за выбитыми этажами), теперь тоньше и открыто с другой стороны — решается
     * заново теми же правилами в столбцах чанка ± {@code margin}. Места — окна: {@code wx | wz << 6 | (y − minY) << 12}.
     */
    static int[] remnants(RuinWindow w, Detonation d, Blast[] blasts, int margin) {
        if (!AirstrikeConfig.SERVER.nukeBlockDamage.get()) return new int[0];
        Solver s = new Solver(w, d);
        s.skyAir();
        IntArrayList breaks = new IntArrayList();
        for (int k = 0; k < 9; k++) {
            if (blasts[k] == null) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int c : blasts[k].removed()) {
                int wx = bx + (c & 15), wz = bz + ((c >> 4) & 15), y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                if (!s.inside(wx, y, wz)) continue;
                int i = s.idx(wx, y, wz);
                Solver.set(s.gone, i);
                breaks.add(i);
            }
        }
        IntArrayList out = new IntArrayList();
        if (!breaks.isEmpty()) s.aroundBreaks(breaks, REMNANT_DEPTH, margin, out);
        int[] res = new int[out.size()];
        for (int q = 0; q < res.length; q++) {
            int i = out.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + s.yb;
            res[q] = wx | wz << 6 | (y - w.minY) << 12;
        }
        return res;
    }

    // ---------------------------------------------------------------- решение

    private static final class Solver {
        final RuinWindow w;
        final Detonation d;
        final Vec3 burst;
        final int yb, yt, h;
        /** Самый низкий верх в окрестности {@link #SKY_DEPTH} + 1: ниже него проёмов с небом нет. */
        final int[] lowTop = new int[SIDE * SIDE];
        /** Глубина воздуха, связанного с небом (1…{@link #SKY_DEPTH}); над верхом столбца — небо без записи. */
        final byte[] sky;
        final long[] gone;
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
            this.sky = new byte[SIDE * SIDE * h];
            this.gone = new long[(SIDE * SIDE * h + 63) >> 6];
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

        Blast run() {
            if (AirstrikeConfig.SERVER.nukeBlockDamage.get()) {
                skyAir();
                if (AirstrikeConfig.SERVER.nukeTreeFall.get()) fellTrees();
                IntArrayList round = new IntArrayList();
                for (int wz = LO - ROUND_ONE; wz < HI + ROUND_ONE; wz++) {
                    for (int wx = LO - ROUND_ONE; wx < HI + ROUND_ONE; wx++) {
                        int t = w.top(wx, wz);
                        if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                        int low = Math.max(yb, Math.min(t, lowTop[wz * SIDE + wx]) - SKY_DEPTH - 1);
                        double pmax = reflected(d.psi(new Vec3(w.x0 + wx + 0.5, Mth.clamp(burst.y, low, t + 1), w.z0 + wz + 0.5)));
                        for (int y = Math.min(t, yt); y >= low; y--) {
                            BlockState st = w.get(wx, y, wz);
                            if (st.isAir() || isGone(wx, y, wz)) continue;
                            Props p = props(st);
                            if (!p.breakable()) continue;
                            // отсечка: блок не сломается и опрокидыванием (гибкость — не больше высоты над ним: 64 блока)
                            if (p.threshold() * 0.85 > pmax * 64 / K[3]) continue;
                            decide(wx, y, wz, st, p, pmax, round, true);
                        }
                    }
                }
                for (int i = 0; i < round.size(); i++) set(gone, round.getInt(i));
                // проломы первого раунда открывают небу воздух за ними — не глубже SKY_DEPTH (зал без окон, ядро за фасадом);
                // второй раунд — в самом чанке: блоки у проломов и у открытого ими воздуха
                IntArrayList second = new IntArrayList();
                aroundBreaks(round, SKY_DEPTH, 0, second);
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
         * Воздух за проломами {@code breaks} становится воздухом с небом (не глубже {@code depth}), и блоки у проломов
         * и у этого воздуха в столбцах чанка ± {@code margin} решаются заново — в {@code out}.
         */
        void aroundBreaks(IntArrayList breaks, int depth, int margin, IntArrayList out) {
            IntArrayList reach = new IntArrayList(breaks);
            IntOpenHashSet seen = new IntOpenHashSet();
            for (int q = 0; q < reach.size(); q++) {
                int c = reach.getInt(q), wx = c % SIDE, wz = (c / SIDE) % SIDE, y = c / (SIDE * SIDE) + yb;
                int dc = bit(gone, c) ? 0 : sky[c];
                if (dc >= depth) continue;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (!inside(nx, ny, nz) || ny > w.top(nx, nz)) continue;
                    int n = idx(nx, ny, nz);
                    if (bit(gone, n) || sky[n] != 0 || !w.get(nx, ny, nz).isAir() || !seen.add(n)) continue;
                    sky[n] = (byte) (dc + 1);
                    reach.add(n);
                }
            }
            seen.clear();
            for (int q = 0; q < reach.size(); q++) {
                int c = reach.getInt(q), wx = c % SIDE, wz = (c / SIDE) % SIDE, y = c / (SIDE * SIDE) + yb;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (nx < LO - margin || nx >= HI + margin || nz < LO - margin || nz >= HI + margin || ny < yb || ny > yt) continue;
                    int n = idx(nx, ny, nz);
                    if (bit(gone, n) || !seen.add(n)) continue;
                    BlockState st = w.get(nx, ny, nz);
                    if (st.isAir()) continue;
                    Props p = props(st);
                    if (!p.breakable()) continue;
                    decide(nx, ny, nz, st, p, Double.MAX_VALUE, out, false);
                }
            }
        }

        /** Сломать ли блок (толщина, отражение) и опрокинуть ли элемент; сломанные места — в {@code out}. */
        void decide(int wx, int y, int wz, BlockState st, Props p, double pmax, IntArrayList out, boolean first) {
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
                while (height < 64 && y + height + 1 <= w.maxY - 1 && !w.get(wx, y + height + 1, wz).isAir()) height++;
                if (height < SLENDER) return;
            }
            double psi = d.psi(new Vec3(cx, cy, cz)), pr = reflected(psi);
            int seed = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)));
            // грунт (земля, камень, терракота) ломается тонким элементом, только если с обеих сторон — воздух с небом:
            // гребень, стенка, навес; свод закрытой пещеры, бункера, подвала под толщей земли — нет (перепада нет)
            boolean thin = t <= 3 && (p.response().kind() != BlockResponse.Kind.GROUND || t == 1 && !p.full() || ends(wx, y, wz, th));
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
                while (height < 64 && y + height + 1 <= w.maxY - 1 && !w.get(wx, y + height + 1, wz).isAir()) height++;
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
            Props p = props(w.get(wx, y, wz));
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
            int run = 0;
            while (run <= MAX_TOPPLE && full(alongX ? wx + step * run : wx, y, alongX ? wz : wz + step * run)) run++;
            if (run == 0 || run > MAX_TOPPLE) return;
            if (!open(alongX ? wx + step * run : wx, y, alongX ? wz : wz + step * run)) return; // сзади не небо: массив, склон
            if (height < SLENDER * run) return;
            // за проломами (второй и третий раунды) опрокидывается только столб — ядро, угол, простенок: стену вширь
            // держат поперечные стены и перекрытия
            if (!first) {
                int a = 0, b = 0;
                while (a < MAX_TOPPLE && full(alongX ? wx : wx + a + 1, y, alongX ? wz + a + 1 : wz)) a++;
                while (b < MAX_TOPPLE && full(alongX ? wx : wx - b - 1, y, alongX ? wz - b - 1 : wz)) b++;
                if (a + b + 1 > MAX_TOPPLE) return;
            }
            if (!p.response().breaksAt(pr * cos * height / run / K[3], seed)) return;
            for (int i = 0; i < run; i++) {
                int ax = alongX ? wx + step * i : wx, az = alongX ? wz : wz + step * i;
                if (w.has(ax, az)) take(ax, y, az, out);
            }
        }

        /** Полный куб для толщины: жидкость — тоже (вода не даёт перепада), выбитое в раунде 1 — нет. */
        boolean full(int wx, int y, int wz) {
            if (isGone(wx, y, wz)) return false;
            Props p = props(w.get(wx, y, wz));
            return p.full() || p.fluid();
        }

        /**
         * Толщина элемента через место: наименьшая по осям длина ряда полных кубов с открытыми концами (4 — массив).
         * Упаковано: биты 0–2 — толщина, 3–4 — ось, 5–6 и 7–8 — сколько кубов ряда в плюс и в минус по оси.
         */
        long thickness(int wx, int y, int wz, Props self) {
            if (!self.full()) return 1 | 1L << 3;
            int best = 4, bestAxis = 0, bestA = 0, bestB = 0;
            for (int axis = 0; axis < 3 && best > 1; axis++) {
                int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
                int a = 0, b = 0;
                while (a < 3 && full(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1))) a++;
                if (a >= 3) continue;
                while (b < 3 && full(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1))) b++;
                if (b >= 3 || a + b + 1 >= best) continue;
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
                            if (sky[i] != 0 || !w.get(wx, y, wz).isAir()) continue;
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
                    if (sky[n] != 0 || !w.get(nx, ny, nz).isAir()) continue;
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
                        BlockState st = w.get(wx, y, wz);
                        if (!st.is(BlockTags.LOGS) || isGone(wx, y, wz)) continue;
                        BlockState below = w.get(wx, y - 1, wz);
                        Props pb = props(below);
                        if (pb.log() || pb.leaves() || !pb.bearing()) continue;
                        int x = w.x0 + wx, z = w.z0 + wz;
                        if (d.psi(new Vec3(x + 0.5, y + 0.5, z + 0.5)) < props(st).threshold()) continue;
                        // ствол — только дерево: над ним листва (иначе бревенчатая стена или столб)
                        int height = 0;
                        while (height < 24 && y + height <= yt && w.get(wx, y + height, wz).is(BlockTags.LOGS)) height++;
                        if (!props(w.get(wx, y + height, wz)).leaves() && !nearLeaves(wx, y + height - 1, wz)) continue;
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
            for (Direction dir : Direction.values()) if (props(w.get(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ())).leaves()) return true;
            return false;
        }
    }
}
