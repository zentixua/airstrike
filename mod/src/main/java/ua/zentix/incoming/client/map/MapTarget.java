package ua.zentix.incoming.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.strike.Waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Место, выбранное на карте наведения, маршрут к нему — точки, которые оператор поставил сам ({@link Waypoints}), — и
 * место пуска, если его выбрал оператор или хост (иначе пуск у стреляющего): помнятся, пока игрок в том же измерении и
 * не вышел из мира, поэтому повторный удар идёт тем же маршрутом и с того же места. Только x и z: высоту земли там
 * находит сервер ({@code Target.Ground.at}, пусковую на месте пуска ставит {@code LaunchSite}); верх по карте
 * ({@link TerrainTiles#height}) уходит с приказом только оценкой.
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
    /** Место пуска; null — пуск у стреляющего. */
    @Nullable
    private static Place launch;

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

    /** Место пуска в этом измерении; пусто — пуск у стреляющего. */
    public static Optional<Place> launch(@Nullable ClientLevel level) {
        return here(level) ? Optional.ofNullable(launch) : Optional.empty();
    }

    /** Место пуска для приказа (x и z). */
    public static Optional<Vec3> launchPoint(@Nullable ClientLevel level) {
        return launch(level).map(p -> new Vec3(p.x(), 0, p.z()));
    }

    /** Пускать отсюда (выбрать или перенести место пуска). */
    public static void setLaunch(ClientLevel level, Place place) {
        bind(level);
        launch = place;
    }

    /** Убрать место пуска: дальше — пуск у стреляющего. */
    public static void clearLaunch() {
        launch = null;
    }

    public static void reset() {
        dimension = null;
        point = null;
        route.clear();
        launch = null;
    }

    private static boolean here(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(dimension);
    }

    /** Место, точки и место пуска другого измерения — чужие координаты: выбор в этом начинается заново. */
    private static void bind(ClientLevel level) {
        if (here(level)) return;
        reset();
        dimension = level.dimension();
    }
}
