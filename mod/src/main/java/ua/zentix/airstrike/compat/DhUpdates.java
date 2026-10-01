package ua.zentix.airstrike.compat;

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
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayDeque;

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
 * </ul>
 * За тик — не больше {@link #PER_TICK} вызовов: очередь DH на мир — от 1000 позиций на поток его разбора, переполненная
 * выкидывает дальние (на выделенном сервере — дальние от 0 0) с одной строкой «overloaded» в лог DH раз в 30 с.
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

    /** Чанк → тик, не раньше которого его отправить; по порядку отметок. */
    private final Long2LongLinkedOpenHashMap due = new Long2LongLinkedOpenHashMap();
    /** Чанк → более поздняя отметка, чем ближайшая в {@link #due}: после отправки чанк ждёт её снова. */
    private final Long2LongOpenHashMap later = new Long2LongOpenHashMap();
    /** Чанк → тик последней отправки (старше {@link #RESEND} — забываются). */
    private final Long2LongOpenHashMap sent = new Long2LongOpenHashMap();
    private final ArrayDeque<ChunkAccess> offered = new ArrayDeque<>();
    private long sentTotal, offeredTotal;
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
        u.offeredTotal++;
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.DH_UPDATES)) level.getData(ModAttachments.DH_UPDATES).tick(level);
    }

    private void tick(ServerLevel level) {
        if (due.isEmpty() && offered.isEmpty()) return;
        long now = level.getGameTime();
        int budget = PER_TICK;
        // собранные чанки — первыми: отметки чанков в мире подождут тик, а собранные держат память
        for (ChunkAccess c; budget > 0 && (c = offered.poll()) != null; budget--) sink.overwrite(level, c);
        sent.long2LongEntrySet().removeIf(e -> now - e.getLongValue() >= RESEND);
        Pending pending = new Pending();
        ObjectIterator<Long2LongMap.Entry> it = due.long2LongEntrySet().fastIterator();
        while (budget > 0 && it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            long key = e.getLongKey();
            long at = Math.max(e.getLongValue(), sent.containsKey(key) ? sent.get(key) + RESEND : Long.MIN_VALUE);
            if (at > now) {
                e.setValue(at);
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

    /** Для проверок: ждут отправки (чанки мира, собранные), отправлено с запуска мира (чанки мира, собранные). */
    public static long[] stats(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return new long[] {u.due.size(), u.offered.size(), u.sentTotal, u.offeredTotal - u.offered.size()};
    }
}
