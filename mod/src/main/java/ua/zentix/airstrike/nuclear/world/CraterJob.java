package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.CraterModel;
import ua.zentix.airstrike.registry.ModBlocks;
import ua.zentix.airstrike.strike.ChunkTickets;
import ua.zentix.airstrike.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Воронка наземного подрыва (DESIGN-nuke §1.7, §3.5): параболоид с неровным краем, вал выброса до двух радиусов,
 * на дне и стенках — тринитит. Столбцы идут от центра наружу под общим бюджетом времени; чанки воронки держатся
 * тикетом, пока она не вырыта (единственное место, где ядерный удар грузит чанки).
 */
public final class CraterJob {
    private final Detonation d;
    private final CraterModel.Soil soil;
    private final double radius;
    private final List<int[]> columns = new ArrayList<>();
    private final List<BlockState> rim;
    private final UUID ticketOwner;
    private final List<ChunkPos> held = new ArrayList<>();
    private int next;

    public CraterJob(ServerLevel level, Detonation d) {
        this.d = d;
        BlockPos gz = BlockPos.containing(d.burst().x, d.groundY(), d.burst().z);
        GroundMaterial mat = GroundMaterial.sample(level, gz);
        this.soil = switch (mat) {
            case STONE, DEEPSLATE, BRICK -> CraterModel.Soil.ROCK;
            case SAND, WATER, SNOW -> CraterModel.Soil.WET;
            default -> CraterModel.Soil.DRY;
        };
        this.rim = mat.debris();
        this.radius = d.blocks(CraterModel.radius(d.yieldKt(), soil));
        int outer = Mth.ceil(radius * 2);
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                if (dx * dx + dz * dz <= outer * outer) columns.add(new int[]{gz.getX() + dx, gz.getZ() + dz});
            }
        }
        columns.sort((a, b) -> Double.compare(dist2(a, gz), dist2(b, gz)));
        this.ticketOwner = UUID.nameUUIDFromBytes(("airstrike-crater-" + d.id() + "-" + d.gameTime()).getBytes());
        for (int cx = (gz.getX() - outer) >> 4; cx <= (gz.getX() + outer) >> 4; cx++) {
            for (int cz = (gz.getZ() - outer) >> 4; cz <= (gz.getZ() + outer) >> 4; cz++) {
                ChunkTickets.CONTROLLER.forceChunk(level, ticketOwner, cx, cz, true, false);
                held.add(new ChunkPos(cx, cz));
            }
        }
    }

    private static double dist2(int[] c, BlockPos gz) {
        double dx = c[0] - gz.getX(), dz = c[1] - gz.getZ();
        return dx * dx + dz * dz;
    }

    public enum Step { PROGRESS, WAIT, DONE }

    /** Один столбец. WAIT — чанк по тикету ещё грузится, продолжим в следующем тике. */
    public Step step(ServerLevel level, RandomSource random) {
        if (next >= columns.size()) {
            release(level);
            return Step.DONE;
        }
        int[] c = columns.get(next);
        if (!level.hasChunk(c[0] >> 4, c[1] >> 4)) return Step.WAIT;
        next++;
        double r = d.metres(Math.hypot(c[0] + 0.5 - d.burst().x, c[1] + 0.5 - d.burst().z));
        // неровный край: радиус «дышит» по углу
        double wobble = 1 + 0.08 * Math.sin(Math.atan2(c[1] - d.burst().z, c[0] - d.burst().x) * 7 + d.seed() % 13);
        r /= wobble;
        double relief = d.blocks(CraterModel.rimHeight(r, d.yieldKt(), soil) - CraterModel.profileDepth(r, d.yieldKt(), soil));
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0], c[1]);
        int target = Mth.floor(d.groundY() + relief);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(c[0], 0, c[1]);
        if (target < surface) {
            for (int y = surface; y > target; y--) {
                BlockState s = level.getBlockState(m.setY(y));
                if (s.getBlock().defaultDestroyTime() >= 0) level.setBlock(m, Blocks.AIR.defaultBlockState(), ColumnScar.FLAGS);
            }
            // дно и стенки частично стекловидные
            if (d.metres(Math.hypot(c[0] - d.burst().x, c[1] - d.burst().z)) < CraterModel.radius(d.yieldKt(), soil) && random.nextFloat() < 0.3f) {
                BlockState floor = level.getBlockState(m.setY(target));
                if (!floor.isAir() && floor.getFluidState().isEmpty()) level.setBlock(m, ModBlocks.TRINITITE.get().defaultBlockState(), ColumnScar.FLAGS);
            }
        } else if (target > surface && !rim.isEmpty()) {
            for (int y = surface; y < target; y++) {
                if (level.getBlockState(m.setY(y)).canBeReplaced()) level.setBlock(m, rim.get(random.nextInt(rim.size())), ColumnScar.FLAGS);
            }
        }
        return Step.PROGRESS;
    }

    public void release(ServerLevel level) {
        for (ChunkPos p : held) ChunkTickets.CONTROLLER.forceChunk(level, ticketOwner, p.x, p.z, false, false);
        held.clear();
    }
}
