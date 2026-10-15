package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.world.ColumnScar;

import java.util.Locale;

/**
 * След светового импульса на земле: выжженная трава, гарь, сгоревшие растения и опалённые биомы — ступенями от центра к
 * краю, с рваным краем (кривые поражения с шумом мира), а не кольцом по порогу.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScorchGameTests {
    private ScorchGameTests() {}

    private static final ResourceKey<Biome> SINGED = key("singed"), SCORCHED = key("scorched"), CHARRED = key("charred");
    /**
     * Эпицентр — у западного края площадки, на уровне её земли (блок структуры под шаблоном: дёрн шаблона — y 11, верх
     * — 12): стена барьеров вокруг площадки иначе закрывала бы ближнюю землю от шара.
     */
    private static final BlockPos GROUND_ZERO = new BlockPos(2, 12, 32);
    /** Кольца по расстоянию от эпицентра, блоки: от 0 до 72. */
    private static final int RING = 8, RINGS = 9, FIRST = 0;
    /** Высота проверки биома под землёй: клетка там целиком ниже {@code ScorchedBiomes.BELOW} от верха дёрна. */
    private static final int DEEP = -1;

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Incoming.id(name));
    }

    /**
     * 15 кт на оптимальной высоте в масштабе, где площадка — от эпицентра (свет ~100 кал/см², дёрн содран) до ~3.5 км
     * (~3 кал/см²). Дёрн выгорает у центра и уцелел у края, а между — пятнами (кольцо, где есть и трава, и земля); гарь —
     * только ближе к центру; трава и цветы над землёй сгорели у центра (верхние половины высоких — вместе с нижними,
     * от части остался сухой куст), у края стоят. Биом — ступенями от обугленного к опалённому, моря-реки, пустыня и
     * снежные равнины не тронуты, под землёй — тоже; в данных чанка — новые биомы.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch", skyAccess = true)
    public static void scorchFadesOutwardInPatches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // биомы: ближние ряды — равнина (её свет меняет), дальние полосы — снег, река, пустыня (их — нет)
        fillBiome(h, 0, 39, Biomes.PLAINS);
        fillBiome(h, 40, 47, Biomes.SNOWY_PLAINS);
        fillBiome(h, 48, 55, Biomes.RIVER);
        fillBiome(h, 56, 63, Biomes.DESERT);
        // растения над дёрном: трава, через два — высокая трава в два блока
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                BlockPos p = h.absolutePos(new BlockPos(x, 12, z));
                if ((x + z) % 3 == 0) {
                    level.setBlock(p, Blocks.TALL_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(p.above(), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
                } else {
                    level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        // биом в 12 блоках под землёй (под камнем площадки) — до удара; равнина ли у земли: клетки биома — 4×4 по сетке
        // мира, а площадка стоит как придётся — у границы полос в «равнинном» ряду бывает клетка снега
        int[] deep = new int[64 * 64];
        for (int x = 0; x < 64; x += 4) for (int z = 0; z < 64; z += 4) deep[x * 64 + z] = biomeAt(h, x, DEEP, z).hashCode();
        boolean[] plains = new boolean[64 * 64];
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) plains[x * 64 + z] = biomeAt(h, x, 12, z).is(Biomes.PLAINS);
        double scale = 0.0182;
        Detonation d = NuclearGameTests.detonation(h, GROUND_ZERO, Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        NuclearGameTests.scarAll(h, d, new ColumnScar.Budget(true));

        int[] grass = new int[RINGS], burnt = new int[RINGS], charred = new int[RINGS], plants = new int[RINGS], plantsLeft = new int[RINGS];
        int[] cells = new int[RINGS], levels = new int[RINGS];
        int bushes = 0;
        double[] fluence = new double[RINGS], psi = new double[RINGS];
        int[] coarse = new int[RINGS], seen = new int[RINGS];
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int ring = (int) ((Math.hypot(x - GROUND_ZERO.getX(), z - GROUND_ZERO.getZ()) - FIRST) / RING);
                if (ring < 0 || ring >= RINGS) continue;
                BlockState top = h.getBlockState(new BlockPos(x, 11, z)), above = h.getBlockState(new BlockPos(x, 12, z));
                BlockState upper = h.getBlockState(new BlockPos(x, 13, z));
                if (top.is(Blocks.GRASS_BLOCK)) grass[ring]++;
                else if (top.is(Blocks.PODZOL)) charred[ring]++;
                else if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) burnt[ring]++;
                plants[ring]++;
                if (above.is(Blocks.SHORT_GRASS) || above.is(Blocks.TALL_GRASS)) plantsLeft[ring]++;
                if (above.is(Blocks.DEAD_BUSH)) bushes++;
                // верхняя половина без нижней висела бы в воздухе
                if (upper.is(Blocks.TALL_GRASS) && !above.is(Blocks.TALL_GRASS)) {
                    h.fail("верх высокой травы без низа в " + x + ", " + z + " (внизу " + above + ")");
                }
                net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(new BlockPos(x, 12, z)));
                fluence[ring] = Math.max(fluence[ring], d.fluence(at));
                psi[ring] = Math.max(psi[ring], d.psi(at));
                if (top.is(Blocks.COARSE_DIRT)) coarse[ring]++;
                if (ua.zentix.incoming.nuclear.world.ThermalShadow.visible(level, d.burst(), at.add(0, -0.5, 0))) seen[ring]++;
                // биом — у равнины, без клеток у края площадки (в них и столбцы за стеной барьеров, в тени)
                if (plains[x * 64 + z] && z >= 4 && x >= 4 && x < 60) {
                    cells[ring]++;
                    levels[ring] += level(biomeAt(h, x, 12, z));
                }
            }
        }
        StringBuilder table = new StringBuilder();
        for (int r = 0; r < RINGS; r++) {
            int n = grass[r] + burnt[r] + charred[r];
            table.append(String.format(Locale.ROOT, "%n  %d–%d блоков, свет до %.1f, давление до %.1f, видят шар %d: дёрн %d, земля %d (крупн. %d), гарь %d; растения %d из %d; биом %.2f",
                    FIRST + r * RING, FIRST + (r + 1) * RING, fluence[r], psi[r], seen[r], grass[r], burnt[r], coarse[r], charred[r], plantsLeft[r], plants[r],
                    cells[r] == 0 ? 0 : levels[r] / (double) cells[r]));
            h.assertTrue(n == plants[r], "в кольце " + r + " верх площадки не дёрн и не земля: " + (plants[r] - n) + table);
        }
        String why = table.toString();
        Incoming.LOG.info("След света по кольцам:{}", why);
        double first = 1 - grass[0] / (double) plants[0], last = 1 - grass[RINGS - 1] / (double) plants[RINGS - 1];
        h.assertTrue(first >= 0.8, "у центра дёрн уцелел: " + why);
        h.assertTrue(last <= 0.05, "у края дёрн выгорел: " + why);
        boolean patchy = false;
        double prev = 1;
        for (int r = 0; r < RINGS; r++) {
            double share = 1 - grass[r] / (double) plants[r];
            if (share >= 0.1 && share <= 0.9) patchy = true;
            h.assertTrue(share <= prev + 0.1, "выгоревшего дальше от центра больше, чем ближе (кольцо " + r + "): " + why);
            prev = share;
        }
        h.assertTrue(patchy, "край выжженного — кольцом по порогу, без пятен: " + why);
        h.assertTrue(charred[1] + charred[2] + charred[3] > 0, "у центра нет гари: " + why);
        h.assertTrue(charred[RINGS - 1] + charred[RINGS - 2] + charred[RINGS - 3] == 0, "гарь у края: " + why);
        h.assertTrue(plantsLeft[0] <= plants[0] * 0.1, "трава у центра не сгорела: " + why);
        h.assertTrue(plantsLeft[RINGS - 1] + plantsLeft[RINGS - 2] >= (plants[RINGS - 1] + plants[RINGS - 2]) * 0.75, "трава у края сгорела: " + why);
        h.assertTrue(bushes > 0, "ни одного сухого куста: " + why);
        // биом: обугленный у центра, к краю — ступенями слабее
        double prevLevel = 4;
        for (int r = 0; r < RINGS; r++) {
            if (cells[r] < 16) continue;
            double mean = levels[r] / (double) cells[r];
            h.assertTrue(mean <= prevLevel + 0.3, "биом дальше от центра опалён сильнее (кольцо " + r + "): " + why);
            prevLevel = mean;
        }
        h.assertTrue(levels[0] >= cells[0] * 2.5, "у центра биом не обуглен: " + why);
        h.assertTrue(levels[RINGS - 2] <= cells[RINGS - 2] * 1.5, "у края биом опалён сильно: " + why);
        // полосы, которые свет не перекрашивает, и биомы под землёй; клетки биома у края площадки берут и столбцы за её
        // стеной (площадка стоит в чанках как придётся) — их земля и биом не те, что у площадки, поэтому от края отступ
        for (int x = 4; x < 60; x += 2) {
            assertBiome(h, x, 44, Biomes.SNOWY_PLAINS);
            assertBiome(h, x, 51, Biomes.RIVER);
            assertBiome(h, x, 60, Biomes.DESERT);
        }
        for (int x = 4; x < 60; x += 4) {
            for (int z = 4; z < 60; z += 4) {
                if (biomeAt(h, x, DEEP, z).hashCode() != deep[x * 64 + z]) h.fail("биом под землёй изменился в " + x + ", " + z + ": " + biomeAt(h, x, DEEP, z));
            }
        }
        // в данных чанка у центра — новые биомы (к сохранению чанк руин помечен отметкой руин)
        LevelChunk chunk = level.getChunkAt(h.absolutePos(new BlockPos(2, 11, 32)));
        String saved = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk).toString();
        h.assertTrue(saved.contains(CHARRED.location().toString()), "в данных чанка нет обугленного биома");
        h.succeed();
    }

    /** Биом клеток рядов {@code z0..z1} площадки во всю высоту — как {@code /fillbiome}. */
    private static void fillBiome(GameTestHelper h, int z0, int z1, ResourceKey<Biome> key) {
        ServerLevel level = h.getLevel();
        Holder<Biome> biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
        BlockPos a = h.absolutePos(new BlockPos(0, -16, z0)), b = h.absolutePos(new BlockPos(63, 39, z1));
        int qx0 = QuartPos.fromBlock(Math.min(a.getX(), b.getX())), qx1 = QuartPos.fromBlock(Math.max(a.getX(), b.getX()));
        int qy0 = QuartPos.fromBlock(Math.min(a.getY(), b.getY())), qy1 = QuartPos.fromBlock(Math.max(a.getY(), b.getY()));
        int qz0 = QuartPos.fromBlock(Math.min(a.getZ(), b.getZ())), qz1 = QuartPos.fromBlock(Math.max(a.getZ(), b.getZ()));
        for (int cx = qx0 >> 2; cx <= qx1 >> 2; cx++) {
            for (int cz = qz0 >> 2; cz <= qz1 >> 2; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                chunk.fillBiomesFromNoise((x, y, z, s) -> x >= qx0 && x <= qx1 && y >= qy0 && y <= qy1 && z >= qz0 && z <= qz1 ? biome
                        : chunk.getNoiseBiome(x, y, z), level.getChunkSource().randomState().sampler());
                chunk.setUnsaved(true);
            }
        }
    }

    private static Holder<Biome> biomeAt(GameTestHelper h, int x, int y, int z) {
        BlockPos p = h.absolutePos(new BlockPos(x, y, z));
        return h.getLevel().getNoiseBiome(QuartPos.fromBlock(p.getX()), QuartPos.fromBlock(p.getY()), QuartPos.fromBlock(p.getZ()));
    }

    private static int level(Holder<Biome> b) {
        return b.is(CHARRED) ? 3 : b.is(SCORCHED) ? 2 : b.is(SINGED) ? 1 : 0;
    }

    private static void assertBiome(GameTestHelper h, int x, int z, ResourceKey<Biome> key) {
        Holder<Biome> b = biomeAt(h, x, 12, z);
        if (!b.is(key)) h.fail("биом в " + x + ", " + z + " — " + b.unwrapKey().map(k -> k.location().toString()).orElse("?") + ", а не " + key.location());
    }
}
