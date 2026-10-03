package ua.zentix.airstrike.strike;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.guidance.Route;

import java.util.List;

/**
 * Маршрут оператора: точки, которые он поставил на карте наведения, по порядку пролёта и только по горизонтали (высоту
 * держит сам снаряд). Пусто — путь строит пуск ({@link StrikeService}): петля в обход и заход из-за спины стреляющего.
 * По точкам летит оружие, у которого в паспорте есть дальность маршрута ({@link WeaponSpec.Route#reach}): шахед,
 * крылатая ракета и «Ланцет». Точек не больше {@link #MAX}; что они годятся (конечные числа, в дальности карты, путь
 * в дальности оружия), проверяет сервер ({@code ServerActions}).
 */
public record Waypoints(List<Vec3> points) {
    /** Больше точек на маршруте не бывает. */
    public static final int MAX = 5;
    public static final Waypoints NONE = new Waypoints(List.of());

    /** Точка маршрута в пакете: x и z. */
    private static final StreamCodec<ByteBuf, Vec3> POINT = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::z, (x, z) -> new Vec3(x, 0, z));
    /** Больше {@link #MAX} точек пакет не читает: такой пакет — ошибка разбора, а не маршрут. */
    public static final StreamCodec<ByteBuf, Waypoints> STREAM_CODEC = POINT.apply(ByteBufCodecs.list(MAX)).map(Waypoints::new, Waypoints::points);
    public static final Codec<Waypoints> CODEC = Vec3.CODEC.listOf().xmap(Waypoints::new, Waypoints::points);

    public Waypoints {
        points = points.stream().limit(MAX).map(p -> new Vec3(p.x, 0, p.z)).toList();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    /** Все координаты — конечные числа: NaN проходит любые сравнения дальности, бесконечность ломает чанки. */
    public boolean finite() {
        return points.stream().allMatch(p -> Double.isFinite(p.x) && Double.isFinite(p.z));
    }

    /** Путь от {@code from} через точки до {@code to} по горизонтали, блоков. */
    public double length(Vec3 from, Vec3 to) {
        return route(from).remaining(from, to);
    }

    /** Оружие пролетит этот маршрут от {@code from} до {@code to}: летает по точкам и путь не длиннее его дальности. */
    public boolean within(WeaponType weapon, Vec3 from, Vec3 to) {
        WeaponSpec.Route spec = weapon.spec().route();
        return spec.waypoints() && length(from, to) <= spec.reach();
    }

    /** Маршрут полёта по точкам; первый участок начинается в {@code origin} (точка пуска). */
    public Route route(Vec3 origin) {
        return Route.operator(points, origin);
    }

    /**
     * Откуда заходит снаряд издалека (без пусковой у стреляющего): за {@code lead} блоков до первой точки, на продолжении
     * первого участка — к ней он подходит прямо, по курсу маршрута. Только по горизонтали; высоту ставит пуск.
     *
     * @param aim точка цели: после последней точки маршрута снаряд идёт на неё
     */
    public Vec3 afar(Vec3 aim, double lead) {
        Vec3 first = points.getFirst();
        Vec3 next = points.size() > 1 ? points.get(1) : new Vec3(aim.x, 0, aim.z);
        Vec3 along = next.subtract(first);
        // первая точка над самой целью: курс первого участка не задан — заход с севера
        Vec3 dir = along.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : along.normalize();
        return first.subtract(dir.scale(lead));
    }
}
