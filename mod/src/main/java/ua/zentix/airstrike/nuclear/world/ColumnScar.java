package ua.zentix.airstrike.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.ThermalModel;
import ua.zentix.airstrike.registry.ModBlocks;

/**
 * Повреждения одного столбца (x, z) от одного подрыва (DESIGN-nuke §3.2–3.4). Надземное (всё выше природного
 * грунта: постройки, деревья, растения) ломается по давлению; грунт волна не трогает — подвалы и бункеры
 * уцелеют; верхний слой меняется только от света, если видит огненный шар. Операции идемпотентны:
 * разрушенное уже воздух, обгоревшее уже обгорело — столбец можно пройти повторно.
 */
public final class ColumnScar {
    /** Без каскада обновлений соседей и без выпадения предметов; клиенты получают изменения пачками по секциям. */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Сколько блоков вниз от верха столбца ищем природный грунт. */
    private static final int MAX_DEPTH = 96;

    private ColumnScar() {}

    /** Счётчики на подрыв: пожаров не больше заданного. */
    public static final class Budget {
        int fires;
    }

    public static void apply(ServerLevel level, Detonation d, int x, int z, Budget budget, RandomSource random) {
        int top = Terrain.height(level, Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = Math.max(level.getMinBuildHeight(), top - MAX_DEPTH);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean blockDamage = AirstrikeConfig.SERVER.nukeBlockDamage.get();
        int ground = Integer.MIN_VALUE;
        for (int y = top; y >= bottom; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            BlockResponse r = BlockResponse.of(s);
            if (r.kind() == BlockResponse.Kind.GROUND) {
                ground = y;
                break;
            }
            if (!blockDamage || r.kind() == BlockResponse.Kind.NONE) continue;
            double psi = d.psi(Vec3.atCenterOf(m));
            if (r.kind() == BlockResponse.Kind.LOG && AirstrikeConfig.SERVER.nukeTreeFall.get() && psi >= r.thresholdPsi()) {
                // столбец идёт сверху: ищем комель — если ствол стоит на грунте, валится всё дерево целиком
                int base = y;
                while (base - 1 > bottom && level.getBlockState(m.setY(base - 1)).is(BlockTags.LOGS)) base--;
                if (BlockResponse.of(level.getBlockState(m.setY(base - 1))).kind() == BlockResponse.Kind.GROUND) {
                    fellTree(level, d, new BlockPos(x, base, z), s);
                    ground = base - 1;
                    break;
                }
                m.setY(y);
            }
            if (!r.breaksAt(psi, Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z))))) continue;
            level.setBlock(m, Blocks.AIR.defaultBlockState(), FLAGS);
            if (r.kind() == BlockResponse.Kind.LEAVES && psi < 3 && lit(level, d, m) >= 10) ignite(level, m, budget, random, 1.0);
        }
        if (ground != Integer.MIN_VALUE) {
            // скоростной напор сдирает дёрн и траву: от 8 psi — голая земля, даже в тени
            if (blockDamage && d.psi(Vec3.atCenterOf(m.set(x, ground, z))) >= STRIP_PSI) strip(level, m, random);
            scorch(level, d, m.set(x, ground, z), budget, random);
        }
    }

    /** С какого давления волна сдирает дёрн. */
    private static final double STRIP_PSI = 8;

    private static void strip(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState s = level.getBlockState(pos);
        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.MOSS_BLOCK)) {
            level.setBlock(pos, (random.nextInt(3) == 0 ? Blocks.DIRT : Blocks.COARSE_DIRT).defaultBlockState(), FLAGS);
        }
    }

    /** Световой импульс на верхнем блоке грунта: пожары, выгоревшая трава, растаявший снег, тринитит у шара. */
    private static void scorch(ServerLevel level, Detonation d, BlockPos.MutableBlockPos top, Budget budget, RandomSource random) {
        double q = lit(level, d, top);
        if (q < ThermalModel.IGNITE) return;
        BlockState s = level.getBlockState(top);
        BlockPos above = top.above();
        Vec3 c = Vec3.atCenterOf(top);
        double horizontal = Math.hypot(c.x - d.burst().x, c.z - d.burst().z);
        if (d.surface() && horizontal < d.fireballRadius() * 0.8 && c.y > d.groundY() - d.fireballRadius()) {
            // внутри огненного шара у земли: песок сплавляется, вода вскипает
            if (s.is(BlockTags.SAND)) level.setBlock(top, ModBlocks.TRINITITE.get().defaultBlockState(), FLAGS);
            for (int i = 0; i < 3 && level.getBlockState(above.above(i)).getFluidState().isSource(); i++) {
                level.setBlock(above.above(i), Blocks.AIR.defaultBlockState(), FLAGS);
            }
            return;
        }
        if (q >= 15) {
            if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)) {
                level.setBlock(top, (random.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState(), FLAGS);
            }
            if (s.is(Blocks.SNOW_BLOCK)) level.setBlock(top, Blocks.WATER.defaultBlockState(), FLAGS);
        }
        if (!AirstrikeConfig.SERVER.nukeFires.get()) return;
        // волна гасит часть огня там, где давление ≥ 2 psi
        double chance = Math.min(1, q / 20) * (d.psi(c) >= 2 ? 0.5 : 1);
        if (level.getBlockState(above).isAir() && (s.isFlammable(level, top, net.minecraft.core.Direction.UP) || s.is(Blocks.GRASS_BLOCK) || s.is(BlockTags.DIRT))) {
            ignite(level, above.mutable(), budget, random, chance);
        }
    }

    private static void ignite(ServerLevel level, BlockPos.MutableBlockPos pos, Budget budget, RandomSource random, double chance) {
        if (!AirstrikeConfig.SERVER.nukeFires.get() || budget.fires >= AirstrikeConfig.SERVER.nukeMaxFires.get() || random.nextDouble() >= chance) return;
        // огонь проверяет и будит соседей: на краю загруженного мира это загрузило бы соседний чанк
        if (!NuclearTickets.aroundLoaded(level, pos)) return;
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (fire.canSurvive(level, pos)) {
            level.setBlock(pos, fire, Block.UPDATE_ALL);
            budget.fires++;
        }
    }

    /** Световой импульс в точке с учётом тени от рельефа и построек, кал/см². */
    static double lit(Level level, Detonation d, BlockPos pos) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return ThermalShadow.visible(level, d.burst(), p) ? d.fluence(p) : 0;
    }

    /**
     * Дерево валится стволом от эпицентра (радиальный вывал, как в Тунгуске и Хиросиме): ствол убираем,
     * на землю кладём горизонтальные брёвна по направлению от эпицентра.
     */
    private static void fellTree(ServerLevel level, Detonation d, BlockPos base, BlockState log) {
        int height = 0;
        BlockPos.MutableBlockPos m = base.mutable();
        while (height < 24 && level.getBlockState(m).is(BlockTags.LOGS)) {
            level.setBlock(m, Blocks.AIR.defaultBlockState(), FLAGS);
            m.move(Direction.UP);
            height++;
        }
        double dx = base.getX() - d.burst().x, dz = base.getZ() - d.burst().z;
        Direction dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockState lying = log.hasProperty(RotatedPillarBlock.AXIS) ? log.setValue(BlockStateProperties.AXIS, dir.getAxis()) : log;
        for (int i = 0; i < height; i++) {
            BlockPos p = base.relative(dir, i);
            if (!NuclearTickets.aroundLoaded(level, p)) break; // ствол не тянет за собой загрузку соседнего чанка
            BlockPos at = new BlockPos(p.getX(), Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
            if (!level.getBlockState(at).canBeReplaced()) break;
            level.setBlock(at, lying, FLAGS);
        }
    }
}
