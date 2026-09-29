package ua.zentix.airstrike.strike;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.target.Target;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Раз в 5 тиков каждому, у кого есть снаряды в полёте (во всех мирах, в том числе вне загруженных чанков), —
 * список: фаза, время до удара, где снаряд. Когда всё долетело — один пустой список, чтобы HUD погас.
 * МБР сюда не входит (её отсчёт ведёт ядерный HUD), ушедший после сброса B-2 — тоже.
 */
public final class FlightStatus {
    private static final int PERIOD = 5;

    private FlightStatus() {}

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % PERIOD != 0) return;
        Map<UUID, List<S2C.Flight>> byOwner = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (StrikeProjectile p : StrikeWorld.projectiles(level)) collect(byOwner, p);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            List<S2C.Flight> flights = byOwner.get(p.getUUID());
            if (flights != null) {
                PacketDistributor.sendToPlayer(p, new S2C.Flights(flights));
                p.setData(ModAttachments.FLIGHTS_SHOWN, true);
            } else if (p.getData(ModAttachments.FLIGHTS_SHOWN)) {
                PacketDistributor.sendToPlayer(p, new S2C.Flights(List.of()));
                p.setData(ModAttachments.FLIGHTS_SHOWN, false);
            }
        }
    }

    private static void collect(Map<UUID, List<S2C.Flight>> byOwner, StrikeProjectile p) {
        UUID owner = p.ownerId();
        if (owner == null || p.isRemoved() || p instanceof IcbmEntity || p instanceof BomberEntity b && b.hasReleased()) return;
        int kind = S2C.Flight.TARGET_POINT;
        String name = "";
        Target t = p.target();
        if (t instanceof Target.OfEntity e) {
            Entity ent = p.level() instanceof ServerLevel sl ? sl.getEntity(e.uuid()) : null;
            if (ent instanceof Player || ent != null && ent.hasCustomName()) {
                kind = S2C.Flight.TARGET_NAMED;
                name = ent.getName().getString();
            } else if (ent != null) {
                kind = S2C.Flight.TARGET_TYPE;
                name = ent.getType().getDescriptionId();
            }
        } else if (t instanceof Target.OfSubLevel s) {
            kind = S2C.Flight.TARGET_AIRCRAFT;
            SubLevelAccess sub = SubLevels.containing(p.level(), s.plotPos());
            String n = sub == null ? null : SubLevels.name(sub);
            name = n == null ? "" : n;
        }
        if (name.length() > S2C.Flight.MAX_NAME) name = name.substring(0, S2C.Flight.MAX_NAME);
        byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(new S2C.Flight(p.getUUID(), p.weapon().id(), p.flightPhase().ordinal(),
                p.etaTicks(), p.position(), p.aimPoint(), p.isNuclear(), kind, name, p.targetLost()));
    }
}
