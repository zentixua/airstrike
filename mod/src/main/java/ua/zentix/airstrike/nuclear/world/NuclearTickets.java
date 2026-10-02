package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
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

    /**
     * Чанк в памяти: полностью загруженный, опущенный ниже (у края видимости) или ждущий выгрузки, — пока не пришёл
     * его {@code ChunkEvent.Unload}; null — его нет в памяти или он ещё не бывал полностью загружен ({@code ChunkEvent.Load}
     * поставит его сам). Держатель, у которого не осталось тикетов, ваниль убирает из видимой карты чанков сразу, а сам
     * чанк выгружает позже, когда его можно сохранить ({@code ChunkMap.processUnloads} → {@code pendingUnloads} →
     * {@code scheduleUnload}); вернувшийся за это время тикет возвращает держатель из {@code pendingUnloads} с тем же
     * чанком и без нового {@code ChunkEvent.Load}. Поэтому «нет в видимой карте» ещё не значит «выгружен»: очередь,
     * которая сняла бы такой чанк, больше его не увидела бы (облако 01.10.2026: два чанка зоны остались без руин и
     * держали квадрат зоны). AT: {@code ChunkMap.pendingUnloads}, только чтение.
     */
    @Nullable
    public static LevelChunk inMemory(ServerLevel level, long pos) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(pos);
        if (holder == null) holder = chunkMap.pendingUnloads.get(pos);
        return holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk chunk ? chunk : null;
    }

    /**
     * Сколько блоков от меняемого места читает Sable: на смену блока ({@code LevelChunk.setBlockState}) — соседей места,
     * а у твёрдого соседа — и его соседей ({@code VoxelNeighborhoodState}).
     */
    public static final int SABLE_REACH = 2;

    /**
     * Загружены все чанки в {@link #SABLE_REACH} блоках от места (по углам — с диагональными): неготовый чанк Sable
     * грузил бы прямо в вызове (ноутбук 01.10.2026: тик 3,5 с в очереди руин, поток сервера ждал чанк в
     * {@code ColumnScar.replace}; GameTest {@code fallenLogNextToUnreadyChunkLoadsNothing}).
     */
    public static boolean aroundLoaded(Level level, BlockPos pos) {
        for (int cx = (pos.getX() - SABLE_REACH) >> 4; cx <= (pos.getX() + SABLE_REACH) >> 4; cx++) {
            for (int cz = (pos.getZ() - SABLE_REACH) >> 4; cz <= (pos.getZ() + SABLE_REACH) >> 4; cz++) {
                if (!Terrain.ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    public static void hold(ServerLevel level, ChunkPos pos, boolean hold) {
        hold(level, TYPE, pos, hold);
    }

    /**
     * Чанк на краю загруженного мира и чанки вокруг него в радиусе {@code radius} (сколько соседей нужно его руинам) —
     * пока очередь разрушений его не пройдёт ({@link ScarQueue}).
     */
    static void holdForScar(ServerLevel level, ChunkPos pos, boolean hold, int radius) {
        hold(level, SCAR, pos, hold, radius);
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
