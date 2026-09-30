package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.airstrike.strike.AreaLoader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Тикеты чанков в очереди ванили ({@code DistanceManager.tickets}): сколько их с данным ключом. Тикеты мода держат
 * ключ — UUID снаряда (или игрока); у тикета загрузки {@link AreaLoader} значение — сам район, ключ — его.
 */
public final class TicketProbe {
    private static final Field TICKETS, KEY;

    static {
        try {
            TICKETS = DistanceManager.class.getDeclaredField("tickets");
            TICKETS.setAccessible(true);
            KEY = Ticket.class.getDeclaredField("key");
            KEY.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TicketProbe() {}

    /** Тикеты типа с именем {@code type} и ключом {@code key} во всём мире. */
    public static int count(ServerLevel level, String type, UUID key) {
        return count(level, t -> t.toString().equals(type), key);
    }

    /** Тикеты любого типа под {@code type} с ключом {@code key} во всём мире. */
    @SuppressWarnings("unchecked")
    public static int count(ServerLevel level, Predicate<TicketType<?>> type, UUID key) {
        try {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    if (type.test(t.getType()) && key.equals(key(t))) n++;
                }
            }
            return n;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Типы тикетов с ключом {@code key} во всём мире, по одному на тикет (для сообщений проверок). */
    @SuppressWarnings("unchecked")
    public static List<String> types(ServerLevel level, UUID key) {
        try {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            List<String> out = new ArrayList<>();
            for (var e : map.long2ObjectEntrySet()) {
                for (Ticket<?> t : e.getValue()) {
                    if (key.equals(key(t))) out.add(t.getType() + "@" + new ChunkPos(e.getLongKey()));
                }
            }
            return out;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ключ тикета (у тикета загрузки района — ключ района). */
    public static Object key(Ticket<?> t) throws IllegalAccessException {
        Object k = KEY.get(t);
        return k instanceof AreaLoader.Area a ? a.key() : k;
    }
}
