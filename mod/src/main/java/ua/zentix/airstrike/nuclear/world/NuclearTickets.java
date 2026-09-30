package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.util.Comparator;
import java.util.UUID;

/**
 * Загрузка чанков для ядерного удара — пятно воронки и край очереди разрушений. Ванильные тикеты грузят и
 * генерируют чанк в фоне (тикет NeoForge {@code forceChunk} грузил бы его сразу, останавливая сервер), не
 * сохраняются в мире и сами пропадают при перезапуске; кто ждёт чанк — проверяет {@link Terrain#ready}.
 * <p>
 * Менять блоки можно только там, где загружены и соседи: Sable на каждое изменение блока читает соседние блоки
 * (физика аппаратов), и на краю загруженного мира это синхронно грузило соседний чанк — по 30–300 мс на столбец.
 * <p>
 * Чанки берутся через {@link AreaLoader}: тикет региона сразу сделал бы чанк тикающим, пока соседи ещё генерируются, —
 * и хранилище испытаний в нём грузило соседа синхронно (облако 29.09.2026: 2,5 с у чанков очереди разрушений).
 */
public final class NuclearTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_nuclear", Comparator.<UUID>naturalOrder());
    /**
     * Свой тип для очереди разрушений: одинаковый тикет (тип, уровень, значение) у ванили один на всех, и снятие
     * тикета очередью сняло бы тикет воронки на том же чанке.
     */
    private static final TicketType<UUID> SCAR = TicketType.create("airstrike_nuclear_scar", Comparator.<UUID>naturalOrder());
    /** Чанк и соседи вокруг — полностью загружены (и соседние столбцы, и края воронки). */
    private static final int RADIUS = 1;

    private NuclearTickets() {}

    /** Чанк и все восемь соседей загружены целиком. */
    public static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos pos) {
        return neighbourhoodLoaded(level, pos, 1);
    }

    /** Чанк и все чанки в радиусе {@code r} вокруг загружены целиком. */
    public static boolean neighbourhoodLoaded(ServerLevel level, ChunkPos pos, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
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

    /**
     * Чанк на краю загруженного мира и чанки вокруг него (руинам нужны соседи в радиусе {@link RuinPlanner#REACH}) —
     * пока очередь разрушений его не пройдёт ({@link ScarQueue}).
     */
    static void holdForScar(ServerLevel level, ChunkPos pos, boolean hold) {
        hold(level, SCAR, pos, hold, RuinPlanner.REACH);
    }

    private static void hold(ServerLevel level, TicketType<UUID> type, ChunkPos pos, boolean hold) {
        hold(level, type, pos, hold, RADIUS);
    }

    private static void hold(ServerLevel level, TicketType<UUID> type, ChunkPos pos, boolean hold, int radius) {
        // один район на чанк и тип, как один ванильный тикет (тип, уровень, значение): повторный hold ничего не добавляет
        AreaLoader.Area area = new AreaLoader.Area(type, pos, radius, new UUID(0L, pos.toLong()));
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }
}
