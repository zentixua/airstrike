package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.UUID;

/**
 * Сущности вне тикающих чанков не тикают, а ракета проходит 0.7 чанка за тик. Снаряд держит свой чанк и чанк впереди
 * по курсу, пока летит. Это ванильный тикет региона (как у {@link FlightTickets}): он не сохраняется в мир и ничего
 * не грузит синхронно, в отличие от {@code TicketController.forceChunk} NeoForge, который пишет каждое изменение
 * в {@code ForcedChunksSavedData} и сразу догружает чанк. Ключ — UUID снаряда: соседи по залпу не снимают тикет друг у друга.
 */
public final class ChunkTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_projectile", Comparator.<UUID>naturalOrder());
    /** Уровень тикета 33 − 2 = 31: в самом чанке тикают сущности, как у принудительно загруженного тикающего чанка. */
    private static final int DISTANCE = 2;

    private ChunkTickets() {}

    public static void hold(ServerLevel level, UUID owner, long chunk, boolean hold) {
        ChunkPos pos = new ChunkPos(chunk);
        if (hold) level.getChunkSource().addRegionTicket(TYPE, pos, DISTANCE, owner);
        else level.getChunkSource().removeRegionTicket(TYPE, pos, DISTANCE, owner);
    }
}
