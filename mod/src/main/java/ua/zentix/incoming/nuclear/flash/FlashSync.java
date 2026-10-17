package ua.zentix.incoming.nuclear.flash;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * Следы вспышки в отметке чанка ({@link ModAttachments#FLASH_MARKS}) и у игроков (поток сервера): гарь ставится
 * с руинами чанка ({@code RuinPlan.apply}), силуэты — работой {@link FlashShadows}; игрок, которому чанк уходит
 * (вход, подход, смена измерения), получает его следы сразу за чанком ({@link ChunkWatchEvent.Sent}), а те, кто чанк
 * уже видит, — каждое изменение. Блок со следами сменился — его следы уходят сразу ({@link #onNeighborNotify}: так
 * сломанная и поставленная заново такая же доска приходит чистой), а игрокам — в конце тика мира, одним пакетом на
 * чанк. Смена без оповещения соседей (её делают моды, двигающие блоки) видна только по самому блоку: перед отправкой
 * следы на сменившихся блоках (руины, игрок построил своё) уходят — на месте не их блок ({@link FlashMarks#pruned}).
 */
public final class FlashSync {
    private FlashSync() {}

    /** Гарь подрыва {@code d} в чанке с его руинами; следы прежних вспышек на сменившихся блоках уходят. */
    public static void chars(ServerLevel level, LevelChunk chunk, Detonation d, int[] chars) {
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        if (old == null && chars.length == 0) return;
        FlashMarks now = old == null ? FlashMarks.EMPTY : old.pruned(chunk);
        if (chars.length > 0) now = now.withChars(d.id(), d.gameTime(), chars);
        put(level, chunk, old, now);
    }

    /** Силуэты подрыва {@code d} на гранях чанка (к прежним). */
    public static void shadows(ServerLevel level, LevelChunk chunk, Detonation d, int[] faces, long[] masks) {
        if (faces.length == 0) return;
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        FlashMarks base = old == null ? FlashMarks.EMPTY : old.pruned(chunk);
        put(level, chunk, old, base.withShadows(d.id(), d.gameTime(), faces, masks));
    }

    /** Следы чанка как есть (для проверок). */
    public static FlashMarks of(LevelChunk chunk) {
        FlashMarks m = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        return m == null ? FlashMarks.EMPTY : m;
    }

    private static void put(ServerLevel level, LevelChunk chunk, @Nullable FlashMarks old, FlashMarks now) {
        if (now == old || now.isEmpty() && old == null) return;
        store(chunk, now);
        send(level, chunk, now);
        // отложенная отправка того же чанка уже не нужна
        if (level.hasData(ModAttachments.FLASH_PENDING)) level.getData(ModAttachments.FLASH_PENDING).chunks.remove(chunk.getPos().toLong());
    }

    private static void store(LevelChunk chunk, FlashMarks now) {
        if (now.isEmpty()) chunk.removeData(ModAttachments.FLASH_MARKS.get());
        else chunk.setData(ModAttachments.FLASH_MARKS.get(), now);
        chunk.setUnsaved(true);
    }

    /** Следы чанка — тем, кто его видит (пустые — клиент их забывает). */
    private static void send(ServerLevel level, LevelChunk chunk, FlashMarks now) {
        S2C.NukeFlashMarks packet = new S2C.NukeFlashMarks(chunk.getPos().toLong(), now);
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) PacketDistributor.sendToPlayer(p, packet);
    }

    /**
     * Блок сменился, и соседи узнают об этом (поток сервера): ломает игрок, взрыв, огонь, поршень, команда, ставят
     * блок. Следы этого места, если блок уже не их, уходят из отметки сразу — иначе сломанная и тут же поставленная
     * заново такая же доска снова показала бы гарь (хеш блока тот же). Игрокам — в конце тика мира ({@link #onLevelTick}):
     * взрыв меняет десятки блоков чанка за тик. Оповещение без смены блока (рычаг на стене, провод) следы не трогает.
     */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        if (old == null) return;
        FlashMarks now = old.prunedAt(pos.getX() & 15, pos.getY(), pos.getZ() & 15, e.getState());
        if (now == old) return;
        store(chunk, now);
        level.getData(ModAttachments.FLASH_PENDING).chunks.add(chunk.getPos().toLong());
    }

    /** Конец тика мира: чанкам, чьи следы ушли со сменившимися блоками, — новые следы тем, кто их видит. */
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.FLASH_PENDING)) return;
        LongOpenHashSet chunks = level.getData(ModAttachments.FLASH_PENDING).chunks;
        if (chunks.isEmpty()) return;
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) send(level, chunk, of(chunk));
        }
        chunks.clear();
    }

    /** Чанки мира, чьи следы сменились в этом тике и ещё не ушли игрокам (несохраняемый attachment мира). */
    public static final class Pending {
        final LongOpenHashSet chunks = new LongOpenHashSet();
    }

    /** Чанк ушёл игроку: его следы — следом. */
    public static void onChunkSent(ChunkWatchEvent.Sent e) {
        S2C.NukeFlashMarks packet = packet(e.getChunk());
        if (packet != null) PacketDistributor.sendToPlayer(e.getPlayer(), packet);
    }

    /**
     * Пакет следов чанка для игрока, которому чанк уходит (поток сервера); следы на сменившихся блоках сперва уходят
     * и из отметки. Null — следов нет.
     */
    @Nullable
    public static S2C.NukeFlashMarks packet(LevelChunk chunk) {
        FlashMarks old = chunk.getExistingDataOrNull(ModAttachments.FLASH_MARKS.get());
        if (old == null) return null;
        FlashMarks now = old.pruned(chunk);
        if (now != old) store(chunk, now);
        return now.isEmpty() ? null : new S2C.NukeFlashMarks(chunk.getPos().toLong(), now);
    }
}
