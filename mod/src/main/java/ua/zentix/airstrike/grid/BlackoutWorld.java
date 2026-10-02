package ua.zentix.airstrike.grid;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.world.FarLods;
import ua.zentix.airstrike.nuclear.world.NuclearTickets;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.util.Palettes;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Блэкаут в мире измерения: приводит чанки к состоянию сети ({@link PowerGrid}) под бюджетом времени тика.
 * Не сохраняется: всё выводится из отключений, отметок на чанках и самих блоков.
 * <ul>
 *   <li>Каскад ({@link Sweep}): чанки района отключения по времени, когда до их квартала доходит отключение (или
 *   возврат света). Загруженный чанк переводится сразу (лампы — в двойников или обратно); чанк вне загруженного
 *   мира не грузится — он переведётся, когда его загрузят ({@link #onChunkLoad}).</li>
 *   <li>Лампы меняются прямо в палитре секции ({@link ChunkLights#applyInPlace}): для мира лампа и двойник — один
 *   и тот же блок, кроме света ({@link GridLights#inPlace}: ни блок-сущности, ни другой формы), и соседям, картам
 *   высот, столкновениям знать не о чем; свет — {@code checkBlock} по месту, клиентам — изменение блока. Через
 *   {@code setBlock} с Create и Sable лампа стоила ≈10 мкс, а в городе бывает 2.5 млн невидимых блоков света (до 7400 на
 *   чанк): очередь шла минутами. Лампы от сигнала сверяются с сигналом сразу, если соседи чанка загружены; на краю
 *   загруженного мира (соседей нет, а на выделенном сервере они могут не прийти вовсе) — когда загрузятся. Через мир
 *   ({@link ChunkLights#apply}) — только плоты аппаратов Sable: там блоки аппарата читает его физика.</li>
 *   <li>Единица работы — не чанк, а {@link #UNIT_WORK} работы по стольким чанкам очереди, сколько уместится (не больше
 *   {@link #CHUNKS_PER_UNIT}): лампа в палитре — 1, через мир — {@link #WORLD_LAMP}, проход секций чанка с лампами —
 *   {@link #SCAN_COST}; чанк без ламп — проверка палитр, микросекунды. Проход продолжается с места остановки
 *   ({@link ChunkLights.Pass#next}) и кончается на последней лампе чанка: ни повторных проходов с начала, ни
 *   холостого второго.</li>
 *   <li>Чанков блэкаут не грузит никогда: на диске погашенных ламп нет ({@link ChunkSaves}), и чанк, загруженный
 *   в тёмном квартале, гаснет сам при загрузке.</li>
 * </ul>
 */
public final class BlackoutWorld {
    /** Работы в единице (≈0.3 мс): её мера — лампа, переведённая в палитре. */
    public static final int UNIT_WORK = 256;
    /** Лампа через мир ({@code setBlock} со светом, клиентами и Sable; с Create и Sable ≈10 мкс) — как столько ламп в палитре. */
    static final int WORLD_LAMP = 8;
    /** Ламп через мир за единицу (зажигание двойников не от блэкаута, сверка сигнала). */
    static final int LAMPS_PER_UNIT = UNIT_WORK / WORLD_LAMP;
    /** Чанков за единицу работы: у чанка без ламп — проверка палитр секций, микросекунды. */
    static final int CHUNKS_PER_UNIT = 256;
    /**
     * Единица дольше этого (настенное время) — в лог с разбивкой ({@link #logSlowUnit}): в 2.5 раза больше бюджета тика
     * по умолчанию (4 мс) — такая единица одна съедает тик блэкаута; самая долгая в /airstrike grid status не говорит, из чего она.
     */
    private static final long SLOW_UNIT_NANOS = 10_000_000L;
    /** Игровое время последней записи о долгой единице. */
    private long slowLogged = Long.MIN_VALUE / 2;
    /** Чанки ближе этого к игроку (в чанках, по большей из осей) идут в очередь раньше остальных. */
    static final int NEAR_CHUNKS = 8;
    /**
     * Проход секций чанка по блокам — как столько ламп в палитре: палитра помнит и переведённые состояния, и чанк,
     * где ламп уже нет, может потребовать прохода (≈0.2 мс на чанк города).
     */
    private static final int SCAN_COST = 128;
    /** Раз во сколько тиков обходить плоты аппаратов Sable ({@link #relightPlots}). */
    public static final int PLOT_SCAN = 100;
    /** Звук квартала — игрокам ближе этого (блоки по горизонтали). */
    private static final double DISTRICT_SOUND_RANGE = 96;

    /** Каскад одного отключения (или возврата света): чанки района по времени прихода. */
    private static final class Sweep {
        final Outage outage;
        final boolean restore;
        final int minX, maxX, maxZ;
        /** Следующий ряд чанков (z), который ещё не разобран. */
        int row;
        final Long2ObjectOpenHashMap<LongArrayList> byDue = new Long2ObjectOpenHashMap<>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        /** Следующий момент, чанки которого ещё не выданы. */
        long cursor = Long.MIN_VALUE;

        Sweep(Outage outage, boolean restore) {
            this.outage = outage;
            this.restore = restore;
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

    private final List<Sweep> sweeps = new ArrayList<>();
    /**
     * Чанки, которые пора перевести: из каскада, загрузки, поставленной лампы. Сначала — чанки у игроков
     * ({@link #NEAR_CHUNKS}), потом остальные: в городе с миллионами ламп очередь идёт минутами, и свет рядом с
     * игроком не ждёт дальних кварталов.
     */
    private final LongArrayFIFOQueue near = new LongArrayFIFOQueue(), ready = new LongArrayFIFOQueue();
    /** Очередь, из которой взят чанк в работе: недоделанный возвращается в её начало. */
    private LongArrayFIFOQueue taken = ready;
    /** Чанки игроков этого измерения на начало тика. */
    private final LongArrayList players = new LongArrayList();
    /** Чанки в очереди: каждый — один раз, сколько бы поводов ни пришло. */
    private final LongOpenHashSet queued = new LongOpenHashSet();
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
    /**
     * Двойники из {@link #relight}, чей чанк ещё не готов (или соседи не загружены), — по чанкам: снова в {@link #relight}
     * с загрузкой соседнего чанка и раз в секунду, а не каждый тик впереди очереди.
     */
    private final Long2ObjectOpenHashMap<LongArrayList> relightWaiting = new Long2ObjectOpenHashMap<>();
    /** Чанки плотов Sable с лампами, которые ждут полной загрузки (переводятся через мир): в очередь раз в секунду. */
    private final LongOpenHashSet plotsWaiting = new LongOpenHashSet();
    /**
     * Чанк, пройденный не до конца: откуда продолжать ({@link ChunkLights.Pass#next} × 2 + «гасить»). Место только
     * движется вперёд, поэтому чанк кончается за конечное число проходов, что бы ни делали с его блоками другие моды.
     * Другое направление (свет вернули посреди гашения), новый повод (лампу поставили раньше места) или выгрузка — с начала.
     * Место есть у чанков в очереди и у плотов, ждущих полной загрузки ({@link #plotsWaiting}).
     */
    private final Long2IntOpenHashMap resume = new Long2IntOpenHashMap();
    /** Квартал → когда в нём последний раз играл звук (щелчок и гул на весь квартал — один раз). */
    private final Long2LongOpenHashMap sounded = new Long2LongOpenHashMap();
    private boolean restored;

    /**
     * Куда уходит бюджет (для /airstrike grid status): по каждому виду работы — сколько раз и сколько времени, за окно
     * в {@link #STATS_WINDOW} тиков. Очередь, которая тратит единицы и не движется, видна здесь по видам.
     */
    public enum Work {
        /** Единица очереди чанков (из них — всё ниже, кроме зажигания и каскада). */
        UNIT,
        /** Чанк не в памяти — выброшен. */
        GONE,
        /** Переводить нечего (палитры) — закрыт без прохода. */
        IDLE,
        /** Свет с диска: по местам ламп, чанк снова первым. */
        STALE,
        /** Сверка ламп от сигнала, чанк снова первым. */
        RESIGNAL,
        /** Проход через мир, пройден до конца. */
        PASS_DONE,
        /** Проход через мир, лампы сверх лимита — чанк снова первым. */
        PASS_MORE,
        /** Проход в палитре (край загруженного мира). */
        EDGE,
        /** Переведено ламп (времени нет — оно в проходах). */
        LAMPS,
        /** Лампа после setBlock — не то, что ставили (блок вернул или заменил другой мод). */
        REVERTED,
        /** Единица зажигания двойников не от блэкаута. */
        RELIGHT,
        /** Двойник ждёт соседей своего чанка (снова в конец очереди зажигания). */
        RELIGHT_WAIT,
        /** Строка каскада (кварталы и сроки чанков района). */
        BUILD
    }

    /** Окно счётчиков работы, тиков. */
    public static final int STATS_WINDOW = 100;
    private final long[] count = new long[Work.values().length], nanos = new long[Work.values().length];
    private long[] lastCount = new long[Work.values().length], lastNanos = new long[Work.values().length];
    private long statsWindow;
    /** Чанк, который очередь брала больше всего раз подряд (за окно и за прошлое окно): застрявшая голова очереди. */
    private long headChunk, repeatChunk, lastRepeatChunk;
    private int headRepeat, repeatMax, lastRepeatMax;

    /** Счётчики с создания мира (для проверок: окно в 100 тиков их не делит). */
    private final long[] total = new long[Work.values().length];

    private void note(Work w, long n, long t) {
        count[w.ordinal()] += n;
        nanos[w.ordinal()] += t;
        total[w.ordinal()] += n;
    }

    /** Сколько работы каждого вида с загрузки мира (по {@link Work}). */
    public long[] totals() {
        return total.clone();
    }

    /** Счётчики прошлого окна: {сколько, нс} по {@link Work}. */
    public long[][] work() {
        return new long[][]{lastCount.clone(), lastNanos.clone()};
    }

    /** Прошлое окно: чанк, взятый больше всего раз подряд, и сколько раз ({@code null}, если никакой — дважды). */
    @Nullable
    public ChunkPos repeatedChunk() {
        return lastRepeatMax > 1 ? new ChunkPos(lastRepeatChunk) : null;
    }

    public int repeatedTimes() {
        return lastRepeatMax;
    }

    /** Для {@link ModAttachments#BLACKOUT_WORLD}: своё у каждого мира, живёт, пока мир загружен, не сохраняется. */
    public BlackoutWorld() {
        resume.defaultReturnValue(-1);
    }

    public static BlackoutWorld get(ServerLevel level) {
        return level.getData(ModAttachments.BLACKOUT_WORLD);
    }

    // ---------------------------------------------------------------- события

    /** Новое отключение: его каскад — с этого тика. */
    void onOutage(Outage o) {
        sweeps.add(new Sweep(o, false));
    }

    /** Отключению назначили возврат света: каскад возврата вместо прежнего (если он уже шёл). */
    void onRestore(Outage o) {
        sweeps.removeIf(s -> s.restore && s.outage.id() == o.id());
        sweeps.add(new Sweep(o, true));
    }

    /**
     * Привести чанк к сети при первой возможности (загружен, задет каскадом). Чанк уже в очереди продолжает проход
     * с места остановки: повторный повод (сверка сигнала раз в секунду, событие) не начинает его заново.
     */
    void enqueue(long chunk) {
        if (!queued.add(chunk)) return;
        resume.remove(chunk);
        (nearPlayer(chunk) ? near : ready).enqueue(chunk);
    }

    /** Поставлена лампа: чанк — в очередь, и проход, уже ушедший дальше места лампы, возвращается к ней. */
    void lampPlaced(ServerLevel level, BlockPos pos) {
        long c = ChunkPos.asLong(pos);
        int r = resume.get(c);
        int at = level.getSectionIndex(pos.getY()) << 12 | (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
        if (r >= 0 && at < r >> 1) resume.put(c, at << 1 | r & 1);
        enqueue(c);
    }

    /** Чанк не дальше {@link #NEAR_CHUNKS} от игрока (по чанкам игроков на начало тика). */
    private boolean nearPlayer(long chunk) {
        return playerDistance(chunk) <= NEAR_CHUNKS;
    }

    /** Расстояние до ближайшего игрока в чанках (по большей из осей); без игроков — {@code Integer.MAX_VALUE}. */
    private int playerDistance(long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk), best = Integer.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) {
            long p = players.getLong(i);
            best = Math.min(best, Math.max(Math.abs(ChunkPos.getX(p) - x), Math.abs(ChunkPos.getZ(p) - z)));
        }
        return best;
    }

    /**
     * Чанки общей очереди, к которым подошёл игрок, — в очередь у игроков (в её конец, место прохода сохраняется):
     * иначе чанк, попавший в очередь до прихода игрока, ждёт весь дальний город.
     */
    void promoteNear() {
        if (players.isEmpty() || ready.isEmpty()) return;
        for (int i = ready.size(); i > 0; i--) {
            long c = ready.dequeueLong();
            (nearPlayer(c) ? near : ready).enqueue(c);
        }
    }

    private boolean queueEmpty() {
        return near.isEmpty() && ready.isEmpty();
    }

    private long take() {
        taken = near.isEmpty() ? ready : near;
        return taken.dequeueLong();
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
        while (!relight.isEmpty() && clock.canStart()) {
            long c0 = clock.begin(), t0 = System.nanoTime();
            long[] counted = count.clone(), timed = nanos.clone();
            int lamps = 0;
            try {
                for (int n = 0; n < LAMPS_PER_UNIT && !relight.isEmpty(); n++) {
                    BlockPos pos = BlockPos.of(relight.removeFirstLong());
                    boolean plot = SubLevels.isInPlot(level, Vec3.atCenterOf(pos));
                    // чанк готов (иначе getBlockState грузит его сразу); в мире — ещё и соседи (Sable читает соседей),
                    // в плоте соседние чанки держит сам аппарат
                    if (!Terrain.ready(level, pos) || !plot && !NuclearTickets.aroundLoaded(level, pos)) {
                        long c = ChunkPos.asLong(pos);
                        if (inMemory(level, c) != null) {
                            relightWaiting.computeIfAbsent(c, k -> new LongArrayList()).add(pos.asLong());
                            note(Work.RELIGHT_WAIT, 1, 0);
                        }
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (GridLights.isUnlit(state) && powered(level, pos)) {
                        ChunkLights.relight(level, pos, state);
                        lamps++;
                    }
                }
            } finally {
                note(Work.RELIGHT, 1, clock.end(c0));
                long took = System.nanoTime() - t0;
                if (slow(level, took)) logSlowUnit(level, "зажигание", took, "ламп " + lamps, counted, timed);
            }
        }
    }

    /**
     * Двойников чанка можно зажигать (как в {@link #relightPlaced}): чанк полностью загружен и, вне плота Sable, загружены
     * соседи. Загрузка соседа проверяет это сама ({@link #neighbourLoaded}).
     */
    private static boolean relightReady(ServerLevel level, long c) {
        ChunkPos p = new ChunkPos(c);
        return level.getChunkSource().getChunkNow(p.x, p.z) != null
                && (SubLevels.containing(level, p) != null || NuclearTickets.neighbourhoodLoaded(level, p));
    }

    /** Ждущие двойники чанка — снова к зажиганию. */
    private void retryRelight(long chunk) {
        LongArrayList waiting = relightWaiting.remove(chunk);
        if (waiting != null) relight.addAll(waiting);
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
                        if (section.hasOnlyAir() || !Palettes.contains(section.getStates(), GridLights::isUnlit)) continue;
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
        resume.remove(chunk);
        relightWaiting.remove(chunk);
        plotsWaiting.remove(chunk);
    }

    /** Загружен чанк: соседние чанки, чьи лампы ждали соседей для сверки с сигналом, — в очередь. */
    void neighbourLoaded(ChunkPos pos) {
        if (resignal.isEmpty() && relightWaiting.isEmpty()) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long c = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if ((dx != 0 || dz != 0) && resignal.containsKey(c)) enqueue(c);
                retryRelight(c);
            }
        }
    }

    /** Чанк загружен с погашенными в палитре лампами {@code at}: их свет с диска убрать (см. {@link #staleLight}). */
    void staleLight(ChunkPos chunk, LongArrayList at) {
        if (at.isEmpty()) staleLight.remove(chunk.toLong());
        else staleLight.put(chunk.toLong(), at);
    }

    /** Сколько чанков ждёт перевода и двойников — зажигания (для /airstrike grid status). */
    public int[] backlog() {
        return new int[]{near.size() + ready.size(), relight.size()};
    }

    /**
     * Есть ли работа без отключений: очереди, свет с диска, сверка ламп. Мир без отключений и без работы не тикает
     * ({@link Blackouts#work}).
     */
    public boolean busy() {
        return !idle() || !staleLight.isEmpty() || !resignal.isEmpty();
    }

    /**
     * Очередь занята, а у чанка {@code chunk} ждёт свет с диска или сверка сигнала (проверки). Ждущие сверки других
     * чанков не в счёт: краевой чанк чужой проверки с лампой от сигнала ждёт соседей, пока не выгрузится.
     */
    public boolean busy(ChunkPos chunk) {
        return !idle() || staleLight.containsKey(chunk.toLong()) || resignal.containsKey(chunk.toLong());
    }

    /** Чем занята очередь (проверки). */
    public String busyState() {
        return "очередь " + (idle() ? "пуста" : "не пуста") + ", свет с диска ждут " + staleLight.size() + " чанков, сверку сигнала — " + resignal.size();
    }

    // ---------------------------------------------------------------- тик

    /** @param clock бюджет тика сервера для блэкаута, общий для всех измерений (уже запущен) */
    public void tick(ServerLevel level, WorkClock clock) {
        long now = level.getGameTime();
        if (now / STATS_WINDOW != statsWindow) {
            // мир не тикал целое окно — прошлое окно пустое
            boolean adjacent = now / STATS_WINDOW == statsWindow + 1;
            lastCount = adjacent ? count.clone() : new long[count.length];
            lastNanos = adjacent ? nanos.clone() : new long[nanos.length];
            Arrays.fill(count, 0);
            Arrays.fill(nanos, 0);
            lastRepeatChunk = repeatChunk;
            lastRepeatMax = adjacent ? repeatMax : 0;
            repeatMax = 0;
            statsWindow = now / STATS_WINDOW;
        }
        players.clear();
        for (var p : level.players()) players.add(p.chunkPosition().toLong());
        PowerGrid grid = PowerGrid.get(level);
        if (!restored) restore(level, grid, now);
        relightPlaced(level, clock);
        scheduleRestoreSweeps(grid, now);
        if (now % 20 == 0) {
            grid.prune(now);
            sounded.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200);
            // чанк ушёл из памяти, не став полным (кольцо вокруг краевых чанков): события выгрузки у него нет
            var chunkMap = level.getChunkSource().chunkMap;
            staleLight.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            resignal.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            relightWaiting.keySet().removeIf(c -> chunkMap.getVisibleChunkIfPresent(c) == null);
            plotsWaiting.removeIf(c -> {
                if (chunkMap.getVisibleChunkIfPresent(c) != null) return false;
                resume.remove(c);
                return true;
            });
            // сосед вернулся к полной загрузке без события (опускался ниже у края видимости) — сверка сигнала сейчас
            for (long c : resignal.keySet()) {
                ChunkPos p = new ChunkPos(c);
                if (level.getChunkSource().getChunkNow(p.x, p.z) != null && NuclearTickets.neighbourhoodLoaded(level, p)) enqueue(c);
            }
            // плоты Sable соседей по событиям не получают: ждущие двойники и чанки плотов — раз в секунду, когда готовы
            // (иначе все ждущие двойники раз в секунду шли впереди очереди и снова ложились ждать)
            for (long c : relightWaiting.keySet().toLongArray()) {
                if (relightReady(level, c)) retryRelight(c);
            }
            for (long c : plotsWaiting.toLongArray()) {
                ChunkPos p = new ChunkPos(c);
                if (level.getChunkSource().getChunkNow(p.x, p.z) != null) {
                    plotsWaiting.remove(c);
                    // место прохода сохранено (resume): продолжить с него
                    if (queued.add(c)) (nearPlayer(c) ? near : ready).enqueue(c);
                }
            }
            promoteNear();
        }
        advanceSweeps(level, grid, now, clock);
        while (!queueEmpty() && clock.canStart()) {
            long c0 = clock.begin(), t0 = System.nanoTime();
            long[] counted = count.clone(), timed = nanos.clone();
            long c = 0, first = 0;
            int chunks = 0;
            try {
                int work = 0;
                for (; chunks < CHUNKS_PER_UNIT && work < UNIT_WORK && !queueEmpty(); chunks++) {
                    c = take();
                    if (chunks == 0) first = c;
                    work += handle(level, grid, c, now, UNIT_WORK - work);
                }
            } catch (RuntimeException e) {
                if (!taken.isEmpty() && taken.firstLong() == c) taken.dequeueLong();
                queued.remove(c);
                resume.remove(c);
                Airstrike.LOG.error("Блэкаут: перевод чанка {} упал с ошибкой; чанк пропущен", new ChunkPos(c), e);
            } finally {
                note(Work.UNIT, 1, clock.end(c0));
                long took = System.nanoTime() - t0;
                if (slow(level, took)) logSlowUnit(level, "очередь", took, "чанков " + chunks + (chunks > 0 ? ", первый " + new ChunkPos(first) : ""), counted, timed);
            }
        }
    }

    /** Единица дольше {@link #SLOW_UNIT_NANOS} — писать ли её в лог ({@link #logSlowUnit}): не чаще раза в 10 с. */
    private boolean slow(ServerLevel level, long took) {
        long now = level.getGameTime();
        if (took <= SLOW_UNIT_NANOS || now < slowLogged + 200) return false;
        slowLogged = now;
        return true;
    }

    /**
     * Долгая единица — в лог с разбивкой по видам работы: что в ней было и сколько времени каждого вида. У единицы
     * очереди остаток («вне видов») — время, которое не легло ни в один вид: пауза GC, вытеснение потока (у встроенного
     * сервера рядом клиент), звук квартала. Остаток больше половины единицы — не работа блэкаута: строка INFO, не WARN
     * (одна пауза GC уже дольше порога). Зажигание и строка каскада на виды не делятся — у них только время и объём.
     */
    private void logSlowUnit(ServerLevel level, String unit, long took, String what, long[] counted, long[] timed) {
        StringBuilder kinds = new StringBuilder();
        long inKinds = 0;
        for (Work w : Work.values()) {
            long n = count[w.ordinal()] - counted[w.ordinal()], t = nanos[w.ordinal()] - timed[w.ordinal()];
            // единица целиком (очередь, зажигание, каскад) — это и есть took
            if (w == Work.UNIT || w == Work.RELIGHT || w == Work.BUILD || n == 0 && t == 0) continue;
            inKinds += t;
            kinds.append(' ').append(w.name().toLowerCase(Locale.ROOT)).append('=').append(n);
            if (t > 0) kinds.append('/').append(ms(t)).append("мс");
        }
        // по видам раскладывается только время единицы очереди (у зажигания и каскада видов нет)
        boolean split = unit.equals("очередь");
        long outside = Math.max(0, took - inKinds);
        if (split) kinds.append("; вне видов ").append(ms(outside)).append("мс");
        String line = "Блэкаут ({}): единица работы ({}) {} мс, {}" + (kinds.isEmpty() ? "{}" : ":{}");
        Object[] args = {level.dimension().location(), unit, ms(took), what, kinds};
        if (split && outside * 2 > took) Airstrike.LOG.info(line, args);
        else Airstrike.LOG.warn(line, args);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    /**
     * После загрузки мира: каскады отключений, которые ещё идут, — заново; кварталы, до которых каскад уже дошёл,
     * выдаются в первом же тике (загруженные чанки переводятся, остальные — при загрузке).
     */
    private void restore(ServerLevel level, PowerGrid grid, long now) {
        restored = true;
        for (Outage o : grid.outages()) {
            long dark = grid.swept(o.id(), false);
            // отключение, начатое до первого тика (команда, взрыв), уже со своим каскадом
            boolean hasDark = sweeps.stream().anyMatch(sw -> !sw.restore && sw.outage.id() == o.id());
            boolean hasLight = sweeps.stream().anyMatch(sw -> sw.restore && sw.outage.id() == o.id());
            if (!hasDark && dark < o.lastDark()) sweeps.add(new Sweep(o, false));
            if (!hasLight && o.restoreAt() != Outage.NEVER && now >= o.restoreAt()) sweeps.add(new Sweep(o, true));
        }
    }

    /** Отключения, чей свет пора возвращать: каскад возврата (один на отключение). */
    private void scheduleRestoreSweeps(PowerGrid grid, long now) {
        for (Outage o : grid.outages()) {
            if (o.restoreAt() == Outage.NEVER || now < o.restoreAt()) continue;
            if (sweeps.stream().noneMatch(s -> s.restore && s.outage.id() == o.id()) && grid.swept(o.id(), true) < o.restoreAt()) {
                sweeps.add(new Sweep(o, true));
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
                long c0 = clock.begin(), t0 = System.nanoTime();
                long[] counted = count.clone(), timed = nanos.clone();
                s.buildRow();
                note(Work.BUILD, 1, clock.end(c0));
                long took = System.nanoTime() - t0;
                if (slow(level, took)) logSlowUnit(level, "каскад", took, "ряд из " + (s.maxX - s.minX + 1) + " чанков", counted, timed);
            }
            if (!s.built()) continue;
            while (s.cursor <= now && s.cursor <= s.last) {
                LongArrayList chunks = s.byDue.remove(s.cursor);
                if (chunks != null) {
                    // квартал — от игрока наружу: у игрока лампы гаснут и загораются первыми
                    if (!players.isEmpty()) {
                        long[] order = chunks.toLongArray();
                        LongArrays.quickSort(order, (a, b) -> Integer.compare(playerDistance(a), playerDistance(b)));
                        chunks = LongArrayList.wrap(order);
                    }
                    for (int i = 0; i < chunks.size(); i++) {
                        long c = chunks.getLong(i);
                        if (inMemory(level, c) != null) enqueue(c);
                        // не в памяти: переведётся при загрузке, а LOD Distant Horizons вдали — копией с диска
                        else FarLods.request(level, c, false);
                    }
                }
                s.cursor++;
            }
            // пройденное — в сохранение: пройден целиком — после перезапуска не повторяется
            grid.swept(s.outage.id(), s.restore, s.done() ? Long.MAX_VALUE : Math.min(now, s.last));
            if (s.done()) {
                it.remove();
                Airstrike.LOG.info("Блэкаут №{} ({}): каскад {} прошёл весь район", s.outage.id(), level.dimension().location(),
                        s.restore ? "возврата света" : "отключения");
            }
        }
    }

    /**
     * Чанк из очереди, не больше {@code limit} ламп или мест: загружен — сперва убрать свет с диска и сверить лампы от
     * сигнала, потом перевести лампы; осталось ещё — чанк первым в очереди. Не загружен — пропустить (переведётся сам
     * при загрузке). Возвращает, сколько сделано (ламп и мест).
     */
    private int handle(ServerLevel level, PowerGrid grid, long c, long now, int limit) {
        headRepeat = c == headChunk ? headRepeat + 1 : 1;
        headChunk = c;
        if (headRepeat > repeatMax) {
            repeatMax = headRepeat;
            repeatChunk = c;
        }
        LevelChunk chunk = inMemory(level, c);
        ChunkPos pos = new ChunkPos(c);
        if (chunk == null) {
            queued.remove(c);
            staleLight.remove(c);
            resignal.remove(c);
            resume.remove(c);
            note(Work.GONE, 1, 0);
            return 0;
        }
        long t0 = System.nanoTime();
        // край загруженного мира: чанк в памяти, соседи — нет (и не будут, пока игрок не подойдёт); ждать их нельзя —
        // блоки меняются в палитре, без соседей (ChunkLights.applyInPlace)
        boolean edge = level.getChunkSource().getChunkNow(pos.x, pos.z) == null || !NuclearTickets.neighbourhoodLoaded(level, pos);
        LongArrayList stale = staleLight.get(c);
        if (stale != null) {
            // снижение света по окрестности; в незагруженного соседа оно не заходит — край мира, игрокам не выдан
            var light = level.getChunkSource().getLightEngine();
            int n = 0;
            for (; n < limit && !stale.isEmpty(); n++) light.checkBlock(BlockPos.of(stale.removeLong(stale.size() - 1)));
            if (stale.isEmpty()) staleLight.remove(c);
            taken.enqueueFirst(c);
            note(Work.STALE, 1, System.nanoTime() - t0);
            return n;
        }
        LongArrayList signalled = resignal.get(c);
        if (signalled != null && !edge) {
            // сигнал читается у соседей — теперь они загружены
            int n = 0;
            int most = Math.max(1, limit / WORLD_LAMP);
            for (; n < most && !signalled.isEmpty(); n++) ChunkLights.resignal(level, BlockPos.of(signalled.removeLong(signalled.size() - 1)));
            if (signalled.isEmpty()) resignal.remove(c);
            taken.enqueueFirst(c);
            note(Work.RESIGNAL, 1, System.nanoTime() - t0);
            return n * WORLD_LAMP;
        }
        boolean dark = grid.dark(pos.x, pos.z, now);
        // переводить нечего (в большинстве чанков ламп нет) — только отметки
        boolean needed = ChunkLights.needs(chunk, dark);
        // отметка — до перевода: упади он посередине, погашенные уже отмечены
        if (dark && needed) chunk.setData(ModAttachments.GRID_DARK, true);
        // плот аппарата Sable — через мир: блоки аппарата читает его физика (соседние чанки плота держит сам аппарат,
        // вне плота соседей нет — край мира тут не мерка); плот, опущенный ниже полной загрузки, ждёт её с местом прохода
        boolean world = needed && SubLevels.containing(level, pos) != null;
        if (world && level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
            queued.remove(c);
            plotsWaiting.add(c);
            note(Work.EDGE, 1, System.nanoTime() - t0);
            return SCAN_COST;
        }
        int r = resume.remove(c);
        int from = r >= 0 && (r & 1) == (dark ? 1 : 0) ? r >> 1 : 0;
        ChunkLights.Pass pass = ChunkLights.Pass.NONE;
        int cost = world ? WORLD_LAMP : 1, resignalled = 0;
        if (world) {
            pass = ChunkLights.apply(level, chunk, dark, Math.max(1, limit / cost), from);
        } else if (needed) {
            LongArrayList lamps = new LongArrayList();
            pass = ChunkLights.applyInPlace(level, chunk, dark, limit, from, !edge, lamps);
            if (edge) {
                // сигнал читается у соседей — сверка, когда они загрузятся
                if (!lamps.isEmpty()) resignal.computeIfAbsent(c, k -> new LongArrayList()).addAll(lamps);
            } else {
                for (int i = 0; i < lamps.size(); i++) ChunkLights.resignal(level, BlockPos.of(lamps.getLong(i)));
                resignalled = lamps.size();
            }
        }
        int changed = pass.changed();
        note(Work.LAMPS, changed, 0);
        note(Work.REVERTED, pass.reverted(), 0);
        note(!needed ? Work.IDLE : edge ? Work.EDGE : pass.done() ? Work.PASS_DONE : Work.PASS_MORE, 1, System.nanoTime() - t0);
        if (changed > 0) {
            districtSound(level, chunk, dark, now);
            // LOD Distant Horizons: квартал гаснет (и зажигается) и вдали, а не при сохранении чанка
            DhUpdates.mark(level, pos, level.getGameTime() + DhUpdates.SETTLE);
        }
        // проход секций по блокам (4096 блоков — как много ламп) — тоже работа, даже если ламп в них уже нет
        int work = changed * cost + resignalled * WORLD_LAMP + (needed ? SCAN_COST : 0);
        if (!pass.done()) {
            // башня морских фонарей — не одна единица: остальное — следующими, с места остановки
            resume.put(c, pass.next() << 1 | (dark ? 1 : 0));
            taken.enqueueFirst(c);
            return work;
        }
        queued.remove(c);
        if (!dark && chunk.hasData(ModAttachments.GRID_DARK)) {
            // без отметки — не трогать: снятие помечает чанк несохранённым
            chunk.removeData(ModAttachments.GRID_DARK);
        }
        return work;
    }

    /** Щелчок реле и обрыв гула (или гул, набирающий силу) — тем, кто рядом с кварталом, один раз на квартал. */
    private void districtSound(ServerLevel level, LevelChunk chunk, boolean dark, long now) {
        ChunkPos pos = chunk.getPos();
        long district = Districts.of(pos.x, pos.z);
        if (sounded.containsKey(district)) return;
        sounded.put(district, now);
        int x = pos.getMiddleBlockX(), z = pos.getMiddleBlockZ();
        // высота — у чанка в руках: чанк на краю бывает ниже полной загрузки, и level.getHeight грузил бы его сразу
        Vec3 at = new Vec3(x + 0.5, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1, z + 0.5);
        S2C.GridDistrict packet = new S2C.GridDistrict(at, !dark);
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - at.x, dz = p.getZ() - at.z;
            if (dx * dx + dz * dz <= DISTRICT_SOUND_RANGE * DISTRICT_SOUND_RANGE) PacketDistributor.sendToPlayer(p, packet);
        }
    }

    /**
     * Чанк в памяти: полностью загруженный или опущенный ниже (у края видимости), но не выгруженный; null — его нет
     * в памяти (как в {@code ScarQueue}).
     */
    @Nullable
    static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /** Для проверок: пройдены ли все каскады. */
    public boolean idle() {
        return sweeps.isEmpty() && relight.isEmpty() && queueEmpty();
    }
}
