package ua.zentix.airstrike.client.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.zentix.airstrike.client.map.TerrainTiles;

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
}
