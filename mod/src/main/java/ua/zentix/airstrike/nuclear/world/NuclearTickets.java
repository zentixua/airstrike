package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import ua.zentix.airstrike.util.Terrain;

import java.util.Comparator;

/**
 * Загрузка чанков для ядерного удара — чанк эпицентра перед подрывом и пятно воронки. Ванильные тикеты грузят и
 * генерируют чанк в фоне (тикет NeoForge {@code forceChunk} грузил бы его сразу, останавливая сервер), не
 * сохраняются в мире и сами пропадают при перезапуске; кто ждёт чанк — проверяет {@link Terrain#ready}.
 * <p>
 * Менять блоки можно только там, где загружены и соседи: Sable на каждое изменение блока читает соседние блоки
 * (физика аппаратов), и на краю загруженного мира это синхронно грузило соседний чанк — по 30–300 мс на столбец.
 */
public final class NuclearTickets {
    private static final TicketType<ChunkPos> TYPE = TicketType.create("airstrike_nuclear", Comparator.comparingLong(ChunkPos::toLong));
    /**
     * Свой тип для очереди разрушений: одинаковый тикет (тип, уровень, значение) у ванили один на всех, и снятие
     * тикета очередью сняло бы тикет воронки на том же чанке.
     */
    private static final TicketType<ChunkPos> SCAR = TicketType.create("airstrike_nuclear_scar", Comparator.comparingLong(ChunkPos::toLong));
    /** Чанк и соседи вокруг — полностью загружены (и соседние столбцы, и края воронки). */
    private static final int RADIUS = 1;

    private NuclearTickets() {}

    /** Чанк и все восемь соседей загружены целиком. */
    public static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!Terrain.ready(level, pos.x + dx, pos.z + dz)) return false;
            }
        }
        return true;
    }

    /** Загружены все чанки, которых касаются соседи блока (по углам — с диагональными). */
    public static boolean aroundLoaded(Level level, BlockPos pos) {
        return Terrain.ready(level, pos.offset(-1, 0, -1)) && Terrain.ready(level, pos.offset(1, 0, -1))
                && Terrain.ready(level, pos.offset(-1, 0, 1)) && Terrain.ready(level, pos.offset(1, 0, 1));
    }

    public static void hold(ServerLevel level, ChunkPos pos, boolean hold) {
        hold(level, TYPE, pos, hold);
    }

    /** Чанк на краю загруженного мира и его соседи — пока очередь разрушений его не пройдёт ({@link ScarQueue}). */
    static void holdForScar(ServerLevel level, ChunkPos pos, boolean hold) {
        hold(level, SCAR, pos, hold);
    }

    private static void hold(ServerLevel level, TicketType<ChunkPos> type, ChunkPos pos, boolean hold) {
        if (hold) level.getChunkSource().addRegionTicket(type, pos, RADIUS, pos);
        else level.getChunkSource().removeRegionTicket(type, pos, RADIUS, pos);
    }
}
