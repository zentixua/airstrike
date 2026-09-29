package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Место, выбранное на карте наведения: помнится, пока игрок в том же измерении и не вышел из мира. */
public final class MapTarget {
    @Nullable
    private static ResourceKey<Level> dimension;
    @Nullable
    private static Vec3 point;

    private MapTarget() {}

    /** @param point x и z места; высота — оценка по рельефу карты (сервер возьмёт поверхность) */
    public static void set(ClientLevel level, Vec3 point) {
        dimension = level.dimension();
        MapTarget.point = point;
    }

    public static Optional<Vec3> get(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(dimension) ? Optional.ofNullable(point) : Optional.empty();
    }

    public static void reset() {
        dimension = null;
        point = null;
    }
}
