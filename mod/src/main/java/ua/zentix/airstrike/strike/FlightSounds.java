package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;

import java.util.ArrayList;
import java.util.List;

/**
 * Звук снаряда, которого у клиента нет. Сущность уходит клиенту только в пределах его дальности прорисовки
 * (и ближе дальности слежения своего типа), а в полёте вне мира ({@link VirtualFlights}) её нет ни у кого, — но
 * мотор ракеты или вой снаряда РСЗО слышно дальше. Раз в {@link S2C.Heard#PERIOD} тика каждому игроку уходят
 * те снаряды его мира, которые он уже может слышать ({@link Hearing#range}) и которых у него нет; клиент ведёт
 * по ним тот же звук, что и по сущности, а когда сущность появится, продолжает его по ней без шва.
 */
public final class FlightSounds {
    private FlightSounds() {}

    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % S2C.Heard.PERIOD != 0) return;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            List<StrikeProjectile> flights = flights(level);
            if (flights.isEmpty()) continue;
            for (ServerPlayer player : level.players()) {
                List<S2C.HeardFlight> heard = heard(level, flights, player.position(), player);
                if (!heard.isEmpty()) PacketDistributor.sendToPlayer(player, new S2C.Heard(heard));
            }
        }
    }

    /** Все снаряды мира в полёте: в мире и вне его (на пусковой в закрытой ячейке — нет). */
    public static List<StrikeProjectile> flights(ServerLevel level) {
        List<StrikeProjectile> flights = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), StrikeProjectile::isActive));
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
            if (p.isActive()) flights.add(p);
        }
        return flights;
    }

    /** Что слышно в точке {@code ear} из того, чего у игрока {@code player} нет (без игрока — всё слышимое). */
    public static List<S2C.HeardFlight> heard(ServerLevel level, List<StrikeProjectile> flights, Vec3 ear, @Nullable ServerPlayer player) {
        List<S2C.HeardFlight> heard = new ArrayList<>();
        for (StrikeProjectile p : flights) {
            double r = Hearing.range(p);
            if (r > 0 && p.position().distanceToSqr(ear) <= r * r && (player == null || p.isVirtual() || !seenBy(level, p, player))) {
                heard.add(sample(p));
            }
        }
        return heard;
    }

    /** Сущность уже есть у этого игрока — её звук он ведёт сам. */
    private static boolean seenBy(ServerLevel level, StrikeProjectile p, ServerPlayer player) {
        ChunkMap.TrackedEntity tracked = level.getChunkSource().chunkMap.entityMap.get(p.getId());
        return tracked != null && tracked.seenBy.contains(player.connection);
    }

    private static S2C.HeardFlight sample(StrikeProjectile p) {
        return new S2C.HeardFlight(p.getUUID(), p.weapon().id(), p instanceof BomberEntity, p instanceof BunkerBusterEntity b && b.isDrilling(),
                p.position(), p.getYRot(), p.getXRot(), p.flightPhase().ordinal(), p.phaseAge(), (float) p.position().distanceTo(p.aimPoint()));
    }
}
