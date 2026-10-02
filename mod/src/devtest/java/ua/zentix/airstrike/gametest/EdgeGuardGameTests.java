package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.GroundMaterial;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.Comparator;
import java.util.List;

/**
 * Взрыв у края загрузки не грузит чанки синхронно: грунт и поверхность точки удара, кольцо огня ракеты, выбитые стёкла
 * и бревна стволов, поваленных ядерным взрывом, читают и меняют только готовые чанки. Проверка — на свежем чанке без
 * тикета: чтение неготового чанка на сервере грузит его прямо в вызове, и чанк после вызова оказался бы готов.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EdgeGuardGameTests {
    private static final TicketType<ChunkPos> HOLD = TicketType.create("airstrike_test_edge_guard", Comparator.comparingLong(ChunkPos::toLong));

    /** Свежий чанк далеко за площадкой: его никто не грузил. */
    private static ChunkPos fresh(GameTestHelper h, int dx, int dz) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos c = new ChunkPos(base.x + dx, base.z + dz);
        h.assertFalse(Terrain.ready(h.getLevel(), c.x, c.z), "чанк " + c + " уже загружен — проверка ничего не проверит");
        return c;
    }

    private static Vec3 middle(ChunkPos c) {
        return new Vec3(c.getMiddleBlockX() + 0.5, 90, c.getMiddleBlockZ() + 0.5);
    }

    /** Кольцо огня ракеты: столб в неготовом чанке не поджигается и не грузится. */
    @GameTest(template = "range", batch = "edge_guard_fire")
    public static void igniteGroundAtUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.assertTrue(AirstrikeConfig.SERVER.fire.get(), "огонь выключен в настройке — кольцо огня не проверить");
        ChunkPos ring = fresh(h, 45, -55);
        Warheads.igniteGround(level, middle(ring).x, middle(ring).z);
        h.assertFalse(Terrain.ready(level, ring.x, ring.z), "поджог земли загрузил чанк " + ring);
        h.succeed();
    }

    /** Грунт точки удара в неготовом чанке — гравий, чанк не грузится. */
    @GameTest(template = "range", batch = "edge_guard_ground")
    public static void groundSampleAtUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos ground = fresh(h, -55, 45);
        GroundMaterial sampled = GroundMaterial.sample(level, BlockPos.containing(middle(ground)));
        h.assertFalse(Terrain.ready(level, ground.x, ground.z), "грунт точки удара загрузил чанк " + ground);
        h.assertTrue(sampled == GroundMaterial.GRAVEL, "грунт неготового чанка " + sampled + ", ждём гравий");
        h.succeed();
    }

    /**
     * Сам подрыв: грунт в конструкторе, ванильный взрыв — отложен до загрузки района в фоне. Потом тест догружает район
     * сам и ждёт, пока отложенные взрывы и таймлайн подрыва закончатся: иначе они грузили бы чанки в чужих тестах.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "edge_guard_blast")
    public static void blastAtUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos blast = fresh(h, 55, 55);
        detonateAndFinish(h, blast);
    }

    /**
     * Подрыв у чанка, который уже грузится (тикет есть, полной загрузки ещё нет): чтение блока из него ждало бы
     * загрузки в {@code managedBlock} прямо в тике.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "edge_guard_loading")
    public static void blastAtLoadingChunkWaitsForNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos blast = fresh(h, -60, -40);
        // уровень 33 без тика: загрузка чанка запущена (держатель с тикетом), getChunkNow — null, пока не загрузится
        level.getChunkSource().addRegionTicket(HOLD, blast, 0, blast);
        level.getChunkSource().chunkMap.getDistanceManager().runAllUpdates(level.getChunkSource().chunkMap);
        try {
            h.assertFalse(Terrain.ready(level, blast.x, blast.z), "чанк " + blast + " уже загружен — проверка ничего не проверит");
            detonateAndFinish(h, blast);
        } finally {
            level.getChunkSource().removeRegionTicket(HOLD, blast, 0, blast);
        }
    }

    private static void detonateAndFinish(GameTestHelper h, ChunkPos blast) {
        ServerLevel level = h.getLevel();
        Vec3 at = middle(blast);
        Warheads.detonate(level, WeaponType.DRONE, at, null, null);
        h.assertFalse(Terrain.ready(level, blast.x, blast.z), "подрыв загрузил чанк точки удара " + blast);
        // вторичные подрывы наземного взрыва — до 22 блоков от точки (SurfaceBlast): с запасом
        double area = 32;
        h.runAfterDelay(1, () -> {
            for (int cx = Mth.floor(at.x - area) >> 4; cx <= Mth.floor(at.x + area) >> 4; cx++)
                for (int cz = Mth.floor(at.z - area) >> 4; cz <= Mth.floor(at.z + area) >> 4; cz++) level.getChunk(cx, cz);
        });
        // таймлайн подрыва — 12 тиков, отложенные взрывы срабатывают в первом тике готового района
        h.runAfterDelay(40, h::succeed);
    }

    /**
     * Стекло у неготового соседнего чанка остаётся: {@code setBlock} обновил бы форму соседа и загрузил его. Стекло
     * внутри чанка выбивается.
     */
    @GameTest(template = "range", batch = "edge_guard_glass")
    public static void shatterKeepsGlassNextToUnreadyChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = fresh(h, -45, -65);
        ChunkPos east = new ChunkPos(c.x + 1, c.z);
        // уровень 33: чанк загружен целиком, соседи — ниже полной загрузки
        level.getChunkSource().addRegionTicket(HOLD, c, 0, c);
        try {
            LevelChunk chunk = level.getChunk(c.x, c.z);
            h.assertFalse(Terrain.ready(level, east.x, east.z), "сосед " + east + " готов — проверка ничего не проверит");
            // выше рельефа: природное стекло в объёме проверки не попадёт в счёт
            int y = level.getMaxBuildHeight() - 8;
            BlockPos edge = new BlockPos(c.getMaxBlockX(), y, c.getMiddleBlockZ());
            BlockPos inner = new BlockPos(c.getMiddleBlockX(), y, c.getMiddleBlockZ());
            // прямо в секцию: setBlock сам обновил бы соседа
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            section.setBlockState(edge.getX() & 15, y & 15, edge.getZ() & 15, Blocks.GLASS.defaultBlockState());
            section.setBlockState(inner.getX() & 15, y & 15, inner.getZ() & 15, Blocks.GLASS.defaultBlockState());
            // две соединённые панели в 2 и 1 блоке от соседа: выбитая дальняя меняет форму ближней, та читает соседа
            BlockPos pane = edge.offset(-1, 0, 2), paneNear = edge.offset(0, 0, 2);
            section.setBlockState(pane.getX() & 15, y & 15, pane.getZ() & 15, Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.EAST, true));
            section.setBlockState(paneNear.getX() & 15, y & 15, paneNear.getZ() & 15, Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.WEST, true));
            // панель, дверь, доски в 3, 2 и 1 блоке от соседа: выбитая панель будит дверь, дверь проверяет сигнал
            // редстоуна, а доски проводят его — читаются их соседи, то есть соседний чанк
            BlockPos signal = edge.offset(-2, 0, -2), door = edge.offset(-1, 0, -2), planks = edge.offset(0, 0, -2);
            section.setBlockState(signal.getX() & 15, y & 15, signal.getZ() & 15, Blocks.GLASS_PANE.defaultBlockState());
            section.setBlockState(door.getX() & 15, y & 15, door.getZ() & 15, Blocks.OAK_DOOR.defaultBlockState());
            section.setBlockState(door.getX() & 15, (y + 1) & 15, door.getZ() & 15, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            section.setBlockState(planks.getX() & 15, y & 15, planks.getZ() & 15, Blocks.OAK_PLANKS.defaultBlockState());

            int broken = Warheads.shatter(level, Vec3.atCenterOf(inner), 10, 2, 2, ModTags.SHATTERS);

            h.assertFalse(Terrain.ready(level, east.x, east.z), "выбитое стекло у края загрузило соседний чанк " + east);
            h.assertTrue(section.getBlockState(edge.getX() & 15, y & 15, edge.getZ() & 15).is(Blocks.GLASS), "стекло у неготового соседа выбито");
            h.assertTrue(section.getBlockState(pane.getX() & 15, y & 15, pane.getZ() & 15).is(Blocks.GLASS_PANE), "панель в 2 блоках от неготового соседа выбита");
            h.assertTrue(section.getBlockState(signal.getX() & 15, y & 15, signal.getZ() & 15).isAir(), "панель у двери не выбита");
            h.assertTrue(section.getBlockState(inner.getX() & 15, y & 15, inner.getZ() & 15).isAir(), "стекло внутри чанка не выбито");
            h.assertTrue(broken == 2, "выбито " + broken + " (ждём 2: стекло внутри и панель у двери)");
        } finally {
            level.getChunkSource().removeRegionTicket(HOLD, c, 0, c);
        }
        h.succeed();
    }

    /**
     * Бревно поваленного ствола во втором блоке от неготового соседа не ставится: на смену блока Sable читает соседей
     * места, у твёрдых — и их соседей, и загрузил бы соседа прямо в вызове. Бревно в середине чанка ставится.
     */
    @GameTest(template = "range", batch = "edge_guard_log")
    public static void fallenLogNextToUnreadyChunkLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = fresh(h, 65, -45);
        ChunkPos east = new ChunkPos(c.x + 1, c.z);
        // уровень 33: чанк загружен целиком, соседи — ниже полной загрузки
        level.getChunkSource().addRegionTicket(HOLD, c, 0, c);
        try {
            LevelChunk chunk = level.getChunk(c.x, c.z);
            h.assertFalse(Terrain.ready(level, east.x, east.z), "сосед " + east + " готов — проверка ничего не проверит");
            // камень выше рельефа — опора бревна с воздухом над ней и, у края, стенка рядом с бревном: соседей твёрдого
            // блока Sable читает, воздуха — нет. Прямо в секцию и карту высот: setBlock у края сам прочитал бы соседа
            int y = level.getMaxBuildHeight() - 8;
            BlockPos edge = new BlockPos(c.getMaxBlockX() - 1, y, c.getMiddleBlockZ());
            BlockPos inner = new BlockPos(c.getMiddleBlockX(), y, c.getMiddleBlockZ());
            for (BlockPos p : List.of(edge, edge.offset(1, 1, 0), inner)) {
                chunk.getSection(chunk.getSectionIndex(p.getY())).setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, Blocks.STONE.defaultBlockState());
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES).update(p.getX() & 15, p.getY(), p.getZ() & 15, Blocks.STONE.defaultBlockState());
            }
            BlockState log = Blocks.OAK_LOG.defaultBlockState();
            RuinPlan.placeLog(level, edge.asLong(), log);
            h.assertFalse(Terrain.ready(level, east.x, east.z), "бревно во втором блоке от края загрузило соседний чанк " + east);
            h.assertTrue(chunk.getBlockState(edge.above()).isAir(), "бревно у неготового соседа поставлено");
            RuinPlan.placeLog(level, inner.asLong(), log);
            h.assertTrue(chunk.getBlockState(inner.above()).is(Blocks.OAK_LOG), "бревно в середине чанка не поставлено");
        } finally {
            level.getChunkSource().removeRegionTicket(HOLD, c, 0, c);
        }
        h.succeed();
    }
}
