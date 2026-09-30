package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Место, выбранное на карте наведения: помнится, пока игрок в том же измерении и не вышел из мира. Только x и z:
 * высоту земли там находит сервер ({@code Target.Ground.at}); верх по карте ({@link TerrainTiles#height}) уходит
 * с приказом только оценкой.
 */
public final class MapTarget {
    public record Place(double x, double z) {}

    @Nullable
    private static ResourceKey<Level> dimension;
    @Nullable
    private static Place point;

    private MapTarget() {}

    public static void set(ClientLevel level, Place point) {
        dimension = level.dimension();
        MapTarget.point = point;
    }

    public static Optional<Place> get(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(dimension) ? Optional.ofNullable(point) : Optional.empty();
    }

    public static void reset() {
        dimension = null;
        point = null;
    }
}
