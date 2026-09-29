package ua.zentix.airstrike.grid;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.DistantHorizons;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.world.NuclearTickets;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.util.Terrain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *   <li>Чанк, у которого загружены и соседи, переводится через мир (свет, клиенты, Sable). Чанк на краю загруженного
 *   мира (соседей нет, а на выделенном сервере они могут не прийти вовсе) — прямо в палитре
 *   ({@link ChunkLights#applyInPlace}): Sable на каждое изменение блока читает соседей и грузил бы их синхронно
 *   ({@link NuclearTickets#neighbourhoodLoaded}). Что требует соседей (сверка ламп от сигнала), ждёт их загрузки.</li>
 *   <li>Чанков блэкаут не грузит никогда: на диске погашенных ламп нет ({@link ChunkSaves}), и чанк, загруженный
 *   в тёмном квартале, гаснет сам при загрузке.</li>
 * </ul>
 */
public final class BlackoutWorld {
    /** Ламп за единицу работы: у каждой — setBlock со светом, клиентами и Sable (≈10–30 мкс). */
    static final int LAMPS_PER_UNIT = 32;
    /** Раз во сколько тиков обходить плоты аппаратов Sable ({@link #relightPlots}). */
    public static final int PLOT_SCAN = 100;
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
    /** Чанки в {@link #ready}: каждый — один раз, сколько бы поводов ни пришло. */
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final LongArrayFIFOQueue reads = new LongArrayFIFOQueue();
    /** Чанки в {@link #reads}: копия читается один раз и по сети на момент чтения. */
    private final LongOpenHashSet readsQueued = new LongOpenHashSet();
    /**
     * Лампы, погашенные при загрузке чанка ({@link ChunkSaves}), чей свет пришёл с диска: убрать его первой же
     * работой по чанку (в загруженных соседей снижение света заходит само, в незагруженных света нет).
     */
    private final Long2ObjectOpenHashMap<LongArrayList> staleLight = new Long2ObjectOpenHashMap<>();
    /**
     * Лампы от сигнала, зажжённые на краю загруженного мира ({@link ChunkLights#applyInPlace}): сверить с сигналом,
     * как только соседи чанка загружены ({@link #neighbourLoaded}).
     */
    private final Long2ObjectOpenHashMap<LongArrayList> resignal = new Long2ObjectOpenHashMap<>();
    /** Двойники не от блэкаута, которые пора зажечь ({@link #relightLater}): каждое место — один раз. */
    private final LongLinkedOpenHashSet relight = new LongLinkedOpenHashSet();
    private final ConcurrentLinkedQueue<Read> readsDone = new ConcurrentLinkedQueue<>();
    private int readsInFlight;
    /**
     * Для строки в лог, с загрузки мира: копий чанков отдано DH; без копии (файла или чанка на диске нет, в чанке
     * нет ламп, он другой версии игры или не полный); отброшено (чанк загрузился или сеть в нём сменилась, пока копия
     * читалась); не прочитано (ошибка).
     */
    private int lodCopies, lodSkipped, lodDiscarded, lodFailed;
    /** Когда очередь копий для LOD начала работу (наносекунды; 0 — очередь пуста): для строки в лог о её конце. */
    private long readsSince;
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

    /** Двойник не от блэкаута (поршень, аппарат) — зажечь в конце тика, если ток есть. */
    public void relightLater(BlockPos pos) {
        relight.add(pos.asLong());
    }

    /** Есть ли ток у блока: плот аппарата Sable — всегда (своё питание), остальное — по кварталу. */
    public static boolean powered(ServerLevel level, BlockPos pos) {
        return SubLevels.isInPlot(level, Vec3.atCenterOf(pos)) || !PowerGrid.get(level).dark(pos.getX() >> 4, pos.getZ() >> 4, level.getGameTime());
    }

    /** Двойники не от блэкаута — по {@link #LAMPS_PER_UNIT} за единицу работы. */
    private void relightPlaced(ServerLevel level, WorkClock clock) {
        LongArrayList later = new LongArrayList();
        while (!relight.isEmpty() && clock.canStart()) {
            long c0 = clock.begin();
            try {
                for (int n = 0; n < LAMPS_PER_UNIT && !relight.isEmpty(); n++) {
                    BlockPos pos = BlockPos.of(relight.removeFirstLong());
                    boolean plot = SubLevels.isInPlot(level, Vec3.atCenterOf(pos));
                    // в мире — только при готовых соседях (Sable читает соседей); в плоте чанки держит сам аппарат
                    if (!plot && (!Terrain.ready(level, pos) || !NuclearTickets.aroundLoaded(level, pos))) {
                        if (inMemory(level, ChunkPos.asLong(pos)) != null) later.add(pos.asLong());
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (GridLights.isUnlit(state) && powered(level, pos)) ChunkLights.relight(level, pos, state);
                }
            } finally {
                clock.end(c0);
            }
        }
        // ждущие соседей — в следующий тик, после остальных
        relight.addAll(later);
    }

    /**
     * Лампы в плотах аппаратов Sable: сборка аппарата ставит блоки прямо в секции плота (без {@code onPlace}), тики
     * блоков в плоте не идут, а сохраняет плот Sable своим кодеком, мимо {@link ChunkSaves}. У аппарата своё питание:
     * раз в {@link #PLOT_SCAN} тиков чанки плотов с двойниками (по палитрам) зажигаются — страховка: двойник, у которого
     * при сборке есть собранный сосед, зажигается сразу ({@code Unlit.neighborChanged}). Обход — только палитры и
     * секции с двойниками; сами лампы — под бюджетом ({@link #relightPlaced}).
     */
    public void relightPlots(ServerLevel level, List<SubLevelAccess> ships) {
        for (SubLevelAccess sub : ships) {
            var box = sub.boundingBox();
            double dx = box.maxX() - box.minX(), dy = box.maxY() - box.minY(), dz = box.maxZ() - box.minZ();
            // габариты — в мире: в плоте аппарат, уменьшенный в мире, крупнее во столько раз
            double r = (Math.sqrt(dx * dx + dy * dy + dz * dz) / 2 + 2) / SubLevels.minScale(sub);
            Vec3 c = SubLevels.toPlot(sub, SubLevels.center(sub));
            for (int cx = Mth.floor(c.x - r) >> 4; cx <= Mth.floor(c.x + r) >> 4; cx++) {
                for (int cz = Mth.floor(c.z - r) >> 4; cz <= Mth.floor(c.z + r) >> 4; cz++) {
                    // чанк чужого плота или не плота — не трогать (и не грузить)
                    if (SubLevels.containing(level, new ChunkPos(cx, cz)) != sub) continue;
                    if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk) || !ChunkLights.anyUnlit(chunk.getSections())) continue;
                    int x0 = cx << 4, z0 = cz << 4;
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        LevelChunkSection section = sections[i];
                        if (section.hasOnlyAir() || !section.maybeHas(GridLights::isUnlit)) continue;
                        int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    BlockState state = section.getBlockState(x, y, z);
                                    if (GridLights.isUnlit(state)) relight.add(BlockPos.asLong(x0 + x, y0 + y, z0 + z));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Чанк выгружен: свет с диска уберёт следующая загрузка. Несверенные лампы от сигнала ушли на диск такими, как
     * были, — как у любого чанка с края загруженного мира, их сверит следующее обновление соседа.
     */
    void forget(long chunk) {
        staleLight.remove(chunk);
        resignal.remove(chunk);
    }

    /** Загружен чанк: соседние чанки, чьи лампы ждали соседей для сверки с сигналом, — в очередь. */
    void neighbourLoaded(ChunkPos pos) {
        if (resignal.isEmpty()) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if ((dx != 0 || dz != 0) && resignal.containsKey(c)) enqueue(c);
            }
        }
    }

    private void read(long chunk) {
        if (readsQueued.add(chunk)) reads.enqueue(chunk);
    }

    /** Чанк загружен с погашенными в палитре лампами {@code at}: их свет с диска убрать (см. {@link #staleLight}). */
    void staleLight(ChunkPos chunk, LongArrayList at) {
        if (at.isEmpty()) staleLight.remove(chunk.toLong());
        else staleLight.put(chunk.toLong(), at);
    }

    /** Сколько чанков ждёт перевода, двойников — зажигания и копий с диска (для /airstrike grid status). */
    public int[] backlog() {
        return new int[]{ready.size(), relight.size(), reads.size() + readsInFlight};
    }

    /**
     * Есть ли работа без отключений: очереди, свет с диска, сверка ламп. Мир без отключений и без работы не тикает
     * ({@link Blackouts#onServerTick}).
     */
    public boolean busy() {
        return !idle() || !staleLight.isEmpty() || !resignal.isEmpty();
    }

    // ---------------------------------------------------------------- тик

    /** @param clock бюджет тика сервера для блэкаута, общий для всех измерений (уже запущен) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        PowerGrid grid = PowerGrid.get(level);
        if (!restored) restore(level, grid, now);
        drainReads(level, grid, now);
        relightPlaced(level, clock);
        scheduleRestoreSweeps(grid, now);
        if (now % 20 == 0) {
            grid.prune(now);
            Substations.sync(level, grid, now);
            sounded.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200);
            // чанк ушёл из памяти, не став полным (кольцо вокруг краевых чанков): события выгрузки у него нет
            var chunkMap = level.getChunkSource().chunkMap;
            staleLight.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            resignal.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
        }
        advanceSweeps(level, grid, now, clock);
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
            // отключение, начатое до первого тика (команда, взрыв), уже со своим каскадом
            boolean hasDark = sweeps.stream().anyMatch(sw -> !sw.restore && sw.outage.id() == o.id());
            boolean hasLight = sweeps.stream().anyMatch(sw -> sw.restore && sw.outage.id() == o.id());
            if (!hasDark && dark < o.lastDark()) sweeps.add(new Sweep(o, false, dark));
            if (!hasLight && o.restoreAt() != Outage.NEVER && now >= o.restoreAt()) sweeps.add(new Sweep(o, true, grid.swept(o.id(), true)));
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
            if (s.done()) {
                it.remove();
                Airstrike.LOG.info("Блэкаут №{}: каскад {} прошёл весь район (копий для LOD DH ещё в очереди: {})",
                        s.outage.id(), s.restore ? "возврата света" : "отключения", reads.size() + readsInFlight);
            }
        }
    }

    /**
     * Один чанк — одна единица работы: загружен — сперва убрать свет с диска и сверить лампы от сигнала (по
     * {@link #LAMPS_PER_UNIT} мест за единицу), потом перевести до {@link #LAMPS_PER_UNIT} ламп; осталось ещё — чанк
     * первым в очереди. Не загружен — обновить его LOD копией с диска (переведётся сам при загрузке).
     */
    private void handle(ServerLevel level, PowerGrid grid, long c, long now) {
        LevelChunk chunk = inMemory(level, c);
        ChunkPos pos = new ChunkPos(c);
        if (chunk == null) {
            queued.remove(c);
            staleLight.remove(c);
            resignal.remove(c);
            if (DistantHorizons.present() && AirstrikeConfig.SERVER.gridDistantLod.get()) read(c);
            return;
        }
        // край загруженного мира: чанк в памяти, соседи — нет (и не будут, пока игрок не подойдёт); ждать их нельзя —
        // блоки меняются в палитре, без соседей (ChunkLights.applyInPlace)
        boolean edge = level.getChunkSource().getChunkNow(pos.x, pos.z) == null || !NuclearTickets.neighbourhoodLoaded(level, pos);
        LongArrayList stale = staleLight.get(c);
        if (stale != null) {
            // снижение света по окрестности; в незагруженного соседа оно не заходит — край мира, игрокам не выдан
            var light = level.getChunkSource().getLightEngine();
            for (int n = 0; n < LAMPS_PER_UNIT && !stale.isEmpty(); n++) light.checkBlock(BlockPos.of(stale.removeLong(stale.size() - 1)));
            if (stale.isEmpty()) staleLight.remove(c);
            ready.enqueueFirst(c);
            return;
        }
        LongArrayList signalled = resignal.get(c);
        if (signalled != null && !edge) {
            // сигнал читается у соседей — теперь они загружены
            for (int n = 0; n < LAMPS_PER_UNIT && !signalled.isEmpty(); n++) ChunkLights.resignal(level, BlockPos.of(signalled.removeLong(signalled.size() - 1)));
            if (signalled.isEmpty()) resignal.remove(c);
            ready.enqueueFirst(c);
            return;
        }
        boolean dark = grid.dark(pos.x, pos.z, now);
        // переводить нечего (в большинстве чанков ламп нет) — только отметки
        boolean needed = ChunkLights.needs(chunk, dark);
        // отметка — до перевода: упади он посередине, погашенные уже отмечены
        if (dark && needed) chunk.setData(ModAttachments.GRID_DARK, true);
        int changed = 0;
        if (needed && edge) {
            LongArrayList lamps = new LongArrayList();
            changed = ChunkLights.applyInPlace(level, chunk, dark, LAMPS_PER_UNIT, lamps);
            if (!lamps.isEmpty()) resignal.computeIfAbsent(c, k -> new LongArrayList()).addAll(lamps);
        } else if (needed) {
            changed = ChunkLights.apply(level, chunk, dark, LAMPS_PER_UNIT);
        }
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
        if (readsSince == 0 && !reads.isEmpty()) readsSince = System.nanoTime();
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
                lodFailed++;
                if (!readFailureLogged) {
                    readFailureLogged = true;
                    Airstrike.LOG.warn("Блэкаут: копия чанка {} с диска для LOD не вышла", new ChunkPos(r.chunk), r.error);
                }
                continue;
            }
            ChunkPos pos = new ChunkPos(r.chunk);
            if (r.copy == null) {
                lodSkipped++;
            } else if (inMemory(level, r.chunk) == null && grid.dark(pos.x, pos.z, now) == r.dark) {
                DistantHorizons.updateLod(level, r.copy);
                lodCopies++;
            } else {
                lodDiscarded++;
            }
        }
        if (readsSince != 0 && reads.isEmpty() && readsInFlight == 0) {
            Airstrike.LOG.info("Блэкаут: копии чанков для LOD DH отданы за {} с (с загрузки мира: в DH {}, без копии {}, отброшено {}, не прочитано {})",
                    String.format(Locale.ROOT, "%.1f", (System.nanoTime() - readsSince) / 1e9), lodCopies, lodSkipped, lodDiscarded, lodFailed);
            readsSince = 0;
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
        return sweeps.isEmpty() && relight.isEmpty() && ready.isEmpty() && reads.isEmpty() && readsInFlight == 0;
    }

    /** Карта для проверок и статуса: у отключения каскад ещё идёт. */
    public Map<Integer, Boolean> sweeping() {
        Map<Integer, Boolean> m = new HashMap<>();
        for (Sweep s : sweeps) m.merge(s.outage.id(), s.restore, (a, b) -> a || b);
        return m;
    }
}
