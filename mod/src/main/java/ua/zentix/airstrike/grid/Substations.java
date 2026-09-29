package ua.zentix.airstrike.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.airstrike.util.Terrain;

/** Блоки подстанций следуют за сетью: выбита — {@code powered=false}, пока в её район не начали возвращать свет. */
final class Substations {
    private Substations() {}

    static void sync(ServerLevel level, PowerGrid grid, long now) {
        for (Node node : grid.nodes()) {
            if (!node.block()) continue;
            BlockPos pos = node.pos();
            // чанк не готов — выровняется, когда станет готов (раз в секунду)
            if (!Terrain.ready(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof SubstationBlock)) continue;
            boolean powered = grid.downOutage(node.id(), now).isEmpty();
            if (state.getValue(SubstationBlock.POWERED) != powered) {
                level.setBlock(pos, state.setValue(SubstationBlock.POWERED, powered), ChunkLights.FLAGS);
            }
        }
    }
}
