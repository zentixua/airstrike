package ua.zentix.airstrike.warhead;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.DebrisEntity;

import java.util.List;

/**
 * Обломки взрыва (debris/* датапака): грунт воронки, обгоревшие куски, горящие головни и детали корпуса.
 * Шахед разбрасывает на 55–75 блоков (до 1.5 блока/тик по горизонтали), ракета — на 90–120 (до 2.1).
 */
public final class DebrisSpawner {
    private static List<BlockState> embers, ash, wreckDrone, wreckMissile, burnStates;

    private DebrisSpawner() {}

    /** Обгоревшие куски — летят раскалёнными. */
    private static List<BlockState> embers() {
        if (embers == null) embers = GroundMaterial.states("blackstone", "basalt", "magma_block", "coal_block", "blackstone", "magma_block");
        return embers;
    }

    /** Пепел (если есть Supplementaries) — просто летит. */
    private static List<BlockState> ash() {
        if (ash == null) ash = GroundMaterial.states("supplementaries:ash", "supplementaries:ash");
        return ash;
    }

    private static List<BlockState> wreckDrone() {
        if (wreckDrone == null) wreckDrone = GroundMaterial.states("gray_concrete", "light_gray_concrete", "create:metal_girder",
                "black_concrete", "iron_trapdoor", "chain", "create:metal_girder", "light_gray_concrete");
        return wreckDrone;
    }

    private static List<BlockState> wreckMissile() {
        if (wreckMissile == null) wreckMissile = GroundMaterial.states("light_gray_concrete", "light_gray_concrete", "light_gray_concrete",
                "create:industrial_iron_block", "create:metal_girder", "create:metal_girder", "create:metal_girder", "iron_bars", "iron_bars",
                "iron_trapdoor", "iron_trapdoor", "gray_concrete", "chain", "black_concrete");
        return wreckMissile;
    }

    private static List<BlockState> burning() {
        if (burnStates == null) {
            BlockState campfire = Blocks.CAMPFIRE.defaultBlockState();
            if (campfire.hasProperty(BlockStateProperties.LIT)) campfire = campfire.setValue(BlockStateProperties.LIT, true);
            burnStates = List.of(campfire, Blocks.FIRE.defaultBlockState(), Blocks.FIRE.defaultBlockState());
        }
        return burnStates;
    }

    /** Обломки шахеда; возвращает, сколько их. */
    public static int drone(ServerLevel level, Vec3 at, GroundMaterial mat) {
        return spawn(level, at, mat.debris(), false, 1.5, 0.5, 1.6)
                + spawn(level, at, mat.debris(), false, 1.5, 0.5, 1.6)
                + spawnCharred(level, at, 1.5, 0.5, 1.6)
                + spawn(level, at, wreckDrone(), false, 1.5, 0.5, 1.6)
                + spawnBurning(level, at, 1.5, 0.5, 1.6);
    }

    /** Обломки ракеты; возвращает, сколько их. */
    public static int missile(ServerLevel level, Vec3 at, GroundMaterial mat) {
        int n = 0;
        for (int i = 0; i < 4; i++) n += spawn(level, at, mat.debris(), false, 2.1, 0.6, 2.2);
        return n + spawnCharred(level, at, 2.1, 0.6, 2.2)
                + spawnCharred(level, at, 2.1, 0.6, 2.2)
                + spawn(level, at, wreckMissile(), false, 2.1, 0.6, 2.2)
                + spawnBurning(level, at, 2.1, 0.6, 2.2)
                + spawnBurning(level, at, 2.1, 0.6, 2.2);
    }

    /** Выброс газов из скважины бомбы: грунт и угли вверх почти отвесно. Возвращает, сколько обломков. */
    public static int vent(ServerLevel level, Vec3 at, GroundMaterial mat) {
        return spawn(level, at, mat.debris(), false, 0.45, 1.2, 2.7)
                + spawn(level, at, mat.debris(), false, 0.45, 1.2, 2.7)
                + spawnCharred(level, at, 0.45, 1.2, 2.7);
    }

    private static int spawn(ServerLevel level, Vec3 at, List<BlockState> states, boolean hot, double horizontal, double upMin, double upMax) {
        for (BlockState s : states) {
            level.addFreshEntity(DebrisEntity.create(level, start(level.random, at), s, hot, true, velocity(level.random, horizontal, upMin, upMax)));
        }
        return states.size();
    }

    private static int spawnCharred(ServerLevel level, Vec3 at, double horizontal, double upMin, double upMax) {
        return spawn(level, at, embers(), true, horizontal, upMin, upMax) + spawn(level, at, ash(), false, horizontal, upMin, upMax);
    }

    private static int spawnBurning(ServerLevel level, Vec3 at, double horizontal, double upMin, double upMax) {
        for (BlockState s : burning()) {
            level.addFreshEntity(DebrisEntity.create(level, start(level.random, at), s, true, false, velocity(level.random, horizontal, upMin, upMax)));
        }
        return burning().size();
    }

    private static Vec3 start(RandomSource r, Vec3 at) {
        return at.add((r.nextDouble() - 0.5) * 2.4, 1.5 + r.nextDouble() * 1.5, (r.nextDouble() - 0.5) * 2.4);
    }

    private static Vec3 velocity(RandomSource r, double horizontal, double upMin, double upMax) {
        return new Vec3((r.nextDouble() * 2 - 1) * horizontal, upMin + r.nextDouble() * (upMax - upMin), (r.nextDouble() * 2 - 1) * horizontal);
    }
}
