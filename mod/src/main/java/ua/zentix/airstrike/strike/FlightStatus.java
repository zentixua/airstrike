package ua.zentix.airstrike.strike;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Раз в 5 тиков каждому, у кого есть снаряды в полёте (во всех мирах, в том числе вне загруженных чанков), —
 * список: фаза, время до удара, где снаряд. Когда всё долетело — один пустой список, чтобы HUD погас.
 * МБР сюда не входит (её отсчёт ведёт ядерный HUD), ушедший после сброса B-2 — тоже.
 */
public final class FlightStatus {
    private static final int PERIOD = 5;
    private static final Set<UUID> LAST = new HashSet<>();

    private FlightStatus() {}

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % PERIOD != 0) return;
        Map<UUID, List<S2C.Flight>> byOwner = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (StrikeProjectile p : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true)) collect(byOwner, p);
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) collect(byOwner, p);
        }
        for (Map.Entry<UUID, List<S2C.Flight>> en : byOwner.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p != null) PacketDistributor.sendToPlayer(p, new S2C.Flights(en.getValue()));
        }
        for (UUID id : LAST) {
            if (byOwner.containsKey(id)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) PacketDistributor.sendToPlayer(p, new S2C.Flights(List.of()));
        }
        LAST.clear();
        LAST.addAll(byOwner.keySet());
    }

    private static void collect(Map<UUID, List<S2C.Flight>> byOwner, StrikeProjectile p) {
        UUID owner = p.ownerId();
        if (owner == null || p.isRemoved() || p instanceof IcbmEntity || p instanceof BomberEntity b && b.hasReleased()) return;
        byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(new S2C.Flight(p.getUUID(), p.weapon().id(), p.flightPhase().ordinal(),
                p.etaTicks(), p.position(), p.aimPoint(), p.isNuclear()));
    }
}
