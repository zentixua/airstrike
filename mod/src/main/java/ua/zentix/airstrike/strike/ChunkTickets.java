package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import ua.zentix.airstrike.Airstrike;

/**
 * Сущности вне тикающих чанков не тикают, а ракета проходит 0.7 чанка за тик. Снаряд держит тикет на свой чанк
 * и на чанк впереди по курсу, пока летит. После перезапуска сервера старые тикеты снимаются —
 * загруженный снаряд сам возьмёт новые.
 */
public final class ChunkTickets {
    public static final TicketController CONTROLLER = new TicketController(Airstrike.id("projectile"),
            (level, helper) -> java.util.List.copyOf(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));

    private ChunkTickets() {}

    public static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    public static void force(ServerLevel level, Entity owner, long chunk, boolean add) {
        CONTROLLER.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), add, true);
    }
}
