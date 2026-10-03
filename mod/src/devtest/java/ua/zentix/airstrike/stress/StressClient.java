package ua.zentix.airstrike.stress;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Игрок стенда нагрузки (только devtest, свойство {@code airstrike.stress.client}): заходит на сервер
 * ({@code --quickPlayMultiplayer}), летает по кругу (движущаяся цель), время от времени смотрит камерой снаряда
 * и перенацеливает его ЛКМ, пускает с пульта (пакет, как экран пульта и бинокль). Роль {@code leaver} выходит
 * посреди удара, когда велит режиссёр ({@link StressDirector#LEAVE}), и возвращается. Раз в 5 с пишет {@code STRESSC}: fps, снаряды на клиенте и в HUD, ошибки.
 * Когда сервер останавливается, клиент выходит.
 */
@Mod(value = Airstrike.MOD_ID, dist = Dist.CLIENT)
public final class StressClient {
    private static final String ROLE = System.getProperty("airstrike.stress.client");
    private static final String SERVER = System.getProperty("airstrike.stress.server", "127.0.0.1");

    private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
    private int warnings, errors;
    /** Тики с момента входа в мир (идут и когда клиент вне сервера). */
    private int tick = -1;
    private int away = -1;
    private int disconnectedFor;
    private int joins;
    /** Режиссёр велел выйти: выход — в следующем тике клиента, не из обработчика пакета. */
    private volatile boolean leave;

    public StressClient(IEventBus modBus) {
        if (ROLE == null) return;
        LogWatch.install(problems, () -> warnings++, () -> errors++);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onChat);
    }

    private void onChat(ClientChatReceivedEvent.System e) {
        if (!StressDirector.LEAVE.equals(e.getMessage().getString())) return;
        e.setCanceled(true);
        if ("leaver".equals(ROLE)) leave = true;
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (tick < 0 && p == null) return;
        if (tick < 0) log("в мире, роль %s", ROLE);
        tick++;
        if (p == null) {
            offline(mc);
            return;
        }
        if (disconnectedFor > 0) {
            joins++;
            log("снова в мире (заходов %d)", joins);
            disconnectedFor = 0;
        }
        fly(p);
        if ("host".equals(ROLE)) host(mc);
        if (tick % 600 == 300) {
            ProjectileCamera.cycle();
            log("камера: active=%b viewing=%b", ProjectileCamera.isActive(), ProjectileCamera.isViewing());
        }
        if (tick % 600 == 380 && ProjectileCamera.isViewing()) {
            // ЛКМ в камере снаряда: перенацелить на то, что под перекрестием
            KeyMapping.click(mc.options.keyAttack.getKey());
            log("ЛКМ в камере");
        }
        if (tick % 600 == 480 && ProjectileCamera.isActive()) ProjectileCamera.exit();
        if (leave) {
            leave = false;
            log("выхожу посреди удара");
            away = tick;
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            return;
        }
        if (tick % 100 == 0) stat(mc);
    }

    private void offline(Minecraft mc) {
        disconnectedFor++;
        if (away >= 0 && tick - away >= 300) {
            away = -1;
            log("захожу обратно");
            ServerData data = new ServerData("stress", SERVER, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(SERVER), data, false, null);
            return;
        }
        // выгнали не по сценарию — сервер закончил (или упал): выходим
        if (away < 0 && mc.screen instanceof DisconnectedScreen && disconnectedFor > 40) {
            log("сервер закрыл соединение, выхожу; warn %d err %d", warnings, errors);
            for (String s : problems) log("problem: %s", s);
            mc.stop();
        }
    }

    /** Летать по кругу радиусом ~40 блоков на высоте, где застал сервер: цель движется. */
    private void fly(LocalPlayer p) {
        if (!p.mayFly()) return;
        if (!p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }
        if ("host".equals(ROLE)) return; // хозяин стоит у пусковых
        Minecraft.getInstance().options.keyUp.setDown(!ProjectileCamera.isActive());
        if (!ProjectileCamera.isActive()) p.setYRot(p.getYRot() + 2f);
    }

    /** Хозяин пускает и с пульта: залп РСЗО по игроку (экран пульта) и ракеты по точке (бинокль). */
    private void host(Minecraft mc) {
        if (tick == 2600) {
            Loadout l = new Loadout(WeaponType.ROCKET, 30, 150, TargetMode.PLAYER, "Friend2", Loadout.Nuke.DEFAULT);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.empty(), Optional.empty(), Waypoints.NONE));
            log("пульт: РСЗО 30 по Friend2");
        }
        if (tick == 2800) {
            Vec3 at = mc.player.position().add(0, -mc.player.getY() + 70, 300);
            Loadout l = new Loadout(WeaponType.MISSILE, 10, 60, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.of(new C2S.AimHint(C2S.AimHint.POINT, at, 0, Vec3.ZERO)), Optional.empty(), Waypoints.NONE));
            log("бинокль: ракеты 10 по точке");
        }
        if (tick == 4400) {
            Loadout l = new Loadout(WeaponType.LOITER, 30, 150, TargetMode.PLAYER, "Friend1", Loadout.Nuke.DEFAULT);
            PacketDistributor.sendToServer(new C2S.Fire(l, Optional.empty(), Optional.empty(), Waypoints.NONE));
            log("пульт: барраж 30 по Friend1");
        }
    }

    private void stat(Minecraft mc) {
        int entities = 0;
        for (var en : mc.level.entitiesForRendering()) {
            if (en instanceof StrikeProjectile) entities++;
        }
        Runtime rt = Runtime.getRuntime();
        log("stat t=%d fps %d | снарядов у клиента %d, в HUD %d | камера %b | частиц %s | heap %d МБ | warn %d err %d", tick, mc.getFps(), entities,
                ClientFlights.all().size(), ProjectileCamera.isActive(),
                mc.particleEngine.countParticles() + " + эффектов " + ua.zentix.airstrike.client.fx.particle.FxPool.INSTANCE.count(),
                (rt.totalMemory() - rt.freeMemory()) >> 20, warnings, errors);
    }

    private static void log(String fmt, Object... args) {
        Airstrike.LOG.info("STRESSC " + String.format(Locale.ROOT, fmt, args));
    }
}
