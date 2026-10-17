package ua.zentix.incoming.client.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.zentix.incoming.client.map.TerrainTiles;

import java.util.OptionalInt;

/**
 * Высоты рельефа для лучей {@link Sightline} на клиенте: чанки клиента ({@code MOTION_BLOCKING} — с кронами и
 * постройками, другой карты у клиента нет), дальше — готовые плитки рельефа карты наведения ({@link TerrainTiles}:
 * Distant Horizons читается там в фоне заранее). Ничего не грузит и не ждёт.
 */
public final class FarTerrain {
    private FarTerrain() {}

    public static Sightline.Heights of(ClientLevel level) {
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk != null) return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
            OptionalInt tile = TerrainTiles.height(x, z);
            return tile.isPresent() ? tile.getAsInt() : Double.NaN;
        };
    }

    /** Вода ли сверху колонки: 1 — вода, 0 — суша, {@link Double#NaN} — неизвестно. */
    @FunctionalInterface
    public interface Water {
        double at(int x, int z);
    }

    /**
     * Вода сверху колонки на клиенте: в чанках клиента — верхний блок карты высот {@code MOTION_BLOCKING} (вода её держит)
     * с водой, дальше — глубина воды в плитках рельефа карты ({@link TerrainTiles}: чанки клиента, Distant Horizons).
     * Ничего не грузит и не ждёт.
     */
    public static Water water(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, z) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
            if (chunk != null) {
                // карта высот — первый воздух над верхним блоком
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) - 1;
                if (top < chunk.getMinBuildHeight()) return Double.NaN;
                return chunk.getFluidState(pos.set(x, top, z)).is(FluidTags.WATER) ? 1 : 0;
            }
            OptionalInt depth = TerrainTiles.waterDepth(x, z);
            return depth.isPresent() ? (depth.getAsInt() > 0 ? 1 : 0) : Double.NaN;
        };
    }
}
