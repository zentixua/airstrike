package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.grid.ChunkLights;
import ua.zentix.airstrike.grid.PowerGrid;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Чанки не в мире, которые мод изменил бы при загрузке, — в LOD Distant Horizons сразу ({@link DhUpdates#offer}): руины
 * ядерки за волной ({@link NuclearPrep}: план уже готов, а сам чанк грузится, только когда к нему подойдут) и лампы
 * погасшего или вновь зажжённого квартала ({@code BlackoutWorld}). Чанк читается с диска (поток ввода-вывода чанков,
 * разбор — {@link RuinWorkers}), в копию его секций ({@link DiskShots#readSections}) кладутся руины по готовому плану
 * ({@link RuinPlan#applyToCopy}: план устарел — копия не годится) и лампы — по сети сейчас ({@link ChunkLights#applyToCopy}),
 * копия уходит в DH как {@link ProtoChunk}. Мир не трогается: чанк, который загрузят потом, получит те же руины и лампы
 * своим путём. Копия без изменений против диска в DH не уходит — его LOD по этим же блокам уже есть.
 * <p>
 * Только при DH ({@link DhUpdates#room}) и только чанки не дальше {@link #FAR_CHUNKS} от игрока: дальше DH по умолчанию
 * не рассылает обновления, а чтения с диска делят поток ввода-вывода с загрузкой мира игрокам — их не больше
 * {@link #READS} сразу и {@link #PER_TICK} новых за тик.
 */
public final class FarLods {
    /** Докуда от игрока (чанки, по большей оси): дальность обновлений DH игрокам по умолчанию ({@code realTimeUpdates.playerDistance}). */
    static final int FAR_CHUNKS = 256;
    /** Чтений с диска в работе сразу и новых за тик. */
    static final int READS = 16, PER_TICK = 8;
    /** Сколько тиков чанк за волной ждёт готового плана (строится в фоне); дальше — без LOD вдали. */
    static final int PLAN_WAIT = 1200;

    /**
     * Чанк в очереди: с какого тика и почему — за волной ядерки (ждать его плана руин) или квартал сменил свет (LOD
     * уходит и без изменений против диска: у DH мог остаться погашенный квартал).
     */
    private record Request(long chunk, long since, boolean ruins) {}

    /** Готовая копия (или почему её нет) из фонового потока. */
    private record Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, boolean lights, @Nullable String skip) {}

    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    /** Чанки в чтении и те, кого за это время попросили снова (после чтения — в очередь ещё раз). */
    private final LongOpenHashSet reading = new LongOpenHashSet(), again = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    @Nullable
    private DiskShots.Format format;
    /** Для строки в лог и проверок: ушло в DH (с руинами), без изменений, план устарел, не прочитаны, не дождались плана. */
    private long sent, sentRuins, unchanged, stale, unread, noPlan;
    private long nextReport;

    /** Для {@link ModAttachments#FAR_LODS}: своё у каждого мира, не сохраняется. */
    public FarLods() {}

    public static FarLods get(ServerLevel level) {
        return level.getData(ModAttachments.FAR_LODS);
    }

    /**
     * Чанк не в мире, чьи лампы или руины сейчас другие, чем в LOD DH: в LOD, если он рядом с игроком.
     *
     * @param ruins чанк за волной ядерки — ждать его плана руин ({@link #PLAN_WAIT}); иначе квартал сменил свет
     */
    public static void request(ServerLevel level, long chunk, boolean ruins) {
        if (!DhUpdates.enabled(level) || !nearPlayer(level, chunk)) return;
        FarLods lods = get(level);
        if (lods.reading.contains(chunk)) {
            lods.again.add(chunk);
            return;
        }
        if (lods.queued.add(chunk)) lods.queue.add(new Request(chunk, level.getGameTime(), ruins));
    }

    private static boolean nearPlayer(ServerLevel level, long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        for (ServerPlayer p : level.players()) {
            ChunkPos at = p.chunkPosition();
            if (Math.max(Math.abs(at.x - x), Math.abs(at.z - z)) <= FAR_CHUNKS) return true;
        }
        return false;
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.FAR_LODS)) get(level).tick(level);
    }

    private void tick(ServerLevel level) {
        if (queue.isEmpty() && reading.isEmpty()) return;
        long now = level.getGameTime();
        for (Built b; (b = built.poll()) != null; ) harvest(level, b);
        if (!DhUpdates.enabled(level)) {
            // DH ушёл (мир DH закрыт): просить нечего
            clear();
            return;
        }
        int room = DhUpdates.room(level) - reading.size();
        NuclearWorld nuclear = NuclearWorld.get(level);
        NuclearEvents events = NuclearEvents.get(level);
        int started = 0;
        for (int n = queue.size(); n > 0 && started < PER_TICK && reading.size() < READS && room > 0; n--) {
            Request r = queue.poll();
            ChunkPos pos = new ChunkPos(r.chunk);
            if (inWorld(level, r.chunk)) {
                // загружен: руины и лампы ему ставит мир, а LOD — их отметка (DhUpdates.mark)
                queued.remove(r.chunk);
                continue;
            }
            RuinPlan plan = nuclear.prep().arrivedPlan(level, events, r.chunk, now);
            if (plan == null && r.ruins) {
                if (now - r.since < PLAN_WAIT) {
                    queue.add(r);
                } else {
                    queued.remove(r.chunk);
                    noPlan++;
                }
                continue;
            }
            queued.remove(r.chunk);
            if (format == null) format = DiskShots.Format.of(level);
            reading.add(r.chunk);
            started++;
            room--;
            DiskShots.Format f = format;
            boolean fires = AirstrikeConfig.SERVER.nukeFires.get();
            DiskShots.readSections(f, pos).thenApply(s -> build(f, s, plan, fires, !r.ruins)).exceptionally(e -> new Built(r.chunk, null, false, false, "ошибка: " + e))
                    .thenAccept(built::add);
        }
        if (now >= nextReport && sent + unchanged + stale + unread + noPlan > 0) {
            nextReport = now + 600;
            Airstrike.LOG.info("LOD Distant Horizons вдали ({}): ушло {} чанков (с руинами {}), без изменений {}, план устарел {}, не прочитаны {}, "
                            + "не дождались плана {}; в очереди {}, читаются {}", level.dimension().location(), sent, sentRuins, unchanged, stale, unread,
                    noPlan, queue.size(), reading.size());
        }
    }

    /** Копия чанка с руинами (фоновый поток): секции с диска, места плана. */
    private static Built build(DiskShots.Format f, DiskShots.Sections s, @Nullable RuinPlan plan, boolean fires, boolean lights) {
        long chunk = s.pos().toLong();
        if (s.skip() != null) return new Built(chunk, null, false, lights, s.skip());
        if (plan != null && !plan.applyToCopy(s.sections(), fires)) return new Built(chunk, null, false, lights, "план устарел");
        ProtoChunk copy = new ProtoChunk(s.pos(), UpgradeData.EMPTY, s.sections(), new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                LevelHeightAccessor.create(f.minY(), f.height()), f.biomes(), null);
        copy.setPersistedStatus(ChunkStatus.FULL);
        return new Built(chunk, copy, plan != null && plan.changedBlocks() + plan.fireCount() > 0, lights, null);
    }

    /** Поток сервера: лампы — к сети сейчас; изменилось против диска (или квартал сменил свет) — в DH. */
    private void harvest(ServerLevel level, Built b) {
        reading.remove(b.chunk);
        if (again.remove(b.chunk)) request(level, b.chunk, false);
        if (b.copy == null) {
            if ("план устарел".equals(b.skip)) stale++;
            else unread++;
            return;
        }
        // загрузили, пока читали: у мира свой путь
        if (inWorld(level, b.chunk)) return;
        // на диске двойников нет: погасить, если квартал тёмный
        boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(b.chunk), ChunkPos.getZ(b.chunk), level.getGameTime());
        int lamps = dark ? ChunkLights.applyToCopy(b.copy.getSections(), true) : 0;
        if (!b.ruined && lamps == 0 && !b.lights) {
            unchanged++;
            return;
        }
        DhUpdates.offer(level, b.copy);
        sent++;
        if (b.ruined) sentRuins++;
    }

    /**
     * Проверки: копия загруженного чанка — его данные сохранения (как на диске), разобранные как чанк не в мире, с
     * руинами плана ({@code fires} — с пожарами) и лампами по сети; null — план к копии не подошёл.
     */
    @Nullable
    public static ProtoChunk testCopy(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk, @Nullable RuinPlan plan, boolean fires) {
        DiskShots.Format f = DiskShots.Format.of(level);
        net.minecraft.nbt.CompoundTag tag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
        Built b = build(f, DiskShots.parseSections(f, chunk.getPos(), tag), plan, fires, false);
        if (b.copy == null) return null;
        if (PowerGrid.get(level).dark(chunk.getPos().x, chunk.getPos().z, level.getGameTime())) ChunkLights.applyToCopy(b.copy.getSections(), true);
        return b.copy;
    }

    private static boolean inWorld(ServerLevel level, long chunk) {
        return level.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunk) != null;
    }

    /** Отбой или остановка: очередь не нужна (чтения в работе доходят и выбрасываются). */
    public void clear() {
        queue.clear();
        queued.clear();
        again.clear();
    }

    /** Для проверок: ушло, с руинами, без изменений, план устарел, не прочитаны, не дождались плана, в очереди, читаются. */
    public long[] stats() {
        return new long[] {sent, sentRuins, unchanged, stale, unread, noPlan, queue.size(), reading.size()};
    }

    /** Для проверок: дождаться чтений в работе. */
    public boolean busy() {
        return !queue.isEmpty() || !reading.isEmpty();
    }
}
