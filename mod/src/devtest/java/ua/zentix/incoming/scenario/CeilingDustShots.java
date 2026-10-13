package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.fx.CeilingDust;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Пыль с потолка укрытия ({@code CeilingDust}): зритель стоит в углу закрытой комнаты в 17 блоках под поверхностью
 * равнины (свод — в 12 блоках под ней), по поверхности над серединой комнаты бьют крылатая ракета, потом снаряд «Града»
 * (с пусковой в 150 блоках: рассеивание — 1,5 блока). Кадры {@code dust-<оружие>_<что>_<тик>.png}: до удара, в тик взрыва, через
 * 0,5, 1, 2 и 4 с; строки {@code SCENARIO dust} — где взрыв, сила, свод, места, струи и сколько родилось. Половина
 * свода — земля (бурая пыль), половина — гладкий камень (серая). Фронт ракеты толкает зрителя и под землёй
 * ({@code Warheads.push} не смотрит на укрытие): кадры после её удара сняты на ~1,7 блока ближе к углу (глаз кадра —
 * строка {@code SCENARIO dust shot}). Запуск: {@code tools/client_scenario.sh dust}.
 */
final class CeilingDustShots {
    /** Удары по очереди: оружие и команда (x y z — точка на поверхности над комнатой). */
    private static final List<String> STRIKES = List.of("missile", "rocket");
    /** Глубина пола под поверхностью и высота комнаты (воздух), блоков; полуширина. */
    private static final int DEPTH = 17, HEIGHT = 4, HALF = 7;
    /** Взрыва нет за столько тиков после команды — FAIL, следующий удар. */
    private static final int BLAST_WAIT = 2400;
    /** Кадры после взрыва: тики и подписи. */
    private static final int[] AFTER = {1, 10, 20, 40, 80};
    private static final String[] LABEL = {"hit", "0.5s", "1s", "2s", "4s"};

    private int tick = -1;
    private BlockPos centre;
    /** Верх грунта над серединой комнаты (пишет поток сервера). */
    private volatile int surface;
    private final ArrayDeque<String> queue = new ArrayDeque<>(STRIKES);
    private String current;
    /** Тик команды удара (−1 — не ждём) и тик взрыва у клиента. */
    private int firedAt = -1, blastAt = -1;
    /** Первый взрыв на сервере после команды (поток сервера пишет, клиент забирает). */
    private final AtomicReference<Vec3> blast = new AtomicReference<>();
    private volatile boolean armed;

    CeilingDustShots() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onServerBlast);
    }

    private void onServerBlast(ExplosionEvent.Start e) {
        // первый взрыв удара — главный подрыв (у ракеты за ним огненный шар и вторичные)
        if (!e.getLevel().isClientSide() && armed) {
            armed = false;
            blast.set(e.getExplosion().center());
        }
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        tick++;
        MinecraftServer server = mc.getSingleplayerServer();
        if (tick == 40) {
            server("time set 6000");
            server("weather clear");
            mc.options.hideGui = true;
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.missileFlightTime.set(5);
            // ровное место: ближайшая равнина
            server.execute(() -> {
                var found = server.overworld().findClosestBiome3d(b -> b.is(Biomes.PLAINS), BlockPos.ZERO.atY(64), 6400, 32, 64);
                BlockPos at = found == null ? BlockPos.ZERO : found.getFirst();
                server(String.format(Locale.ROOT, "tp %s %d 170 %d", name(), at.getX(), at.getZ()));
                Minecraft.getInstance().execute(() -> centre = at);
            });
            return;
        }
        if (tick == 300 && centre != null) {
            server.execute(() -> build(server));
            return;
        }
        if (tick == 340 && surface != 0) {
            int floor = surface - DEPTH;
            // из угла комнаты наискосок через неё, чуть вверх: в кадре свод над головой и впереди
            server(String.format(Locale.ROOT, "tp %s %.1f %d %.1f facing %.1f %.1f %.1f", name(), centre.getX() - HALF + 1.5, floor + 1,
                    centre.getZ() - HALF + 1.5, centre.getX() + HALF - 1.0, floor + HEIGHT + 1.8, centre.getZ() + HALF - 1.0));
            mc.player.getAbilities().flying = false;
            return;
        }
        // кадры чанков в комнате на llvmpipe строятся долго: первый удар — через 30 с
        if (tick == 940) {
            shot("dust-room");
            next(mc);
            return;
        }
        if (firedAt >= 0 && blastAt < 0) {
            Vec3 at = blast.getAndSet(null);
            if (at != null) {
                blastAt = tick;
                Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
                Incoming.LOG.info("SCENARIO dust {} blast at {} — {} blocks from eye, {} above it, {} ticks after command", current, xyz(at),
                        f1(at.distanceTo(eye)), f1(at.y - eye.y), tick - firedAt);
            } else if (tick - firedAt > BLAST_WAIT) {
                Incoming.LOG.warn("SCENARIO dust {}: no blast in {} ticks — FAIL", current, BLAST_WAIT);
                firedAt = -1;
                next(mc);
                return;
            }
        }
        if (blastAt >= 0) {
            int dt = tick - blastAt;
            for (int i = 0; i < AFTER.length; i++) {
                if (dt == AFTER[i]) shot("dust-" + current + "_" + LABEL[i]);
            }
            if (dt % 10 == 0 && dt <= 160) Incoming.LOG.info("SCENARIO dust {} +{}: {}", current, dt, CeilingDust.describe());
            if (dt == 200) {
                firedAt = blastAt = -1;
                next(mc);
            }
        }
    }

    /** Комната под поверхностью: стены и пол — каменный кирпич, свод — наполовину гладкий камень, наполовину земля; свет в полу. */
    private void build(MinecraftServer server) {
        var level = server.overworld();
        int x = centre.getX(), z = centre.getZ();
        surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int floor = surface - DEPTH, roof = floor + HEIGHT + 1;
        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks hollow", x - HALF - 1, floor, z - HALF - 1,
                x + HALF + 1, roof, z + HALF + 1));
        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone", x - HALF, roof, z - HALF, x, roof, z + HALF));
        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x + 1, roof, z - HALF, x + HALF, roof, z + HALF));
        for (int dx = -HALF + 1; dx <= HALF - 1; dx += 4) {
            for (int dz = -HALF + 1; dz <= HALF - 1; dz += 4) {
                command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:sea_lantern", x + dx, floor, z + dz));
            }
        }
        Incoming.LOG.info("SCENARIO dust room: surface y={} at {} {}, floor y={}, roof y={} ({} blocks of ground over it)", surface, x, z, floor, roof,
                surface - roof);
    }

    /** Следующий удар: кадр «до» и команда от консоли; ударов нет — конец. */
    private void next(Minecraft mc) {
        current = queue.poll();
        if (current == null) {
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
            return;
        }
        shot("dust-" + current + "_before");
        // по поверхности над серединой комнаты: зритель смотрит туда из угла
        int x = centre.getX(), z = centre.getZ();
        String at = String.format(Locale.ROOT, "%.1f %d %.1f", x + 0.5, surface + 1, z + 0.5);
        String c = current.equals("rocket")
                ? String.format(Locale.ROOT, "incoming salvo rocket 1 0 at %s from %d %d", at, x + 150, z)
                : "incoming " + current + " at " + at;
        firedAt = tick;
        armed = true;
        server(c);
    }

    private static void command(MinecraftServer server, String c) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
        Incoming.LOG.info("SCENARIO /{}", c);
    }

    private static void server(String c) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> command(server, c));
    }

    private static String name() {
        return Minecraft.getInstance().player.getGameProfile().getName();
    }

    private void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        // фронт толкает зрителя: откуда снят кадр
        Incoming.LOG.info("SCENARIO dust shot {}: eye {}", name, xyz(mc.gameRenderer.getMainCamera().getPosition()));
        Screenshot.grab(mc.gameDirectory, String.format(Locale.ROOT, "%s_%04d.png", name, tick), mc.getMainRenderTarget(), c -> {});
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
