package ua.zentix.incoming.nuclear.flash;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * Следы вспышки в отметке чанка ({@link ModAttachments#FLASH_MARKS}) и у игроков (поток сервера): гарь ставится
 * с руинами чанка ({@code RuinPlan.apply}), силуэты — работой {@link FlashShadows}; игрок, которому чанк уходит
 * (вход, подход, смена измерения), получает его следы сразу за чанком ({@link ChunkWatchEvent.Sent}), а те, кто чанк
 * уже видит, — каждое изменение. Перед отправкой следы на сменившихся блоках (руины, игрок построил своё) уходят:
 * хеш блока метки не тот ({@link FlashMarks#pruned}).
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
        if (now == old) return;
        if (now.isEmpty()) {
            if (old == null) return;
            chunk.removeData(ModAttachments.FLASH_MARKS.get());
        } else {
            chunk.setData(ModAttachments.FLASH_MARKS.get(), now);
        }
        chunk.setUnsaved(true);
        S2C.NukeFlashMarks packet = new S2C.NukeFlashMarks(chunk.getPos().toLong(), now);
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) PacketDistributor.sendToPlayer(p, packet);
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
        if (now != old) {
            if (now.isEmpty()) chunk.removeData(ModAttachments.FLASH_MARKS.get());
            else chunk.setData(ModAttachments.FLASH_MARKS.get(), now);
            chunk.setUnsaved(true);
        }
        return now.isEmpty() ? null : new S2C.NukeFlashMarks(chunk.getPos().toLong(), now);
    }
}
