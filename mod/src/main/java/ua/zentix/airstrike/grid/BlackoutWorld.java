package ua.zentix.airstrike.grid;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.DistantHorizons;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.world.NuclearTickets;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.util.Terrain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Блэкаут в мире измерения: приводит чанки к состоянию сети ({@link PowerGrid}) под бюджетом времени тика.
 * Не сохраняется: всё выводится из отключений, отметок на чанках и самих блоков.
 * <ul>
 *   <li>Каскад ({@link Sweep}): чанки района отключения по времени, когда до их квартала доходит отключение (или
 *   возврат света). Загруженный чанк переводится сразу (лампы — в двойников или обратно); чанк вне загруженного
 *   мира не грузится — он переведётся, когда его загрузят ({@link #onChunkLoad}), а вдали его LOD в Distant
 *   Horizons обновляется копией с диска ({@link DiskChunks}), так кварталы гаснут по каскаду до горизонта.</li>
 *   <li>Чанк переводится, только когда загружены и соседи: Sable на каждое изменение блока читает соседей и на
 *   краю загруженного мира грузил бы их синхронно ({@link NuclearTickets#neighbourhoodLoaded}).</li>
 *   <li>Чанков блэкаут не грузит никогда: на диске погашенных ламп нет ({@link ChunkSaves}), и чанк, загруженный
 *   в тёмном квартале, гаснет сам при загрузке.</li>
 * </ul>
 */
public final class BlackoutWorld {
    /** Через сколько тиков снова проверить загруженный чанк, у которого не загружены соседи. */
    private static final int NEIGHBOUR_RETRY = 40;
    /** Ламп за единицу работы: у каждой — setBlock со светом, клиентами и Sable (≈10–30 мкс). */
    static final int LAMPS_PER_UNIT = 32;
    /** Копий с диска в работе одновременно: чтение идёт в очереди ввода-вывода вместе с загрузкой чанков игроков. */
    private static final int MAX_READS = 8;
    /** Звук квартала — игрокам ближе этого (блоки по горизонтали). */
    private static final double DISTRICT_SOUND_RANGE = 96;

    /** Каскад одного отключения (или возврата света): чанки района по времени прихода. */
    private static final class Sweep {
        final Outage outage;
        final boolean restore;
        /** До этого времени каскад уже прошёл до перезапуска: копии для LOD не повторяются. */
        final long doneUntil;
        final int minX, maxX, maxZ;
        /** Следующий ряд чанков (z), который ещё не разобран. */
        int row;
        final Long2ObjectOpenHashMap<LongArrayList> byDue = new Long2ObjectOpenHashMap<>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        /** Следующий момент, чанки которого ещё не выданы. */
        long cursor = Long.MIN_VALUE;

        Sweep(Outage outage, boolean restore, long doneUntil) {
            this.outage = outage;
            this.restore = restore;
            this.doneUntil = doneUntil;
            // квартал внутри радиуса может задевать чанки за ним: запас в полторы клетки
            double r = outage.radius() + Districts.CELL * 16 * 1.5;
            minX = (int) Math.floor((outage.x() - r) / 16);
            maxX = (int) Math.floor((outage.x() + r) / 16);
            row = (int) Math.floor((outage.z() - r) / 16);
            maxZ = (int) Math.floor((outage.z() + r) / 16);
        }

        boolean built() {
            return row > maxZ;
        }

        /** Разобрать один ряд чанков: у каждого чанка района — время, когда до его квартала доходит каскад. */
        void buildRow() {
            int z = row++;
            for (int x = minX; x <= maxX; x++) {
                long district = Districts.of(x, z);
                if (!outage.covers(district)) continue;
                long due = restore ? outage.lightAt(district) : outage.darkAt(district);
                if (due == Outage.NEVER) continue;
                byDue.computeIfAbsent(due, k -> new LongArrayList()).add(ChunkPos.asLong(x, z));
                first = Math.min(first, due);
                last = Math.max(last, due);
            }
            if (built() && cursor == Long.MIN_VALUE) cursor = first;
        }

        boolean done() {
            return built() && cursor > last;
        }
    }

    /** Копия с диска готова (или не нужна): в поток сервера. */
    private record Read(long chunk, @Nullable LevelChunk copy, boolean dark, @Nullable Throwable error) {}

    private final List<Sweep> sweeps = new ArrayList<>();
    /** Чанки, которые пора перевести: из каскада, загрузки, поставленной лампы. */
    private final LongArrayFIFOQueue ready = new LongArrayFIFOQueue();
    /** Загруженные чанки без загруженных соседей: когда проверить снова. */
    private final PriorityQueue<long[]> retry = new PriorityQueue<>(Comparator.comparingLong(a -> a[1]));
    /** Чанки в {@link #ready} и {@link #retry}: каждый — один раз, сколько бы поводов ни пришло. */
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final LongArrayFIFOQueue reads = new LongArrayFIFOQueue();
    /** Чанки в {@link #reads}: копия читается один раз и по сети на момент чтения. */
    private final LongOpenHashSet readsQueued = new LongOpenHashSet();
    /**
     * Лампы, погашенные при загрузке чанка ({@link ChunkSaves}), чей свет пришёл с диска: убрать его, как только
     * соседи чанка загружены (свет от лампы заходит и к ним).
     */
    private final Long2ObjectOpenHashMap<LongArrayList> staleLight = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Read> readsDone = new ConcurrentLinkedQueue<>();
    private int readsInFlight;
    private boolean readFailureLogged;
    /** Квартал → когда в нём последний раз играл звук (щелчок и гул на весь квартал — один раз). */
    private final Long2LongOpenHashMap sounded = new Long2LongOpenHashMap();
    @Nullable
    private Path regionFolder;
    private boolean restored;

    /** Для {@link ModAttachments#BLACKOUT_WORLD}: своё у каждого мира, живёт, пока мир загружен, не сохраняется. */
    public BlackoutWorld() {}

    public static BlackoutWorld get(ServerLevel level) {
        return level.getData(ModAttachments.BLACKOUT_WORLD);
    }

    // ---------------------------------------------------------------- события

    /** Новое отключение: его каскад — с этого тика. */
    void onOutage(Outage o) {
        sweeps.add(new Sweep(o, false, Long.MIN_VALUE));
    }

    /** Отключению назначили возврат света: каскад возврата вместо прежнего (если он уже шёл). */
    void onRestore(Outage o) {
        sweeps.removeIf(s -> s.restore && s.outage.id() == o.id());
        sweeps.add(new Sweep(o, true, Long.MIN_VALUE));
    }

    /** Привести чанк к сети при первой возможности (загружен, поставили лампу). */
    void enqueue(long chunk) {
        if (queued.add(chunk)) ready.enqueue(chunk);
    }

    private void read(long chunk) {
        if (readsQueued.add(chunk)) reads.enqueue(chunk);
    }

    /** Чанк загружен с погашенными в палитре лампами {@code at}: их свет с диска убрать (см. {@link #staleLight}). */
    void staleLight(ChunkPos chunk, LongArrayList at) {
        if (at.isEmpty()) staleLight.remove(chunk.toLong());
        else staleLight.put(chunk.toLong(), at);
    }

    /** Сколько чанков ждёт перевода и копий с диска (для /airstrike grid status). */
    public int[] backlog() {
        return new int[]{ready.size() + retry.size(), reads.size() + readsInFlight};
    }

    // ---------------------------------------------------------------- тик

    /** @param clock бюджет тика сервера для блэкаута, общий для всех измерений (уже запущен) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        PowerGrid grid = PowerGrid.get(level);
        if (!restored) restore(level, grid, now);
        drainReads(level, grid, now);
        scheduleRestoreSweeps(grid, now);
        if (now % 20 == 0) {
            grid.prune(now);
            Substations.sync(level, grid, now);
            sounded.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200);
        }
        advanceSweeps(level, grid, now, clock);
        while (!retry.isEmpty() && retry.peek()[1] <= now) ready.enqueue(retry.poll()[0]); // уже в queued
        while (!ready.isEmpty() && clock.canStart()) {
            long c0 = clock.begin();
            long c = ready.dequeueLong();
            try {
                handle(level, grid, c, now);
            } catch (RuntimeException e) {
                queued.remove(c);
                Airstrike.LOG.error("Блэкаут: перевод чанка {} упал с ошибкой; чанк пропущен", new ChunkPos(c), e);
            } finally {
                clock.end(c0);
            }
        }
        startReads(level, grid, now);
    }

    /** После загрузки мира: каскады отключений, которые ещё идут, — дальше с того места, где остановились. */
    private void restore(ServerLevel level, PowerGrid grid, long now) {
        restored = true;
        for (Outage o : grid.outages()) {
            long dark = grid.swept(o.id(), false);
            if (dark < o.lastDark()) sweeps.add(new Sweep(o, false, dark));
            if (o.restoreAt() != Outage.NEVER && now >= o.restoreAt()) sweeps.add(new Sweep(o, true, grid.swept(o.id(), true)));
        }
        regionFolder = DiskChunks.regionFolder(level);
    }

    /** Отключения, чей свет пора возвращать: каскад возврата (один на отключение). */
    private void scheduleRestoreSweeps(PowerGrid grid, long now) {
        for (Outage o : grid.outages()) {
            if (o.restoreAt() == Outage.NEVER || now < o.restoreAt()) continue;
            if (sweeps.stream().noneMatch(s -> s.restore && s.outage.id() == o.id()) && grid.swept(o.id(), true) < o.restoreAt()) {
                sweeps.add(new Sweep(o, true, Long.MIN_VALUE));
            }
        }
    }

    /** Разобрать каскады (ряд чанков — единица работы) и выдать чанки, до которых каскад дошёл. */
    private void advanceSweeps(ServerLevel level, PowerGrid grid, long now, WorkClock clock) {
        Iterator<Sweep> it = sweeps.iterator();
        while (it.hasNext()) {
            Sweep s = it.next();
            // отключение забыто (свет вернулся везде) или ему назначен другой возврат — каскад больше не нужен
            Optional<Outage> current = grid.outages().stream().filter(o -> o.id() == s.outage.id()).findFirst();
            if (current.isEmpty() || s.restore && !current.get().equals(s.outage)) {
                it.remove();
                continue;
            }
            while (!s.built() && clock.canStart()) {
                long c0 = clock.begin();
                s.buildRow();
                clock.end(c0);
            }
            if (!s.built()) continue;
            boolean readsNeeded = DistantHorizons.present() && AirstrikeConfig.SERVER.gridDistantLod.get();
            while (s.cursor <= now && s.cursor <= s.last) {
                LongArrayList chunks = s.byDue.remove(s.cursor);
                if (chunks != null) {
                    for (int i = 0; i < chunks.size(); i++) {
                        long c = chunks.getLong(i);
                        if (inMemory(level, c) != null) enqueue(c);
                        else if (readsNeeded && s.cursor > s.doneUntil) read(c);
                    }
                }
                s.cursor++;
            }
            // пройденное — в сохранение: после перезапуска копии для LOD не повторяются; пройден целиком — навсегда
            grid.swept(s.outage.id(), s.restore, s.done() ? Long.MAX_VALUE : Math.min(now, s.last));
            if (s.done()) it.remove();
        }
    }

    /**
     * Один чанк — одна единица работы: загружен — перевести до {@link #LAMPS_PER_UNIT} ламп (если соседи загружены,
     * иначе позже; остались ещё — чанк первым в очереди); не загружен — обновить его LOD копией с диска
     * (переведётся сам при загрузке).
     */
    private void handle(ServerLevel level, PowerGrid grid, long c, long now) {
        LevelChunk chunk = inMemory(level, c);
        ChunkPos pos = new ChunkPos(c);
        if (chunk == null) {
            queued.remove(c);
            staleLight.remove(c);
            if (DistantHorizons.present() && AirstrikeConfig.SERVER.gridDistantLod.get()) read(c);
            return;
        }
        boolean dark = grid.dark(pos.x, pos.z, now);
        // переводить нечего (в большинстве чанков ламп нет) — только отметки, соседей не ждём
        boolean needed = ChunkLights.needs(chunk, dark);
        LongArrayList stale = staleLight.get(c);
        if ((needed || stale != null) && (level.getChunkSource().getChunkNow(pos.x, pos.z) == null || !NuclearTickets.neighbourhoodLoaded(level, pos))) {
            retry.add(new long[]{c, now + NEIGHBOUR_RETRY});
            return;
        }
        if (stale != null) {
            staleLight.remove(c);
            var light = level.getChunkSource().getLightEngine();
            for (int i = 0; i < stale.size(); i++) light.checkBlock(BlockPos.of(stale.getLong(i)));
        }
        // отметка — до перевода: упади он посередине, погашенные уже отмечены
        if (dark && needed) chunk.setData(ModAttachments.GRID_DARK, true);
        int changed = needed ? ChunkLights.apply(level, chunk, dark, LAMPS_PER_UNIT) : 0;
        if (changed > 0 && ChunkLights.needs(chunk, dark)) {
            // башня морских фонарей — не один тик: остальное — следующими единицами
            ready.enqueueFirst(c);
            districtSound(level, pos, dark, now);
            return;
        }
        queued.remove(c);
        if (!dark && chunk.hasData(ModAttachments.GRID_DARK)) {
            // без отметки — не трогать: снятие помечает чанк несохранённым
            chunk.removeData(ModAttachments.GRID_DARK);
        }
        if (changed > 0) {
            DistantHorizons.updateLod(level, chunk);
            districtSound(level, pos, dark, now);
        }
    }

    /** Щелчок реле и обрыв гула (или гул, набирающий силу) — тем, кто рядом с кварталом, один раз на квартал. */
    private void districtSound(ServerLevel level, ChunkPos pos, boolean dark, long now) {
        long district = Districts.of(pos.x, pos.z);
        if (sounded.containsKey(district)) return;
        sounded.put(district, now);
        int x = pos.getMiddleBlockX(), z = pos.getMiddleBlockZ();
        Vec3 at = new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
        S2C.GridDistrict packet = new S2C.GridDistrict(at, !dark);
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - at.x, dz = p.getZ() - at.z;
            if (dx * dx + dz * dz <= DISTRICT_SOUND_RANGE * DISTRICT_SOUND_RANGE) PacketDistributor.sendToPlayer(p, packet);
        }
    }

    // ---------------------------------------------------------------- копии с диска для LOD

    private void startReads(ServerLevel level, PowerGrid grid, long now) {
        if (regionFolder == null) return;
        Path folder = regionFolder;
        while (readsInFlight < MAX_READS && !reads.isEmpty()) {
            long c = reads.dequeueLong();
            readsQueued.remove(c);
            ChunkPos pos = new ChunkPos(c);
            if (inMemory(level, c) != null) {
                enqueue(c);
                continue;
            }
            boolean dark = grid.dark(pos.x, pos.z, now);
            readsInFlight++;
            CompletableFuture.supplyAsync(() -> DiskChunks.regionExists(folder, pos), Util.backgroundExecutor())
                    .thenCompose(exists -> exists ? level.getChunkSource().chunkMap.read(pos) : CompletableFuture.completedFuture(Optional.empty()))
                    .thenApplyAsync(tag -> tag.map(t -> DiskChunks.copy(level, pos, t, dark)).orElse(null), Util.backgroundExecutor())
                    .whenComplete((copy, error) -> readsDone.add(new Read(c, copy, dark, error)));
        }
    }

    /** Готовые копии — в DH, если чанк так и не загрузился и сеть в нём всё та же. */
    private void drainReads(ServerLevel level, PowerGrid grid, long now) {
        Read r;
        while ((r = readsDone.poll()) != null) {
            readsInFlight--;
            if (r.error != null) {
                if (!readFailureLogged) {
                    readFailureLogged = true;
                    Airstrike.LOG.warn("Блэкаут: копия чанка {} с диска для LOD не вышла", new ChunkPos(r.chunk), r.error);
                }
                continue;
            }
            ChunkPos pos = new ChunkPos(r.chunk);
            if (r.copy != null && inMemory(level, r.chunk) == null && grid.dark(pos.x, pos.z, now) == r.dark) {
                DistantHorizons.updateLod(level, r.copy);
            }
        }
    }

    /**
     * Чанк в памяти: полностью загруженный или опущенный ниже (у края видимости), но не выгруженный; null — его нет
     * в памяти (как в {@code ScarQueue}).
     */
    @Nullable
    private static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /** Для проверок: пройдены ли все каскады. */
    public boolean idle() {
        return sweeps.isEmpty() && ready.isEmpty() && retry.isEmpty() && reads.isEmpty() && readsInFlight == 0;
    }

    /** Карта для проверок и статуса: у отключения каскад ещё идёт. */
    public Map<Integer, Boolean> sweeping() {
        Map<Integer, Boolean> m = new HashMap<>();
        for (Sweep s : sweeps) m.merge(s.outage.id(), s.restore, (a, b) -> a || b);
        return m;
    }
}
