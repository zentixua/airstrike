package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.PickHints;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Замер потока сервера вокруг ударов на копии мира игрока ({@code tools/prod_client.py strike-profile --world …
 * --prop airstrike.profile.steps=…}, полная сборка, JFR — {@code --jvm -XX:StartFlightRecording=…}): шаги через «;»,
 * каждый — {@code tp x y z} (перенос игрока, как телепорт в игре) или {@code оружие сколько разброс x y z} (удар от
 * имени игрока, как с пульта: за 5 с до него — подсказка карты по той же точке, как клик по карте). Первый шаг —
 * через 30 с после входа, следующие — через {@code airstrike.profile.gap} тиков (по умолчанию 2400). В лог: каждый
 * тик сервера дольше 50 мс ({@code SCENARIO strike-profile tick}; «tick» — сам тик, «period» — от начала прошлого
 * тика: туда входят и задачи потока сервера между тиками — разбор чанков с диска, перевод в FULL) и раз в секунду
 * средний тик, самый долгий и сколько настенного времени заняли 20 тиков ({@code SCENARIO strike-profile second}) —
 * со временем от последнего шага.
 */
final class StrikeProfile {
    private static final long SLOW_NANOS = 50_000_000L;
    private static final int FIRST = 600, PICK_LEAD = 100;

    private final List<String[]> steps = new ArrayList<>();
    private final int gap = Integer.getInteger("airstrike.profile.gap", 2400);
    private int tick;
    /** Серверный тик последнего шага и его имя (для строк лога). */
    private volatile long stepServerTick = -1;
    private volatile String stepName = "-";
    // поток сервера
    private long tickStart, secondStart, secondNanos, secondMax;
    private int secondTicks;

    StrikeProfile() {
        String spec = System.getProperty("airstrike.profile.steps", "");
        for (String s : spec.split(";")) {
            String[] a = s.strip().split("\\s+");
            if (a.length == 4 && a[0].equals("tp") || a.length == 6) steps.add(a);
            else if (!s.isBlank()) Airstrike.LOG.warn("SCENARIO strike-profile: шаг «{}» пропущен — нужно «tp x y z» или «оружие сколько разброс x y z»", s);
        }
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
    }

    private void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || server == null) return;
        tick++;
        // неуязвим: удар по месту игрока — тоже шаг замера
        if (tick == 20) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "gamemode creative " + player(server).getGameProfile().getName()));
        for (int i = 0; i < steps.size(); i++) {
            String[] a = steps.get(i);
            int at = FIRST + i * gap;
            if (!a[0].equals("tp") && tick == at - PICK_LEAD) server.execute(() -> pick(server, a));
            if (tick == at) server.execute(() -> run(server, a));
        }
        if (tick == FIRST + steps.size() * gap) {
            Airstrike.LOG.info("SCENARIO done");
            mc.stop();
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void pick(MinecraftServer server, String[] a) {
        ServerPlayer p = player(server);
        PickHints.pick(p.serverLevel(), p.getUUID(), p.getData(ModAttachments.PICK_HINT.get()), Double.parseDouble(a[3]), Double.parseDouble(a[5]));
    }

    private void run(MinecraftServer server, String[] a) {
        ServerPlayer p = player(server);
        String name = p.getGameProfile().getName();
        String c = a[0].equals("tp")
                ? String.format(Locale.ROOT, "tp %s %s %s %s", name, a[1], a[2], a[3])
                : String.format(Locale.ROOT, "execute as %s at @s run airstrike salvo %s %s %s at %s %s %s", name, a[0], a[1], a[2], a[3], a[4], a[5]);
        stepServerTick = server.getTickCount();
        stepName = String.join(" ", a);
        Airstrike.LOG.info("SCENARIO strike-profile step «{}» at {} {} {}: chunks={}", stepName, Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ()),
                p.serverLevel().getChunkSource().getLoadedChunksCount());
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
    }

    private void onServerTickPre(ServerTickEvent.Pre e) {
        long now = System.nanoTime();
        if (tickStart != 0 && now - tickStart > SLOW_NANOS) {
            Airstrike.LOG.info("SCENARIO strike-profile period {} ms after «{}» {} s", (now - tickStart) / 1_000_000, stepName, since(e.getServer()));
        }
        if (secondTicks == 0) secondStart = now;
        tickStart = now;
    }

    private String since(MinecraftServer server) {
        return stepServerTick < 0 ? "-" : String.format(Locale.ROOT, "%+.2f", (server.getTickCount() - stepServerTick) / 20.0);
    }

    private void onServerTickPost(ServerTickEvent.Post e) {
        long end = System.nanoTime(), took = end - tickStart;
        if (took > SLOW_NANOS) {
            Airstrike.LOG.info("SCENARIO strike-profile tick {} ms after «{}» {} s", took / 1_000_000, stepName, since(e.getServer()));
        }
        secondNanos += took;
        secondMax = Math.max(secondMax, took);
        if (++secondTicks == 20) {
            Airstrike.LOG.info("SCENARIO strike-profile second mspt={} max={} wall={} after «{}» {} s", String.format(Locale.ROOT, "%.1f", secondNanos / 20 / 1e6),
                    secondMax / 1_000_000, (end - secondStart) / 1_000_000, stepName, since(e.getServer()));
            secondNanos = 0;
            secondMax = 0;
            secondTicks = 0;
        }
    }
}
