package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;

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
    /** Снимки чанков (поток сервера): пока их читают окна неготовых планов. Чанки не в памяти — с диска ({@link DiskShots}). */
    private final Long2ObjectOpenHashMap<ChunkShot> shots = new Long2ObjectOpenHashMap<>();
    /**
     * Карта высот {@code MOTION_BLOCKING} (первый воздух) чанков, каким мир был до волны, — по ней тень светового импульса
     * ({@link #lit}) в любом потоке: чанки в памяти — по живому чанку (у чанка с руинами — до руин), не в памяти — с диска.
     * Запись — только в потоке сервера, значение не меняется; чего нет — рельефа нет (как у незагруженного чанка).
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, int[]> heights = new java.util.concurrent.ConcurrentHashMap<>();
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
        long key = ChunkPos.asLong(cx, cz);
        if (c == null) {
            // не в памяти: снимок с диска, если его прочитали (и таблицу свойств с тех пор не сбрасывали)
            ChunkShot disk = shots.get(key);
            return disk != null && disk.fromDisk() && disk.fresh() ? disk : null;
        }
        long edits = edits(key, c);
        Applied a = applied.get(key);
        ChunkShot s = shots.get(key);
        if (s == null || !s.current(c, edits, a)) putShot(key, s = ChunkShot.take(c, edits, a));
        return s;
    }

    /**
     * Чанк, прочитанный с диска (поток сервера): снимок — если снимка из памяти нет или чанк сейчас не в памяти целиком
     * (тогда снимок из памяти всё равно не берётся), и его карта высот — для тени светового импульса.
     */
    void putDisk(ServerLevel level, DiskShots.Read read) {
        long key = read.pos().toLong();
        ChunkShot now = shots.get(key);
        if (now == null || now.fromDisk() || level.getChunkSource().getChunkNow(read.pos().x, read.pos().z) == null) {
            putShot(key, ChunkShot.fromDisk(read));
        }
        putHeights(key, read.motion());
    }

    /** Снимок чанка для плана есть: чанк в памяти целиком или прочитан с диска (и таблицу свойств с тех пор не сбрасывали). */
    boolean shotAvailable(ServerLevel level, long chunk) {
        if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) return true;
        ChunkShot s = shots.get(chunk);
        return s != null && s.fromDisk() && s.fresh();
    }

    /**
     * Плана у чанка не будет (у подготовки: за краем тяжёлой зоны или не с диска): снимки его соседей не ждут его плана.
     * Если план ему всё же построят (очередь руин после волны), снимки возьмутся заново.
     */
    void noPlan(long chunk) {
        if (planned.add(chunk)) {
            ChunkPos pos = new ChunkPos(chunk);
            forget(pos, 1, planned, k -> blasts.remove(k));
            forget(pos, RuinPlanner.REACH, planned, this::dropShot);
        }
    }

    /** Как {@link #noPlan}, пока снимков вокруг ещё нет (отпускать нечего). */
    void markNoPlan(long chunk) {
        planned.add(chunk);
    }

    /** Когда готов план чанка (игровое время), −1 — ещё нет: для строки «дальние кольца». */
    long doneAt(long chunk) {
        return doneAt.getOrDefault(chunk, -1L);
    }

    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap doneAt = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Игровое время по последнему {@link #expire}. */
    private long now;

    private long shotBytes;

    private void putShot(long key, ChunkShot shot) {
        ChunkShot was = shots.put(key, shot);
        shotBytes += shot.bytes - (was == null ? 0 : was.bytes);
    }

    private void dropShot(long key) {
        ChunkShot was = shots.remove(key);
        if (was != null) shotBytes -= was.bytes;
    }

    /** Есть ли снимок чанка (из памяти или с диска). */
    boolean hasShot(long chunk) {
        return shots.containsKey(chunk);
    }

    /** Сколько снимков держится (память подрыва). */
    int shotCount() {
        return shots.size();
    }

    // ---------------------------------------------------------------- тень светового импульса

    /** Карта высот чанка с диска (поток сервера): первый воздух {@code MOTION_BLOCKING}, если её ещё нет. */
    void putHeights(long chunk, int[] firstAir) {
        heights.putIfAbsent(chunk, firstAir);
    }

    /** Карта высот чанка в памяти (поток сервера), если её ещё нет: у чанка с руинами — выше из «до руин» и живой. */
    private void captureHeights(ServerLevel level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        if (heights.containsKey(key)) return;
        LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
        if (c == null) return;
        Applied a = applied.get(key);
        int[] h = new int[256];
        for (int col = 0; col < 256; col++) {
            int now = c.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, col & 15, col >> 4) + 1;
            h[col] = a == null ? now : Math.max(now, a.motion[col]);
        }
        heights.put(key, h);
    }

    /**
     * Карты высот чанков между эпицентром и чанком (поток сервера, до плана): лучи к местам чанка идут над ними. Полоса
     * шириной в три чанка по прямой от эпицентра.
     */
    private void captureBand(ServerLevel level, ChunkPos to) {
        int bx = net.minecraft.util.Mth.floor(d.burst().x) >> 4, bz = net.minecraft.util.Mth.floor(d.burst().z) >> 4;
        int steps = Math.max(Math.abs(to.x - bx), Math.abs(to.z - bz));
        for (int i = 0; i <= steps; i++) {
            int cx = bx + (int) Math.round((to.x - bx) * (double) i / Math.max(1, steps));
            int cz = bz + (int) Math.round((to.z - bz) * (double) i / Math.max(1, steps));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) captureHeights(level, cx + dx, cz + dz);
        }
    }

    /** Первый воздух над {@code MOTION_BLOCKING} до волны (любой поток); чанка нет в картах — низ мира. */
    private int height(int x, int z, int minY) {
        int[] h = heights.get(ChunkPos.asLong(x >> 4, z >> 4));
        return h == null ? minY : h[(z & 15) << 4 | (x & 15)];
    }

    /** Световой импульс на месте, каким его видел мир до волны (любой поток). */
    double lit(net.minecraft.core.BlockPos pos, int minY) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return ThermalShadow.visible(d.burst(), p, (x, z) -> height(x, z, minY)) ? d.fluence(p) : 0;
    }

    /** Сколько байт держат снимки (память подрыва). */
    /** Сколько байт держат снимки и карты высот подрыва (память подрыва, к пределу планов). */
    long shotBytes() {
        return shotBytes + heights.size() * 1100L;
    }

    /**
     * Всё, из чего строится план чанка без мира, — снимки квадрата 5×5 вокруг ({@link RuinPlanner#REACH}: окна чанка и
     * его соседей), таблица свойств и настройки, снятые в потоке сервера. Разломы, которых нет в кэше, план посчитает
     * сам — в том потоке, где его строят.
     */
    record Grid(RuinContext ctx, ChunkPos centre, int minY, int maxY, ChunkShot[] shots, Blast.PropsView view, boolean blockDamage, boolean treeFall,
                boolean fires) {
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

        /** Световой импульс на месте, каким его видел мир до волны. */
        double lit(net.minecraft.core.BlockPos pos) {
            return ctx.lit(pos, minY);
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
        captureBand(level, centre);
        RuinPlanner.ensureOwnStates();
        Blast.PropsView view = background ? Blast.publish() : Blast.SERVER;
        return new Grid(this, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(), g, view,
                AirstrikeConfig.SERVER.nukeBlockDamage.get(), AirstrikeConfig.SERVER.nukeTreeFall.get(), AirstrikeConfig.SERVER.nukeFires.get());
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

    /**
     * Фоновый план чанка: задача (разломы, обрушение и достройка — всё в фоне), чанк, по которому сняты снимки
     * ({@code identityHashCode}; 0 — снимок с диска), и когда отдана (игровое время).
     */
    private record Task(java.util.concurrent.Future<RuinPlan> future, int chunkId, long since) {}

    /** Сколько тиков ждать фоновый план: дольше — задача брошена, план строит поток сервера. */
    static final int TASK_AGE_LIMIT = 600;

    private final Long2ObjectOpenHashMap<Task> tasks = new Long2ObjectOpenHashMap<>();
    /** Чанки, чей фоновый план упал с ошибкой или завис: их план — в потоке сервера. */
    private final LongOpenHashSet failed = new LongOpenHashSet();
    private int failures;

    /**
     * Отдать план чанка фоновым потокам (поток сервера; чанк и квадрат {@link RuinPlanner#REACH} вокруг загружены
     * целиком): снимки — сейчас, разломы, обрушение и достройка — в фоне. false — задач и так много
     * ({@link RuinWorkers#admit}), спросить позже.
     */
    boolean submit(ServerLevel level, LevelChunk chunk) {
        return submit(level, chunk.getPos(), System.identityHashCode(chunk));
    }

    /**
     * Отдать фоновым потокам план чанка, которого нет в памяти (поток сервера): снимки квадрата
     * {@link RuinPlanner#REACH} прочитаны с диска ({@link #putDisk}) или сняты с чанков в памяти.
     */
    boolean submitDisk(ServerLevel level, ChunkPos pos) {
        return submit(level, pos, 0);
    }

    private boolean submit(ServerLevel level, ChunkPos pos, int chunkId) {
        long key = pos.toLong();
        if (tasks.containsKey(key)) return true;
        // и в пуле, и готовых, но не забранных, — не без края: готовый план держит память
        if (!RuinWorkers.admit() || tasks.size() >= 2 * RuinWorkers.capacity()) return false;
        Grid g = grid(level, pos, true);
        if (g.at(0, 0) == null) throw new IllegalStateException("нет снимка чанка " + pos);
        tasks.put(key, new Task(RuinWorkers.submit(() -> {
            RuinWorkers.checkStop();
            Collapse c = Collapse.solve(g);
            RuinWorkers.checkStop();
            return RuinPlanner.finish(g, c);
        }), chunkId, level.getGameTime()));
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

    /** Фоновый план чанка упал или завис: строить в потоке сервера. */
    boolean failed(long chunk) {
        return failed.contains(chunk);
    }

    /**
     * Фоновые планы дольше {@link #TASK_AGE_LIMIT} тиков (поток сервера, раз в тик): задача бросается
     * ({@code cancel(true)}: поток решателя, если застрял, получает прерывание), чанк — в {@link #failed}, его план строит
     * поток сервера.
     */
    void expire(long now) {
        this.now = now;
        if (tasks.isEmpty()) return;
        var it = tasks.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            Task t = e.getValue();
            if (t.future.isDone() || now - t.since < TASK_AGE_LIMIT) continue;
            t.future.cancel(true);
            it.remove();
            failed.add(e.getLongKey());
            RuinWorkers.expired();
            Airstrike.LOG.warn("Руины подрыва №{}: фоновый план чанка {} не готов за {} тиков — брошен, строится в потоке сервера",
                    d.id(), new ChunkPos(e.getLongKey()), TASK_AGE_LIMIT);
        }
    }

    /**
     * Готовый фоновый план чанка (поток сервера). null — ещё строится, чанк с тех пор перезагрузили (задача отдана
     * заново) или план упал ({@link #failed}). План по снимку с диска годится и для чанка, который с тех пор загрузили:
     * подмена сверит старые состояния мест плана и посчитает столбцы заново ({@link RuinPlan#apply}).
     */
    @Nullable
    RuinPlan collect(ServerLevel level, LevelChunk chunk) {
        Task t = tasks.get(chunk.getPos().toLong());
        if (t != null && t.chunkId != 0 && t.future.isDone() && System.identityHashCode(chunk) != t.chunkId) {
            // чанк выгрузили и загрузили снова: снимки не того чанка
            tasks.remove(chunk.getPos().toLong());
            submit(level, chunk);
            return null;
        }
        return collect(chunk.getPos());
    }

    /** Готовый фоновый план чанка, где бы чанк ни был (поток сервера): см. {@link #collect(ServerLevel, LevelChunk)}. */
    @Nullable
    RuinPlan collect(ChunkPos pos) {
        long key = pos.toLong();
        Task t = tasks.get(key);
        if (t == null || !t.future.isDone()) return null;
        tasks.remove(key);
        RuinPlan plan;
        try {
            plan = t.future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            failed.add(key);
            // ошибка решателя повторится на многих чанках: в лог — первые
            if (failures++ < 3) Airstrike.LOG.error("Руины подрыва №{}: фоновый план чанка {} упал; строится в потоке сервера", d.id(), pos, e.getCause());
            return null;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            return null;
        }
        planned(pos);
        doneAt.put(key, now);
        return plan;
    }

    /** Чанк выгружен: его фоновый план по живому чанку и снимок больше не верны (план по снимку с диска — верен). */
    void forget(long chunk) {
        Task t = tasks.get(chunk);
        if (t != null && t.chunkId != 0) {
            tasks.remove(chunk);
            t.future.cancel(false);
        }
        dropShot(chunk);
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
        forget(pos, RuinPlanner.REACH, planned, this::dropShot);
    }

    /** Руины чанка встали по плану. */
    void applied(LevelChunk chunk, RuinPlan plan, long edits, short[] motion) {
        ChunkPos pos = chunk.getPos();
        applied.put(pos.toLong(), new Applied(plan, edits, System.identityHashCode(chunk), motion));
        planned.add(pos.toLong());
        stood.add(pos.toLong());
        forget(pos, 1, stood, k -> blasts.remove(k));
        forget(pos, RuinPlanner.REACH, stood, this::dropShot);
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

}
