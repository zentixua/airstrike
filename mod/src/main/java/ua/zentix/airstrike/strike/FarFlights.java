package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Снаряды, которых у клиента нет. Сущность уходит клиенту только в пределах его дальности прорисовки (и ближе дальности
 * слежения своего типа), а в полёте вне мира ({@link VirtualFlights}) её нет ни у кого, — но мотор ракеты или вой
 * снаряда РСЗО слышно дальше, а факел, шлейф и сам корпус видно ещё дальше. Каждому игроку уходят снаряды его мира,
 * которых у него нет и которые он уже может слышать ({@link Hearing#range}) или видеть ({@link #range}): слышимые —
 * раз в {@link S2C.FarFlights#PERIOD} тика, только видимые — раз в {@link S2C.FarFlights#FAR_PERIOD}, не больше
 * {@link #LIMIT} ближних. Клиент ведёт по ним тот же звук, что и по сущности, и рисует снаряд вдали, а когда сущность
 * появится, продолжает по ней без шва.
 */
public final class FarFlights {
    /** Снарядов в пакете одному игроку, не больше: ближние (дальние в большом залпе — точки в одном пикселе). */
    static final int LIMIT = 64;

    private FarFlights() {}

    public static void onServerTick(ServerTickEvent.Post e) {
        int tick = e.getServer().getTickCount();
        if (tick % S2C.FarFlights.PERIOD != 0) return;
        boolean silent = tick % S2C.FarFlights.FAR_PERIOD == 0;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            List<StrikeProjectile> flights = flights(level);
            if (flights.isEmpty()) continue;
            for (ServerPlayer player : level.players()) {
                List<S2C.FarFlight> sent = sample(level, flights, player.position(), player, silent);
                if (!sent.isEmpty()) PacketDistributor.sendToPlayer(player, new S2C.FarFlights(sent));
            }
        }
    }

    /** Все снаряды мира в полёте: в мире и вне его (на пусковой в закрытой ячейке — нет). */
    public static List<StrikeProjectile> flights(ServerLevel level) {
        List<StrikeProjectile> flights = StrikeWorld.projectiles(level);
        flights.removeIf(p -> !p.isActive());
        return flights;
    }

    /** Докуда игрокам видно снаряды и взрывы, блоков (настройка мира). */
    public static double range() {
        return AirstrikeConfig.SERVER.farRange.get();
    }

    /**
     * Что из точки {@code eye} слышно или видно из того, чего у игрока {@code player} нет (без игрока — всё), не больше
     * {@link #LIMIT} ближних.
     *
     * @param silent и те, которых не слышно, а только видно (они уходят реже)
     */
    public static List<S2C.FarFlight> sample(ServerLevel level, List<StrikeProjectile> flights, Vec3 eye, @Nullable ServerPlayer player, boolean silent) {
        double sight = silent ? range() : 0;
        List<Candidate> found = new ArrayList<>();
        for (StrikeProjectile p : flights) {
            double hear = Hearing.range(p), d2 = p.position().distanceToSqr(eye);
            boolean audible = hear > 0 && d2 <= hear * hear;
            if (!audible && d2 > sight * sight) continue;
            if (player == null || p.isVirtual() || !seenBy(level, p, player)) found.add(new Candidate(p, d2, audible));
        }
        if (found.size() > LIMIT) {
            found.sort(Comparator.comparingDouble(Candidate::d2));
            found = found.subList(0, LIMIT);
        }
        List<S2C.FarFlight> out = new ArrayList<>(found.size());
        for (Candidate c : found) out.add(sample(c.p, c.audible));
        return out;
    }

    private record Candidate(StrikeProjectile p, double d2, boolean audible) {}

    /** Сущность уже есть у этого игрока — её звук и картинку он ведёт сам. */
    private static boolean seenBy(ServerLevel level, StrikeProjectile p, ServerPlayer player) {
        ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(p.getId());
        return tracked != null && tracked.seenBy.contains(player.connection);
    }

    /** Снаряд в мире есть сущностью хоть у одного игрока; у остальных его путь — пакетами отсюда. */
    public static boolean seenByAnyone(ServerLevel level, StrikeProjectile p) {
        ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(p.getId());
        return tracked != null && !tracked.seenBy.isEmpty();
    }

    private static S2C.FarFlight sample(StrikeProjectile p, boolean audible) {
        return new S2C.FarFlight(p.getUUID(), p.weapon().id(), p instanceof BomberEntity, p instanceof BunkerBusterEntity b && b.isDrilling(),
                audible, p.position(), p.velocity(), p.getYRot(), p.getXRot(), p.roll(), p.flightPhase().ordinal(), p.phaseAge(), p.aimPoint());
    }
}
