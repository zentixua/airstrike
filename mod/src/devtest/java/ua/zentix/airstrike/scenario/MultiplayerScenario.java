package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.hud.ClientFlights;

import java.util.Locale;

/**
 * Мультиплеер (tools/mp_scenario.sh): выделенный сервер и два клиента. {@code mp-a} (Alpha, оператор) бьёт по игроку
 * Bravo и по точке, пока снаряды летят — выходит и заходит снова; {@code mp-b} (Bravo, цель) выходит посреди подлёта
 * и возвращается. Проверяется, что сервер и клиенты это переживают: снаряды по ушедшей цели идут в последнюю
 * точку, владелец после возвращения снова видит свои полёты, в логах нет ошибок.
 * Время — тики клиента от старта (идут и в меню), шаги — по состоянию подключения.
 */
final class MultiplayerScenario {
    private static final String ADDRESS = "127.0.0.1:" + System.getProperty("airstrike.mp.port", "25599");

    private final boolean attacker;
    private int tick;
    private int joinedAt = -1;
    private int rejoins;
    private int leftAt = -1;
    private int lastConnect = -1000;
    private boolean fired;
    /** Bravo: тик (от входа), когда в списке игроков появился Alpha. */
    private int alphaSeen = -1;

    MultiplayerScenario(boolean attacker) {
        this.attacker = attacker;
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        tick++;
        boolean inGame = mc.player != null && mc.level != null;
        if (!inGame) {
            joinedAt = -1;
            // сервер мог ещё не подняться, а после выхода — ждём паузу и заходим снова
            boolean away = leftAt >= 0 && tick - leftAt < 200;
            if (!away && mc.screen != null && !(mc.screen instanceof ConnectScreen) && tick - lastConnect > 200) connect(mc);
            if (tick > 12_000) finish(mc, "не зашёл на сервер");
            return;
        }
        if (joinedAt < 0) {
            joinedAt = tick;
            alphaSeen = -1;
            Airstrike.LOG.info("SCENARIO mp joined as {} (rejoins {})", mc.player.getGameProfile().getName(), rejoins);
        }
        int t = tick - joinedAt;
        if (t % 20 == 0 && !ClientFlights.all().isEmpty()) logFlights();
        if (attacker) attackerStep(mc, t);
        else targetStep(mc, t);
    }

    /** Alpha: ждёт Bravo, бьёт по нему и по точке рядом, выходит посреди подлёта, возвращается и доживает до конца. */
    private void attackerStep(Minecraft mc, int t) {
        if (!fired && t >= 100 && mc.getConnection().getPlayerInfo("Bravo") != null) {
            fired = true;
            cmd(mc, "gamemode creative");
            cmd(mc, "airstrike salvo drone 3 0 Bravo");
            cmd(mc, "airstrike salvo missile 3 0 Bravo");
            cmd(mc, String.format(Locale.ROOT, "airstrike salvo rocket 6 10 at %.1f %.1f %.1f", mc.player.getX() + 60, mc.player.getY(), mc.player.getZ()));
            leftAt = -1;
        }
        if (fired && rejoins == 0 && t == 260) leave(mc);
        if (rejoins == 1 && t == 1200) finish(mc, "done");
    }

    /** Bravo: заходит, ждёт Alpha, выходит, пока снаряды к нему летят (Alpha бьёт через 5 с после входа), возвращается. */
    private void targetStep(Minecraft mc, int t) {
        if (alphaSeen < 0 && mc.getConnection().getPlayerInfo("Alpha") != null) alphaSeen = t;
        if (rejoins == 0 && alphaSeen >= 0 && t == alphaSeen + 240) leave(mc);
        if (rejoins == 1 && t == 1000) finish(mc, "done");
    }

    private void connect(Minecraft mc) {
        lastConnect = tick;
        if (leftAt >= 0) {
            rejoins++;
            leftAt = -1;
        }
        Airstrike.LOG.info("SCENARIO mp connect {}", ADDRESS);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ADDRESS),
                new ServerData("airstrike-mp", ADDRESS, ServerData.Type.OTHER), false, null);
    }

    private void leave(Minecraft mc) {
        Airstrike.LOG.info("SCENARIO mp leave, flights {}", ClientFlights.all().size());
        leftAt = tick;
        mc.level.disconnect();
        mc.disconnect(new TitleScreen());
    }

    private void finish(Minecraft mc, String why) {
        Airstrike.LOG.info("SCENARIO mp {}", why);
        Airstrike.LOG.info("SCENARIO done");
        mc.stop();
    }

    private static void cmd(Minecraft mc, String c) {
        mc.player.connection.sendCommand(c);
        Airstrike.LOG.info("SCENARIO /{}", c);
    }

    private static void logFlights() {
        StringBuilder sb = new StringBuilder();
        for (var f : ClientFlights.all()) {
            sb.append(' ').append(f.weapon().name()).append('#').append(f.number).append(' ').append(f.phase().name())
                    .append(f.targetLost() ? " lost" : "").append(" →").append(f.targetLabel() == null ? "point" : f.targetLabel().getString());
        }
        Airstrike.LOG.info("SCENARIO mp flights{}", sb);
    }
}
