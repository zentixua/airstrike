package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.util.Terrain;

/**
 * Руины одного подрыва по всем чанкам: что сломала волна в каждом чанке ({@link Blast}, по исходным блокам его окна) и
 * чьи руины уже стоят (их старые блоки — исходный мир для соседей). План чанка — функция исходных блоков вокруг него,
 * поэтому он один и тот же заранее и на месте, в каком бы порядке ни шли чанки.
 * <p>
 * Живёт, пока идёт подрыв (подготовка во время полёта, потом очередь руин); что больше никому не нужно — отпускает:
 * разлом чанка — когда планы есть у всех его соседей, старые блоки стоящих руин — когда руины стоят во всём квадрате
 * 5×5 вокруг (их читают окна соседей и соседей соседей).
 */
final class RuinContext {
    /** По чему строятся руины (у подготовки — место без номера; у подрыва — он сам). */
    final Detonation d;
    /** Разломы чанков: их считают и фоновые задачи ({@link RuinWorkers}), поэтому карта — общая. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Blast> blasts = new java.util.concurrent.ConcurrentHashMap<>();
    /** Снимки чанков (поток сервера): пока их читают окна неготовых планов. */
    private final Long2ObjectOpenHashMap<ChunkShot> shots = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Applied> applied = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet planned = new LongOpenHashSet(), stood = new LongOpenHashSet();
    /** Когда контекст в последний раз брали (игровое время) — очередь забывает давно брошенные. */
    long used;

    /**
     * Руины чанка стоят: план (старые блоки его мест; null — отпущены), счётчик изменений чанка при подмене и карта
     * высот {@code MOTION_BLOCKING} до неё (первый воздух; по ней тень светового импульса — он шёл до волны).
     */
    record Applied(@Nullable RuinPlan plan, long edits, int id, short[] motion) {}

    RuinContext(Detonation d) {
        this.d = d;
    }

    @Nullable
    Applied applied(long chunk) {
        return applied.get(chunk);
    }

    /**
     * Счётчик изменений чанка для окна плана: у чанка с руинами — на момент подмены (дальше его меняют сами руины:
     * огонь, текущая вода, брёвна), у остальных — живой.
     */
    long edits(long pos, LevelChunk chunk) {
        Applied a = applied.get(pos);
        return a != null ? a.edits : RuinPlan.edits(chunk);
    }

    /** Снимок чанка, если он в памяти целиком (поток сервера): из кэша, пока верен, иначе — новый. */
    @Nullable
    ChunkShot shot(ServerLevel level, int cx, int cz) {
        LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
        if (c == null) return null;
        long key = ChunkPos.asLong(cx, cz);
        long edits = edits(key, c);
        Applied a = applied.get(key);
        ChunkShot s = shots.get(key);
        if (s == null || !s.current(c, edits, a)) shots.put(key, s = ChunkShot.take(c, edits, a));
        return s;
    }

    /** Сколько байт держат снимки (память подрыва). */
    long shotBytes() {
        long sum = 0;
        for (ChunkShot s : shots.values()) sum += s.bytes;
        return sum;
    }

    /**
     * Всё, из чего строится план чанка без мира, — снимки квадрата 5×5 вокруг ({@link RuinPlanner#REACH}: окна чанка и
     * его соседей), таблица свойств и настройки, снятые в потоке сервера. Разломы, которых нет в кэше, план посчитает
     * сам — в том потоке, где его строят.
     */
    record Grid(RuinContext ctx, ChunkPos centre, int minY, int maxY, ChunkShot[] shots, Blast.PropsView view, boolean blockDamage, boolean treeFall) {
        static final int R = RuinPlanner.REACH, SIDE = 2 * R + 1;

        @Nullable
        ChunkShot at(int dx, int dz) {
            return shots[(dz + R) * SIDE + dx + R];
        }

        /** Снимки окна чанка со сдвигом {@code dx, dz} от центра (не дальше 1). */
        ChunkShot[] window(int dx, int dz) {
            ChunkShot[] out = new ChunkShot[9];
            for (int k = 0; k < 9; k++) out[k] = at(dx + k % 3 - 1, dz + k / 3 - 1);
            return out;
        }

        RuinWindow windowOf(int dx, int dz) {
            return new RuinWindow(new ChunkPos(centre.x + dx, centre.z + dz), minY, maxY, window(dx, dz), view);
        }

        /** Разлом чанка со сдвигом от центра: из кэша подрыва, если построен по тем же снимкам, иначе — заново и в кэш. */
        Blast blast(int dx, int dz) {
            ChunkPos pos = new ChunkPos(centre.x + dx, centre.z + dz);
            ChunkShot[] w = window(dx, dz);
            Blast b = ctx.blasts.get(pos.toLong());
            if (b != null && b.stamp().same(RuinWindow.Stamp.of(w))) return b;
            b = Blast.solve(new RuinWindow(pos, minY, maxY, w, view), ctx.d, blockDamage, treeFall);
            ctx.blasts.put(pos.toLong(), b);
            return b;
        }

        /** Разлом чанка со сдвигом уже в кэше по тем же снимкам. */
        boolean hasBlast(int dx, int dz) {
            Blast b = ctx.blasts.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
            return b != null && b.stamp().same(RuinWindow.Stamp.of(window(dx, dz)));
        }
    }

    /**
     * Снимки для плана чанка (поток сервера): квадрат {@link RuinPlanner#REACH} загружен целиком. Таблица свойств — та,
     * что опубликована после снимков: фоновый план читает только её ({@code view} — для потока сервера).
     */
    Grid grid(ServerLevel level, ChunkPos centre, boolean background) {
        ChunkShot[] g = new ChunkShot[Grid.SIDE * Grid.SIDE];
        for (int dz = -Grid.R; dz <= Grid.R; dz++) {
            for (int dx = -Grid.R; dx <= Grid.R; dx++) g[(dz + Grid.R) * Grid.SIDE + dx + Grid.R] = shot(level, centre.x + dx, centre.z + dz);
        }
        Blast.PropsView view = background ? Blast.publish() : Blast.SERVER;
        return new Grid(this, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(), g, view,
                AirstrikeConfig.SERVER.nukeBlockDamage.get(), AirstrikeConfig.SERVER.nukeTreeFall.get());
    }

    /**
     * Разломы окна чанка (он и загруженные соседи) — не больше одного за вызов (поток сервера): разлом холодного
     * соседа — отдельная единица работы, а не девять в одной. true — все в кэше и верны (план чанка — одна единица),
     * false — посчитан один.
     */
    boolean blastsReady(ServerLevel level, ChunkPos pos) {
        Grid g = grid(level, pos, false);
        for (int k = 0; k < 9; k++) {
            int dx = k % 3 - 1, dz = k / 3 - 1;
            if (g.at(dx, dz) == null || g.hasBlast(dx, dz)) continue;
            g.blast(dx, dz);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- фоновые планы

    /** Фоновый план чанка: задача и чанк, по которому сняты снимки ({@code identityHashCode}). */
    private record Task(java.util.concurrent.Future<Collapse> future, int chunkId) {}

    private final Long2ObjectOpenHashMap<Task> tasks = new Long2ObjectOpenHashMap<>();
    /** Чанки, чей фоновый план упал с ошибкой: их план — в потоке сервера. */
    private final LongOpenHashSet failed = new LongOpenHashSet();
    private int failures;

    /**
     * Отдать план чанка фоновым потокам (поток сервера; чанк и квадрат {@link RuinPlanner#REACH} вокруг загружены
     * целиком): снимки — сейчас, разломы и обрушение — в фоне. false — задач и так много (у подрыва не больше
     * {@link RuinWorkers#capacity}, включая готовые, но не забранные), спросить позже.
     */
    boolean submit(ServerLevel level, LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        if (tasks.containsKey(key)) return true;
        if (tasks.size() >= RuinWorkers.capacity()) return false;
        Grid g = grid(level, chunk.getPos(), true);
        tasks.put(key, new Task(RuinWorkers.submit(() -> Collapse.solve(g)), System.identityHashCode(chunk)));
        return true;
    }

    /** У чанка есть фоновый план в работе или готовый. */
    boolean running(long chunk) {
        return tasks.containsKey(chunk);
    }

    /** Сколько фоновых планов в работе или готовы и не забраны. */
    int tasks() {
        return tasks.size();
    }

    /** Фоновый план чанка готов (забрать можно одной единицей работы). */
    boolean done(long chunk) {
        Task t = tasks.get(chunk);
        return t != null && t.future.isDone();
    }

    /** Фоновый план чанка упал: строить в потоке сервера. */
    boolean failed(long chunk) {
        return failed.contains(chunk);
    }

    /**
     * Готовый фоновый план чанка (поток сервера): руины достроены по живому чанку ({@link RuinPlanner#finish}). null —
     * ещё строится, чанк с тех пор перезагрузили (задача отдана заново) или план упал ({@link #failed}).
     */
    @Nullable
    RuinPlan collect(ServerLevel level, LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        Task t = tasks.get(key);
        if (t == null || !t.future.isDone()) return null;
        tasks.remove(key);
        Collapse c;
        try {
            c = t.future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            failed.add(key);
            // ошибка решателя повторится на многих чанках: в лог — первые
            if (failures++ < 3) Airstrike.LOG.error("Руины подрыва №{}: фоновый план чанка {} упал; строится в потоке сервера", d.id(), chunk.getPos(), e.getCause());
            return null;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            return null;
        }
        if (System.identityHashCode(chunk) != t.chunkId) {
            // чанк выгрузили и загрузили снова: снимки не того чанка
            submit(level, chunk);
            return null;
        }
        return RuinPlanner.finish(level, this, chunk, c);
    }

    /** Чанк выгружен: его фоновый план и снимок больше не верны. */
    void forget(long chunk) {
        Task t = tasks.remove(chunk);
        if (t != null) t.future.cancel(false);
        shots.remove(chunk);
    }

    /** Фоновые планы больше не нужны (отбой, выгрузка мира): задачи бросаются. */
    void cancelTasks() {
        for (Task t : tasks.values()) t.future.cancel(false);
        tasks.clear();
    }

    /** План чанка построен: разломы, нужные только планам вокруг, можно отпустить. */
    void planned(ChunkPos pos) {
        planned.add(pos.toLong());
        forget(pos, 1, planned, k -> blasts.remove(k));
        forget(pos, RuinPlanner.REACH, planned, shots::remove);
    }

    /** Руины чанка встали по плану. */
    void applied(LevelChunk chunk, RuinPlan plan, long edits, short[] motion) {
        ChunkPos pos = chunk.getPos();
        applied.put(pos.toLong(), new Applied(plan, edits, System.identityHashCode(chunk), motion));
        planned.add(pos.toLong());
        stood.add(pos.toLong());
        forget(pos, 1, stood, k -> blasts.remove(k));
        forget(pos, RuinPlanner.REACH, stood, shots::remove);
        forget(pos, 2, stood, k -> {
            Applied a = applied.get(k);
            if (a != null && a.plan != null) applied.put(k, new Applied(null, a.edits, a.id, a.motion));
        });
    }

    /** Чанки в радиусе r от pos, у которых весь квадрат радиуса r в {@code done}, — в {@code drop}. */
    private static void forget(ChunkPos pos, int r, LongOpenHashSet done, java.util.function.LongConsumer drop) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = pos.x + dx, z = pos.z + dz;
                boolean all = true;
                for (int ex = -r; all && ex <= r; ex++) {
                    for (int ez = -r; all && ez <= r; ez++) all = done.contains(ChunkPos.asLong(x + ex, z + ez));
                }
                if (all) drop.accept(ChunkPos.asLong(x, z));
            }
        }
    }

    /**
     * Световой импульс на месте, каким его видел мир до волны: у чанков, чьи руины уже стоят, — их карта высот до руин.
     */
    double lit(ServerLevel level, net.minecraft.core.BlockPos pos) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        boolean seen = ThermalShadow.visible(d.burst(), p, (x, z) -> {
            Applied a = applied.get(ChunkPos.asLong(x >> 4, z >> 4));
            int now = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            return a == null ? now : Math.max(now, a.motion[(z & 15) << 4 | (x & 15)]);
        });
        return seen ? d.fluence(p) : 0;
    }
}
