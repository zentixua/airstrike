package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.scenario.trailer.Aircraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Помощники трейлера, которым нужен сервер: истребитель-аппарат Sable на пути по сценарию. */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TrailerGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);
    private static final TicketType<ChunkPos> PATH = TicketType.create("airstrike_test_path", Comparator.comparingLong(ChunkPos::toLong));
    /** Путь: 400 блоков на восток по 10 блоков за тик (как в трейлере, 200 м/с), крен покачивается ±25°. */
    private static final double LENGTH = 400, SPEED = 10;

    private TrailerGameTests() {}

    /**
     * Истребитель из блоков собирается в аппарат Sable и летит по прямой 400 блоков со скоростью 10 блоков за тик:
     * физика Sable между тиками не уводит его от поставленной точки (не дальше 0.05 блока), нос смотрит по курсу,
     * крен — какой задан, блоки аппарата на месте, срезы сопел (откуда клиент рисует факел) — позади, на корме.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "trailer_aircraft", skyAccess = true)
    public static void aircraftFliesScriptedPath(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        // выше барьерной стены площадки (40 блоков): аппарат ни обо что не задевает
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 40, 0);
        Vec3 dir = new Vec3(1, 0, 0);
        float yaw = -90; // курс на восток (+X)
        // чанки пути должны тикать блоки, иначе Sable уберёт аппарат в выгруженные
        List<ChunkPos> path = new ArrayList<>();
        for (double d = -16; d <= LENGTH + 32; d += 16) path.add(new ChunkPos(BlockPos.containing(start.add(dir.scale(d)))));
        for (ChunkPos c : path) level.getChunkSource().addRegionTicket(PATH, c, 3, c);
        Aircraft[] plane = new Aircraft[1];
        int[] tick = {-1};
        double[] worst = new double[1];
        String[] failure = new String[1];
        h.onEachTick(() -> {
            if (failure[0] != null || plane[0] == null || tick[0] < 0) return;
            Aircraft a = plane[0];
            int t = tick[0];
            if (!a.alive()) {
                failure[0] = "аппарат пропал на тике " + t;
                return;
            }
            if (t > 0) {
                // после шага физики аппарат там, куда его поставили в прошлом тике (гравитация роняет на 0.014)
                Vec3 off = a.position().subtract(start.add(dir.scale((t - 1) * SPEED)));
                if (t <= 2 || t % 20 == 0) Airstrike.LOG.info(String.format(Locale.ROOT, "TRAILER aircraft t=%d отклонение %s", t, off));
                worst[0] = Math.max(worst[0], off.length());
                if (a.nose().dot(dir) < 0.999) failure[0] = "физика развернула аппарат на тике " + t + ": нос " + a.nose();
            }
            if ((t - 1) * SPEED >= LENGTH) {
                tick[0] = -2; // долетел
                return;
            }
            float roll = (float) (25 * Math.sin(t / 8.0));
            a.fly(start.add(dir.scale(t * SPEED)), yaw, 0, roll);
            Vec3 nose = a.nose();
            Vec3 wing = a.rightWing();
            double wantDown = Math.sin(Math.toRadians(roll));
            if (nose.dot(dir) < 0.999 || Math.abs(-wing.y - wantDown) > 0.01 || wing.z < 0.9 * Math.cos(Math.toRadians(roll))) {
                failure[0] = String.format(Locale.ROOT, "поворот не тот на тике %d: нос %s, правое крыло %s, крен %.1f", t, nose, wing, roll);
            }
            tick[0]++;
        });
        h.startSequence()
                .thenWaitUntil(() -> {
                    for (ChunkPos c : path) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                h.assertTrue(level.shouldTickBlocksAt(ChunkPos.asLong(c.x + dx, c.z + dz)), "чанк пути не готов: " + c);
                            }
                        }
                    }
                })
                .thenExecute(() -> {
                    plane[0] = Aircraft.build(level, BlockPos.containing(start), yaw);
                    h.assertTrue(plane[0].alive(), "аппарат не собрался");
                    h.assertTrue(level.getBlockState(BlockPos.containing(start)).isAir(), "блоки остались в мире после сборки");
                    plane[0].fly(start, yaw, 0, 0);
                    tick[0] = 0;
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(failure[0] == null, String.valueOf(failure[0]));
                    h.assertTrue(tick[0] == -2, "летит: тик " + tick[0]);
                })
                .thenExecute(() -> {
                    Aircraft a = plane[0];
                    Vec3 end = start.add(dir.scale(Math.ceil(LENGTH / SPEED) * SPEED));
                    double flown = a.position().subtract(start).dot(dir);
                    int left = blocksInPlot(level, a);
                    Airstrike.LOG.info(String.format(Locale.ROOT,
                            "TRAILER aircraft: пролетел %.1f блока, конец %s (ждали %s), наибольшее отклонение %.3f, блоков %d из %d",
                            flown, a.position(), end, worst[0], left, a.builtBlocks()));
                    h.assertTrue(flown >= LENGTH, "пролетел только " + flown);
                    h.assertTrue(worst[0] < 0.05, "физика увела аппарат с пути на " + worst[0]);
                    h.assertTrue(left == a.builtBlocks(), "в аппарате блоков " + left + " из " + a.builtBlocks());
                    for (Vec3 n : a.nozzles()) {
                        double behind = a.subLevel().logicalPose().transformPosition(n).subtract(a.position()).dot(dir);
                        h.assertTrue(behind < -3 && behind > -10, "сопло не на корме: " + behind + " блока от центра масс");
                    }
                    a.remove();
                    for (ChunkPos c : path) level.getChunkSource().removeRegionTicket(PATH, c, 3, c);
                })
                .thenSucceed();
    }

    /** Непустые блоки в плоте аппарата вокруг его центра масс. */
    private static int blocksInPlot(ServerLevel level, Aircraft a) {
        BlockPos center = BlockPos.containing(a.subLevel().logicalPose().transformPositionInverse(a.position()));
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-12, -12, -12), center.offset(12, 12, 12))) {
            if (!level.getBlockState(p).isAir()) n++;
        }
        return n;
    }
}
