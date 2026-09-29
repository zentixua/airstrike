package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * Рельеф из чанков, которые есть у клиента (дальность прорисовки): как ванильная карта — верхний блок по карте
 * высот {@code MOTION_BLOCKING} (другие клиенту не приходят) и глубина воды. Мир клиента читается только в потоке игры.
 */
final class LoadedChunksTerrain implements TerrainSource {
    /** Глубже этого вода на карте одинаково тёмная. */
    private static final int MAX_WATER_DEPTH = 16;
    /** Сколько блоков вниз искать блок с цветом под прозрачными (стекло, свет). */
    private static final int MAX_COLORLESS = 8;

    @Override
    public boolean offThread() {
        return false;
    }

    @Override
    public Reader open(ClientLevel level) {
        return new Reader() {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            @Nullable
            @Override
            public Column column(int x, int z) {
                if (!level.hasChunk(x >> 4, z >> 4)) return null;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int bottom = Math.max(level.getMinBuildHeight(), top - MAX_COLORLESS);
                for (int y = top - 1; y >= bottom; y--) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    MapColor color = state.getMapColor(level, pos);
                    if (color == MapColor.NONE) continue;
                    return new Column(y + 1, color, state.getFluidState().is(FluidTags.WATER) ? waterDepth(level, x, y, z) : 0);
                }
                return null;
            }

            private int waterDepth(ClientLevel level, int x, int surface, int z) {
                int depth = 1;
                while (depth < MAX_WATER_DEPTH && level.getFluidState(pos.set(x, surface - depth, z)).is(FluidTags.WATER)) depth++;
                return depth;
            }

            @Override
            public void close() {}
        };
    }
}
