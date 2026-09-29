package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.CraterModel;
import ua.zentix.airstrike.registry.ModBlocks;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Воронка наземного подрыва (DESIGN-nuke §1.7, §3.5): параболоид с неровным краем, вал выброса до двух радиусов,
 * на дне и стенках — тринитит. Глубина и вал отсчитываются от природного грунта в каждом столбце: на склоне
 * воронка идёт по склону, а не срезает холм до высоты эпицентра.
 * <p>
 * Идёт по чанкам от центра наружу, по столбцу за шаг под общим бюджетом времени. Чанк догружается в фоне
 * тикетом ({@link NuclearTickets}) — текущий и несколько следующих, — пока он не готов, работа ждёт.
 * Номер чанка сохраняется в мире, после перезапуска воронка дороется с того же места (операции повторяемы).
 */
public final class CraterJob {
    /** Сколько чанков вперёд просить загрузить. */
    private static final int LOOKAHEAD = 4;
    /** Сколько блоков вниз искать природный грунт под постройками. */
    private static final int MAX_DEPTH = 96;

    private final Detonation d;
    private final BlockPos gz;
    private final int outer;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private int next;
    private int column;
    @Nullable
    private CraterModel.Soil soil;
    private List<BlockState> rim = List.of();

    /** @param startChunk с какого чанка продолжить (0 — новая воронка) */
    public CraterJob(Detonation d, int startChunk) {
        this.d = d;
        this.gz = BlockPos.containing(d.burst().x, d.groundY(), d.burst().z);
        // наибольший грунт — влажный (×1.3): вал доходит до двух радиусов
        this.outer = Mth.ceil(d.blocks(CraterModel.radius(d.yieldKt(), CraterModel.Soil.WET)) * 2) + 1;
        ChunkPos c = new ChunkPos(gz);
        int rc = (outer >> 4) + 1;
        for (int cx = c.x - rc; cx <= c.x + rc; cx++) {
            for (int cz = c.z - rc; cz <= c.z + rc; cz++) {
                ChunkPos p = new ChunkPos(cx, cz);
                if (nearest(p) <= outer) chunks.add(p);
            }
        }
        chunks.sort(Comparator.comparingDouble(this::nearest));
        this.next = Mth.clamp(startChunk, 0, chunks.size());
    }

    public Detonation detonation() {
        return d;
    }

    /** Сколько чанков уже вырыто (для сохранения). */
    public int progress() {
        return next;
    }

    /** Грунт под эпицентром, по которому считается профиль (null — чанк эпицентра ещё не загружен). */
    @Nullable
    public CraterModel.Soil soil() {
        return soil;
    }

    private double nearest(ChunkPos p) {
        double x = Mth.clamp(gz.getX(), p.getMinBlockX(), p.getMaxBlockX()) - gz.getX();
        double z = Mth.clamp(gz.getZ(), p.getMinBlockZ(), p.getMaxBlockZ()) - gz.getZ();
        return Math.sqrt(x * x + z * z);
    }

    public enum Step { PROGRESS, WAIT, DONE }

    /** Один столбец. WAIT — нужный чанк ещё грузится, продолжим в следующем тике. */
    public Step step(ServerLevel level, RandomSource random) {
        if (next >= chunks.size()) {
            release(level);
            return Step.DONE;
        }
        if (soil == null && !sampleSoil(level)) return Step.WAIT;
        for (int i = next; i < Math.min(chunks.size(), next + LOOKAHEAD); i++) NuclearTickets.hold(level, chunks.get(i), true);
        ChunkPos cp = chunks.get(next);
        if (!NuclearTickets.neighbourhoodLoaded(level, cp)) return Step.WAIT;
        dig(level, cp.getMinBlockX() + (column & 15), cp.getMinBlockZ() + (column >> 4), random);
        if (++column >= 256) {
            column = 0;
            NuclearTickets.hold(level, cp, false);
            next++;
        }
        return Step.PROGRESS;
    }

    /** Грунт эпицентра: от него радиус и глубина воронки и из чего вал. */
    private boolean sampleSoil(ServerLevel level) {
        ChunkPos c = new ChunkPos(gz);
        NuclearTickets.hold(level, c, true);
        if (!Terrain.ready(level, c.x, c.z)) return false;
        GroundMaterial mat = GroundMaterial.sample(level, gz);
        soil = switch (mat) {
            case STONE, DEEPSLATE, BRICK -> CraterModel.Soil.ROCK;
            case SAND, WATER, SNOW -> CraterModel.Soil.WET;
            default -> CraterModel.Soil.DRY;
        };
        rim = mat.debris();
        // продолжение после перезапуска: чанк эпицентра уже вырыт, держать его незачем
        if (next > 0) NuclearTickets.hold(level, c, false);
        return true;
    }

    private void dig(ServerLevel level, int x, int z, RandomSource random) {
        double dx = x + 0.5 - d.burst().x, dz = z + 0.5 - d.burst().z;
        if (dx * dx + dz * dz > (double) outer * outer) return;
        // неровный край: радиус «дышит» по углу
        double wobble = 1 + 0.08 * Math.sin(Math.atan2(dz, dx) * 7 + d.seed() % 13);
        double r = d.metres(Math.hypot(dx, dz)) / wobble;
        double depth = d.blocks(CraterModel.profileDepth(r, d.yieldKt(), soil));
        double lift = d.blocks(CraterModel.rimHeight(r, d.yieldKt(), soil));
        if (depth <= 0 && lift < 1) return;

        // высоты — как у карты высот: первый воздух над блоком
        int top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int ground = naturalSurface(level, x, z, top);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        // итоговая поверхность = природный грунт + вал − чаша (у кромки они перекрываются)
        double relief = lift - depth;
        if (relief < 0) {
            // в чаше испарилось всё: и постройки над грунтом, и грунт до профиля; под водой яма заполняется водой
            int floor = Mth.floor(ground + relief);
            BlockState fill = level.getBlockState(m.setY(ground)).getFluidState().isSource()
                    ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            for (int y = top - 1; y >= floor; y--) {
                BlockState s = level.getBlockState(m.setY(y));
                if (y >= ground && !s.getFluidState().isEmpty()) continue; // сама вода над дном остаётся
                if (!s.isAir() && s.getBlock().defaultDestroyTime() >= 0) ColumnScar.replace(level, m, s, y < ground ? fill : Blocks.AIR.defaultBlockState());
            }
            BlockState bottom = level.getBlockState(m.setY(floor - 1));
            if (depth > lift && random.nextFloat() < 0.3f && !bottom.isAir() && bottom.getFluidState().isEmpty() && bottom.getBlock().defaultDestroyTime() >= 0) {
                level.setBlock(m, ModBlocks.TRINITITE.get().defaultBlockState(), ColumnScar.FLAGS);
            }
        } else if (relief >= 1 && !rim.isEmpty()) {
            // вал: выброшенный грунт ложится поверх природного
            int crest = Mth.floor(ground + relief);
            for (int y = ground; y < crest; y++) {
                if (level.getBlockState(m.setY(y)).canBeReplaced()) level.setBlock(m, rim.get(random.nextInt(rim.size())), ColumnScar.FLAGS);
            }
        }
    }

    /** Первый воздух над природным грунтом (ниже построек, деревьев и прочего надземного). */
    private static int naturalSurface(ServerLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = top - 1; y >= Math.max(level.getMinBuildHeight(), top - MAX_DEPTH); y--) {
            if (BlockResponse.of(level.getBlockState(m.setY(y))).kind() == BlockResponse.Kind.GROUND) return y + 1;
        }
        return top;
    }

    public void release(ServerLevel level) {
        if (soil == null) NuclearTickets.hold(level, new ChunkPos(gz), false);
        for (int i = next; i < Math.min(chunks.size(), next + LOOKAHEAD); i++) NuclearTickets.hold(level, chunks.get(i), false);
    }
}
