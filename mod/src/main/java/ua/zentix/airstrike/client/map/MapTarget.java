package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.strike.Waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Место, выбранное на карте наведения, и маршрут к нему — точки, которые оператор поставил сам ({@link Waypoints}):
 * помнятся, пока игрок в том же измерении и не вышел из мира, поэтому повторный удар идёт тем же маршрутом. Только
 * x и z: высоту земли там находит сервер ({@code Target.Ground.at}); верх по карте ({@link TerrainTiles#height}) уходит
 * с приказом только оценкой.
 */
public final class MapTarget {
    public record Place(double x, double z) {}

    /** Измерение, где выбрано место и поставлены точки; в другом их нет. */
    @Nullable
    private static ResourceKey<Level> dimension;
    @Nullable
    private static Place point;
    /** Точки маршрута по порядку пролёта, не больше {@link Waypoints#MAX}. */
    private static final List<Place> route = new ArrayList<>();

    private MapTarget() {}

    public static void set(ClientLevel level, Place point) {
        bind(level);
        MapTarget.point = point;
    }

    public static Optional<Place> get(@Nullable ClientLevel level) {
        return here(level) ? Optional.ofNullable(point) : Optional.empty();
    }

    /** Точки маршрута в этом измерении (копия). */
    public static List<Place> route(@Nullable ClientLevel level) {
        return here(level) ? List.copyOf(route) : List.of();
    }

    /** Маршрут для приказа. */
    public static Waypoints waypoints(@Nullable ClientLevel level) {
        return new Waypoints(route(level).stream().map(p -> new Vec3(p.x(), 0, p.z())).toList());
    }

    /** Добавить точку в конец маршрута (перед целью); больше {@link Waypoints#MAX} — нет. */
    public static boolean add(ClientLevel level, Place place) {
        bind(level);
        if (route.size() >= Waypoints.MAX) return false;
        route.add(place);
        return true;
    }

    /** Перенести точку {@code index} маршрута. */
    public static void move(ClientLevel level, int index, Place place) {
        if (here(level) && index >= 0 && index < route.size()) route.set(index, place);
    }

    /** Убрать точку {@code index} маршрута. */
    public static void remove(ClientLevel level, int index) {
        if (here(level) && index >= 0 && index < route.size()) route.remove(index);
    }

    /** Убрать все точки маршрута: дальше — путь, который строит пуск. */
    public static void clearRoute() {
        route.clear();
    }

    public static void reset() {
        dimension = null;
        point = null;
        route.clear();
    }

    private static boolean here(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(dimension);
    }

    /** Место и точки другого измерения — чужие координаты: выбор в этом начинается заново. */
    private static void bind(ClientLevel level) {
        if (here(level)) return;
        reset();
        dimension = level.dimension();
    }
}
