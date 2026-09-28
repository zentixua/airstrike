package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.UUID;

/**
 * Район цели снаряда: ванильный тикет грузит (и генерирует) чанки в фоне, пока снаряд на подлёте, — к его прибытию
 * там тикают сущности, и он (в мире или вернувшись в мир из полёта вне его) бьёт как обычно.
 * У каждого снаряда свой тикет (ключ — его UUID): залп по одной точке не снимает тикет друг у друга.
 * Тикеты не сохраняются: после перезапуска снаряд возьмёт свой заново.
 */
public final class FlightTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_flight", Comparator.<UUID>naturalOrder());
    /**
     * Уровень тикета 33 − 4 = 29: сущности тикают в квадрате 5×5 чанков вокруг цели (±40 блоков), загружено 9×9 —
     * снаряд появляется в мире до цели, а соседние чанки готовы для взрыва и обломков.
     */
    public static final int DISTANCE = 4;

    private FlightTickets() {}

    public static void hold(ServerLevel level, ChunkPos pos, UUID flight, boolean hold) {
        hold(level, pos, DISTANCE, flight, hold);
    }

    /** С другим уровнем тикета: {@code distance} 6 — сущности тикают в квадрате 9×9 чанков. */
    public static void hold(ServerLevel level, ChunkPos pos, int distance, UUID flight, boolean hold) {
        if (hold) level.getChunkSource().addRegionTicket(TYPE, pos, distance, flight);
        else level.getChunkSource().removeRegionTicket(TYPE, pos, distance, flight);
    }
}
