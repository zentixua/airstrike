package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.UUID;

/**
 * Сущности вне тикающих чанков не тикают, а ракета проходит 0.7 чанка за тик. Снаряд держит свой чанк и чанк впереди
 * по курсу, пока летит. Это ванильный тикет региона через {@link AreaLoader} (как у {@link FlightTickets}: тикать
 * чанки начинают, только когда готовы соседи): он не сохраняется в мир и ничего не грузит синхронно, в отличие от
 * {@code TicketController.forceChunk} NeoForge, который пишет каждое изменение в {@code ForcedChunksSavedData} и сразу
 * догружает чанк. Ключ — UUID снаряда: соседи по залпу не снимают тикет друг у друга.
 */
public final class ChunkTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_projectile", Comparator.<UUID>naturalOrder());
    /**
     * Уровень тикета 33 − 2 = 31: чанк тикает сущности. Принудительно загруженным он от этого не становится: мир без
     * игроков всё равно засыпает через 300 тиков — пока идёт удар, его будит {@link StrikeWorld}.
     */
    private static final int DISTANCE = 2;

    private ChunkTickets() {}

    public static void hold(ServerLevel level, UUID owner, long chunk, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(TYPE, new ChunkPos(chunk), DISTANCE, owner);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }

    /** Держит ли снаряд {@code owner} чанк {@code chunk} (проверки). */
    public static boolean holds(ServerLevel level, UUID owner, long chunk) {
        return StrikeWorld.get(level).areas().holds(new AreaLoader.Area(TYPE, new ChunkPos(chunk), DISTANCE, owner));
    }
}
