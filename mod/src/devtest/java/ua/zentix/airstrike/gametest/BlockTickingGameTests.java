package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.util.BlockTicking;
import ua.zentix.airstrike.util.Terrain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Блок-сущности тикают только в чанке, у которого готовы все соседи ({@link BlockTicking}), а на аппаратах Sable —
 * как раньше.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockTickingGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);
    private static final TicketType<UUID> PLAIN = TicketType.create("airstrike_test_block_ticking", Comparator.<UUID>naturalOrder());

    /**
     * Чанк, который уже загружен целиком (уровень 33, сущности загружены), получает тикет региона радиуса 1: по уровню
     * он сразу тикает блоками (32), а соседи только начинают догружаться до полной загрузки (33). Так было на стенде
     * (облако 29.09.2026): игрок сместился на чанк, хранилище испытаний у нового края тикало и грузило соседа синхронно,
     * сервер стоял 13 с (на {@code main} — 31 с). Пока сосед не готов, блок-сущности чанка не тикают
     * ({@code LevelChunk.isTicking} — false), а с готовыми соседями тикают. Проверка засчитывается, только если такие
     * тики были; без миксина ваниль здесь пускает блок-сущности.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "block_ticking", skyAccess = true)
    public static void blockEntitiesWaitForNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // миксин встал: вызов LevelChunk.isTicking проходит через условие
        long checks = BlockTicking.checks();
        isTicking(level.getChunkAt(h.absolutePos(RANGE_CENTER)));
        h.assertTrue(BlockTicking.checks() > checks, "миксин LevelChunkTickingMixin не встал");
        // чанк грузится в фоне: срок — игровой
        StrikeGameTests.gameSpeed(h);
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos c = new ChunkPos(base.x + 45, base.z - 55);
        UUID id = UUID.randomUUID();
        h.assertFalse(Terrain.ready(level, c.x, c.z), "чанк не свежий");
        // радиус 0 — уровень 33: чанк загружен целиком, но не тикает, соседи — ниже полной загрузки
        level.getChunkSource().addRegionTicket(PLAIN, c, 0, id);
        StrikeGameTests.afterTest(h, () -> {
            level.getChunkSource().removeRegionTicket(PLAIN, c, 0, id);
            level.getChunkSource().removeRegionTicket(PLAIN, c, 1, id);
        });
        int[] tick = {0}, raisedAt = {-1}, exposed = {0};
        h.onEachTick(() -> {
            tick[0]++;
            LevelChunk chunk = level.getChunkSource().getChunkNow(c.x, c.z);
            if (raisedAt[0] < 0) {
                if (chunk == null || !level.areEntitiesLoaded(c.toLong())) return;
                level.getChunkSource().addRegionTicket(PLAIN, c, 1, id);
                raisedAt[0] = tick[0];
                return;
            }
            if (chunk == null || !level.shouldTickBlocksAt(c.toLong())) return;
            boolean blockEntities = isTicking(chunk);
            if (!neighboursReady(level, c.x, c.z)) {
                exposed[0]++;
                if (blockEntities) {
                    throw new GameTestAssertException("тик " + tick[0] + ": блок-сущности тикают, а сосед чанка не готов");
                }
                return;
            }
            if (!blockEntities) {
                if (tick[0] - raisedAt[0] > 600) {
                    throw new GameTestAssertException("соседи готовы, а блок-сущности не тикают " + (tick[0] - raisedAt[0]) + " тиков");
                }
                return;
            }
            Airstrike.LOG.info("Блок-сущности у неготовых соседей: чанк загружен на тике {}, тиков с неготовым соседом {}, тикают с тика {}",
                    raisedAt[0], exposed[0], tick[0]);
            h.assertTrue(exposed[0] > 0, "соседи были готовы сразу — проверка ничего не проверила");
            h.succeed();
        });
    }

    /**
     * Печь на аппарате Sable горит и после сборки: держатель чанка плота ({@code PlotChunkHolder}) всегда «тикающий», и
     * условие {@link BlockTicking} его не задерживает — машины Create на аппаратах тикают, как раньше.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "block_ticking_craft", skyAccess = true)
    public static void craftBlockEntitiesTick(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        BlockPos floor = RANGE_CENTER.above();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) h.setBlock(floor.offset(dx, 0, dz), Blocks.OAK_PLANKS.defaultBlockState());
        }
        BlockPos furnacePos = floor.above();
        h.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState());
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) h.getBlockEntity(furnacePos);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64));
        furnace.setItem(1, new ItemStack(Items.COAL, 8));
        BlockPos origin = h.absolutePos(furnacePos);
        int[] phase = {0}, waited = {0}, litAtFind = {-1};
        BlockPos[] moved = {null};
        h.onEachTick(() -> {
            switch (phase[0]) {
                case 0 -> {
                    // печь разгорелась в мире — теперь собираем аппарат
                    if (litTime(furnace) <= 0) return;
                    BlockPos a = h.absolutePos(floor.offset(-1, 0, -1)), b = h.absolutePos(furnacePos.offset(1, 0, 1));
                    String cmd = String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
                    level.getServer().getCommands().performPrefixedCommand(
                            level.getServer().createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput(), cmd);
                    phase[0] = 1;
                }
                case 1 -> {
                    // Sable может достроить аппарат не в том же тике
                    AbstractFurnaceBlockEntity onCraft = craftFurnace(level, origin);
                    if (onCraft == null) {
                        if (++waited[0] > 40) throw new GameTestAssertException("печь аппарата не найдена среди тикающих блок-сущностей");
                        return;
                    }
                    moved[0] = onCraft.getBlockPos();
                    litAtFind[0] = litTime(onCraft);
                    waited[0] = 0;
                    phase[0] = 2;
                }
                default -> {
                    if (++waited[0] < 40) return;
                    AbstractFurnaceBlockEntity onCraft = (AbstractFurnaceBlockEntity) level.getBlockEntity(moved[0]);
                    int lit = onCraft == null ? -1 : litTime(onCraft);
                    ChunkPos plot = new ChunkPos(moved[0]);
                    ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(plot.toLong());
                    Airstrike.LOG.info("Печь аппарата в {} (держатель чанка в ChunkMap: {}): горение {} → {} за 40 тиков", moved[0],
                            holder == null ? null : holder.getClass().getName(), litAtFind[0], lit);
                    h.assertTrue(onCraft != null && lit < litAtFind[0], "печь на аппарате не тикает: горение " + litAtFind[0] + " → " + lit);
                    // путь, которым чанк плота проходит условие: держатель Sable в ChunkMap, «тикающий» всегда
                    h.assertTrue(holder != null && holder.getClass().getSimpleName().equals("PlotChunkHolder") && holder.getTickingChunk() != null,
                            "держатель чанка плота: " + (holder == null ? null : holder.getClass().getName()));
                    h.succeed();
                }
            }
        });
    }

    /**
     * Печь теста, которая тикает не на своём месте в мире: после сборки её блок-сущность живёт в плоте аппарата. Своя —
     * по сырому железу во входе (другие тесты таких печей не ставят).
     */
    private static AbstractFurnaceBlockEntity craftFurnace(ServerLevel level, BlockPos origin) {
        for (TickingBlockEntity t : tickers(level)) {
            if (t.isRemoved() || t.getPos().equals(origin)) continue;
            BlockEntity be = level.getBlockEntity(t.getPos());
            if (be instanceof AbstractFurnaceBlockEntity f && f.getItem(0).is(Items.RAW_IRON)) return f;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<TickingBlockEntity> tickers(ServerLevel level) {
        List<TickingBlockEntity> all = new ArrayList<>();
        try {
            for (String name : new String[] {"blockEntityTickers", "pendingBlockEntityTickers"}) {
                Field f = Level.class.getDeclaredField(name);
                f.setAccessible(true);
                all.addAll((List<TickingBlockEntity>) f.get(level));
            }
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("тикающие блок-сущности недоступны: " + e);
        }
        return all;
    }

    private static int litTime(AbstractFurnaceBlockEntity furnace) {
        try {
            Field f = AbstractFurnaceBlockEntity.class.getDeclaredField("litTime");
            f.setAccessible(true);
            return f.getInt(furnace);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("горение печи недоступно: " + e);
        }
    }

    /** {@code LevelChunk.isTicking} — тикают ли блок-сущности в чанке (с миксином — и по готовности соседей). */
    private static boolean isTicking(LevelChunk chunk) {
        try {
            Method m = LevelChunk.class.getDeclaredMethod("isTicking", BlockPos.class);
            m.setAccessible(true);
            return (boolean) m.invoke(chunk, chunk.getPos().getMiddleBlockPosition(64));
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("LevelChunk.isTicking недоступен: " + e);
        }
    }

    private static boolean neighboursReady(ServerLevel level, int x, int z) {
        for (int nx = -1; nx <= 1; nx++) {
            for (int nz = -1; nz <= 1; nz++) if (!Terrain.ready(level, x + nx, z + nz)) return false;
        }
        return true;
    }
}
