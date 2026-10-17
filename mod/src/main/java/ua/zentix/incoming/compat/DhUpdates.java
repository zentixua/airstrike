package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Что мод изменил в мире — в LOD Distant Horizons ({@link DhChunks}), в темпе, который его очередь выдерживает.
 * <ul>
 * <li>Загруженные чанки, где мод менял блоки (воронки и осыпание у взрывов, руины ядерки, лампы блэкаута), —
 * {@link #mark}: чанк уходит в DH не раньше срока (порции одного взрыва идут несколько тиков — один вызов на всё)
 * и не чаще раза в {@link #RESEND} тиков: позицию, которая ещё в очереди DH, он молча пропускает, а читает чанк
 * по живой ссылке позже, так что ранний вызов уже несёт и следующие изменения. Чанк, выгруженный до срока,
 * пропускается: DH видит его сохранение при выгрузке сам.</li>
 * <li>Чанки не в мире, собранные модом с изменениями (руины ядерки вдали — с диска и по готовому плану), —
 * {@link #offer}: их ссылку DH держит, пока не разберёт; сколько ещё ждут отправки — {@link #room}.</li>
 * <li>Копии тяжёлой зоны ядерки, собранные заранее и отданные к приходу фронта, — {@link #sendHeld}: сразу, мимо
 * {@link #PER_TICK}; их темп держит окно ({@link #heldRoom}): отданных и не подтверждённых записью в базу DH — не
 * больше половины наименьшей очереди DH ({@link DhQueue}).</li>
 * </ul>
 * За тик — не больше {@link #PER_TICK} вызовов (и {@link #SCAN} просмотренных отметок): очередь DH на мир — от 1000
 * позиций на поток его разбора, переполненная выкидывает дальние (на выделенном сервере — дальние от 0 0) с одной
 * строкой «overloaded» в лог DH раз в 30 с. Чанк, отданный DH любым путём, снова уходит не раньше {@link #RESEND}
 * тиков: позицию, которая ещё в его очереди, DH молча пропускает.
 * Без DH ничего не копится. Несохраняемый attachment мира ({@link ModAttachments#DH_UPDATES}): перезапуск теряет
 * очередь, а чанки, ушедшие в DH, он помнит в своей базе.
 */
public final class DhUpdates {
    /** Вызовов DH за тик сервера (400 чанков в секунду). */
    static final int PER_TICK = 20;
    /** Раньше этого после прошлой отправки чанк снова не отправляется: DH ещё держит его в очереди (250 мс и пакет ~1 с). */
    static final int RESEND = 40;
    /** Сколько тиков после изменения ждать следующих порций того же взрыва. */
    public static final int SETTLE = 10;
    /** Собранных чанков не в мире ждут отправки, не больше: за ними — память (секции целиком). */
    static final int OFFER_LIMIT = 512;
    /**
     * Отметок за тик просматривается, не больше: не наступившие встают в конец (по кругу) — очередь (руины ядерки —
     * тысячи чанков) не обходится вся каждый тик, а наступившая за ними ждёт не дольше нескольких тиков.
     */
    static final int SCAN = 256;
    /**
     * Своя задержка DH от вызова до записи LOD в базу, тиков, — наименьшая ({@link DhQueue#LEAD}): держанная копия
     * ({@link #sendHeld}) уходит за столько до прихода фронта — LOD меняется не раньше него.
     */
    public static final int LEAD = DhQueue.LEAD;
    /**
     * Сколько DH держит отданную копию чанка не в мире, нс, не меньше: до записи в базу (без подтверждения — до срока
     * {@link DhQueue#TIMEOUT_NANOS}) и ещё {@link DhQueue#CACHE_NANOS} в своём кэше: память копии, отданной к фронту, —
     * столько после отдачи.
     */
    public static final long HOLD_NANOS = DhQueue.TIMEOUT_NANOS + DhQueue.CACHE_NANOS;
    /** Миры, ждущие подтверждений держанных копий: поток DH ({@link #saved}) находит по миру его очередь. */
    private static final Map<Object, DhUpdates> LISTENING = new ConcurrentHashMap<>();

    /** Чанк → тик, не раньше которого его отправить; по порядку отметок. */
    private final Long2LongLinkedOpenHashMap due = new Long2LongLinkedOpenHashMap();
    /** Чанк → более поздняя отметка, чем ближайшая в {@link #due}: после отправки чанк ждёт её снова. */
    private final Long2LongOpenHashMap later = new Long2LongOpenHashMap();
    /** Чанк → тик последней отправки (старше {@link #RESEND} — забываются). */
    private final Long2LongOpenHashMap sent = new Long2LongOpenHashMap();
    private final ArrayDeque<ChunkAccess> offered = new ArrayDeque<>();
    /** Чанки в {@link #offered} (сколько копий каждого ждёт отправки). */
    private final Long2IntOpenHashMap offeredCount = new Long2IntOpenHashMap();
    /** Держанные копии, отданные и не подтверждённые ({@link #sendHeld}). */
    private final DhQueue held = new DhQueue();
    /** Подтверждения из потока DH ({@link #saved}): чанк и когда. */
    private final ConcurrentLinkedQueue<long[]> acks = new ConcurrentLinkedQueue<>();
    private long sentTotal, offeredTotal;
    /** Для проверок: больше всего отметок, просмотренных за один тик. */
    private int scannedMax;
    /** Куда уходят чанки: в игре — {@link DhChunks}; проверки подставляют свой приёмник ({@link #testSink}). */
    private Sink sink = DhChunks::overwrite;
    private boolean test;

    /** Приёмник чанков. */
    public interface Sink {
        void overwrite(ServerLevel level, ChunkAccess chunk);
    }

    /** Для {@link ModAttachments#DH_UPDATES}: своё у каждого мира, не сохраняется. */
    public DhUpdates() {}

    /** Очередь мира, если есть куда отдавать (DH стоит или проверка подставила приёмник), иначе null. */
    private static DhUpdates active(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return u.test || DhChunks.available() ? u : null;
    }

    /** Проверки: чанки мира {@code level} уходят в {@code sink} (и без DH); null — снова в DH, очередь пуста. */
    public static void testSink(ServerLevel level, @Nullable Sink sink) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        u.sink = sink != null ? sink : DhChunks::overwrite;
        u.test = sink != null;
        if (sink == null) {
            u.due.clear();
            u.later.clear();
            u.offered.clear();
            u.offeredCount.clear();
            u.held.clear();
            LISTENING.remove(level, u);
        }
    }

    /** Мод изменил блоки загруженного чанка: в DH не раньше тика {@code at}. Поток сервера. */
    public static void mark(ServerLevel level, ChunkPos pos, long at) {
        DhUpdates u = active(level);
        if (u == null) return;
        Long2LongLinkedOpenHashMap due = u.due;
        long key = pos.toLong();
        // повторная отметка не откладывает раннюю, а встаёт за ней: после отправки чанк ждёт и её
        if (!due.containsKey(key)) {
            due.put(key, at);
            return;
        }
        long was = due.get(key);
        if (at < was) due.put(key, at);
        long last = Math.max(was, at);
        if (last > due.get(key) && last > u.later.get(key)) u.later.put(key, last);
    }

    /** {@link #mark} всех чанков квадрата вокруг {@code centre} радиусом {@code reach} блоков. */
    public static void markArea(ServerLevel level, Vec3 centre, double reach, long at) {
        if (active(level) == null) return;
        int x0 = Mth.floor(centre.x - reach) >> 4, x1 = Mth.floor(centre.x + reach) >> 4;
        int z0 = Mth.floor(centre.z - reach) >> 4, z1 = Mth.floor(centre.z + reach) >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) mark(level, new ChunkPos(x, z), at);
    }

    /** Отметка загруженного чанка ещё ждёт отправки в DH ({@link #mark}). Поток сервера. */
    public static boolean pending(ServerLevel level, long chunk) {
        DhUpdates u = active(level);
        return u != null && u.due.containsKey(chunk);
    }

    /** Есть куда отдавать: DH стоит (или проверка подставила приёмник). */
    public static boolean enabled(ServerLevel level) {
        return active(level) != null;
    }

    /** Есть ли DH, и сколько ещё собранных чанков не в мире можно отдать ({@link #offer}) прямо сейчас. */
    public static int room(ServerLevel level) {
        DhUpdates u = active(level);
        return u == null ? 0 : OFFER_LIMIT - u.offered.size();
    }

    /**
     * Чанк не из мира (его копия с изменениями, {@code ProtoChunk}) — в LOD как есть: после этого его не менять.
     * Поток сервера.
     */
    public static void offer(ServerLevel level, ChunkAccess chunk) {
        DhUpdates u = active(level);
        if (u == null) return;
        u.offered.add(chunk);
        u.offeredCount.addTo(chunk.getPos().toLong(), 1);
        u.offeredTotal++;
    }

    /**
     * Сколько ещё держанных копий можно отдать сейчас ({@link #sendHeld}): окно — половина наименьшей очереди DH на мир
     * по числу его потоков ({@link DhQueue#window}), минус отданные и не подтверждённые; без DH — 0.
     */
    public static int heldRoom(ServerLevel level) {
        DhUpdates u = active(level);
        return u == null ? 0 : DhQueue.window(DhChunks.threads()) - u.held.waiting();
    }

    /**
     * Держанная копия чанка не в мире (собрана заранее, {@code ProtoChunk}) — в DH сейчас, мимо {@link #PER_TICK}: её
     * срок — приход фронта, а темп держит окно ({@link #heldRoom}); после этого копию не менять. Поток сервера.
     *
     * @return false — не сейчас: тот же чанк отдан DH меньше {@link #RESEND} тиков назад или ждёт отправки
     * ({@link #offer}) — DH пропустил бы копию или накрыл бы её той
     */
    public static boolean sendHeld(ServerLevel level, ChunkAccess copy) {
        DhUpdates u = active(level);
        if (u == null) return true;
        long key = copy.getPos().toLong(), now = level.getGameTime();
        if (u.offeredCount.containsKey(key) || u.sent.containsKey(key) && now - u.sent.get(key) < RESEND) return false;
        if (u.held.waiting() == 0) {
            LISTENING.put(level, u);
            DhChunks.listen();
        }
        u.sink.overwrite(level, copy);
        u.held.sent(key, System.nanoTime());
        u.sent.put(key, now);
        return true;
    }

    /**
     * DH записал LOD чанка в свою базу ({@code DhApiChunkModifiedEvent}; поток DH, проверки — любой): подтверждение
     * держанной копии ({@link #sendHeld}). Мир — тот, что DH обёртывает ({@code getWrappedMcObject}).
     */
    public static void saved(Object level, int x, int z) {
        DhUpdates u = LISTENING.get(level);
        if (u != null) u.acks.add(new long[] {ChunkPos.asLong(x, z), System.nanoTime()});
    }

    /** Сервер остановлен: подтверждения больше никто не ждёт. */
    static void forgetLevels() {
        LISTENING.clear();
    }

    /**
     * Держанные копии мира с прошлого вызова (счёт начинается заново): отдано, подтверждено, без подтверждения за срок,
     * задержка подтверждённых — средняя и наибольшая (мс), ждут подтверждения сейчас.
     */
    public static long[] takeHeldStats(ServerLevel level) {
        return level.getData(ModAttachments.DH_UPDATES).held.take();
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.DH_UPDATES)) level.getData(ModAttachments.DH_UPDATES).tick(level);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (held.waiting() > 0) {
            for (long[] a; (a = acks.poll()) != null; ) held.ack(a[0], a[1]);
            held.expire(System.nanoTime());
            if (held.waiting() == 0) LISTENING.remove(level, this);
        }
        if (due.isEmpty() && offered.isEmpty()) return;
        int budget = PER_TICK;
        // собранные чанки — первыми: отметки чанков в мире подождут тик, а собранные держат память; чанк, отданный DH
        // меньше RESEND тиков назад (держанной копией или прошлой собранной), — в конец: DH пропустил бы его молча
        for (int i = 0, n = offered.size(); budget > 0 && i < n; i++) {
            ChunkAccess c = offered.poll();
            long key = c.getPos().toLong();
            if (sent.containsKey(key) && now - sent.get(key) < RESEND) {
                offered.add(c);
                continue;
            }
            if (offeredCount.addTo(key, -1) <= 1) offeredCount.remove(key);
            sink.overwrite(level, c);
            sent.put(key, now);
            budget--;
        }
        // отправленных за RESEND тиков — не больше (PER_TICK + окно держанных копий) × RESEND
        sent.long2LongEntrySet().removeIf(e -> now - e.getLongValue() >= RESEND);
        Pending pending = new Pending();
        LongArrayList notYet = new LongArrayList();
        ObjectIterator<Long2LongMap.Entry> it = due.long2LongEntrySet().fastIterator();
        int scanned = 0;
        while (budget > 0 && scanned < SCAN && it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            scanned++;
            long key = e.getLongKey();
            long at = Math.max(e.getLongValue(), sent.containsKey(key) ? sent.get(key) + RESEND : Long.MIN_VALUE);
            if (at > now) {
                e.setValue(at);
                notYet.add(key);
                continue;
            }
            it.remove();
            if (later.containsKey(key)) {
                long next = later.remove(key);
                if (next > now) pending.add(key, next);
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            sink.overwrite(level, chunk);
            sent.put(key, now);
            sentTotal++;
            budget--;
        }
        scannedMax = Math.max(scannedMax, scanned);
        // обошли не всё: не наступившие — в конец, следующий тик смотрит дальше
        if (it.hasNext()) for (int i = 0; i < notYet.size(); i++) due.getAndMoveToLast(notYet.getLong(i));
        for (int i = 0; i < pending.keys.size(); i++) due.put(pending.keys.getLong(i), pending.ats.getLong(i));
    }

    /** Отметки, которые встают снова после отправки (в карту — после обхода). */
    private static final class Pending {
        final LongArrayList keys = new LongArrayList(), ats = new LongArrayList();

        void add(long key, long at) {
            keys.add(key);
            ats.add(at);
        }
    }

    /**
     * Для проверок: ждут отправки (чанки мира, собранные), отправлено с запуска мира (чанки мира, собранные), больше
     * всего отметок, просмотренных за тик.
     */
    public static long[] stats(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return new long[] {u.due.size(), u.offered.size(), u.sentTotal, u.offeredTotal - u.offered.size(), u.scannedMax};
    }
}
