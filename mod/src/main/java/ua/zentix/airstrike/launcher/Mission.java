package ua.zentix.airstrike.launcher;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.target.Target;

/**
 * Задача стационарной пусковой ({@link FixedLauncherBlockEntity}): чем, сколько, с каким разбросом, по какому месту
 * и через какие точки маршрута. Цель — только место ({@link Target.Point} или место с карты {@link Target.Ground}):
 * пусковая стреляет по сигналу, когда угодно позже, и за движущейся целью не следит — сущность, аппарат и замеченная
 * цель становятся местом, где они были при постановке задачи.
 *
 * @param point место удара (у {@link Target.Ground} — с высотой на момент постановки; пуск находит её заново)
 */
public record Mission(WeaponType weapon, int count, int spread, Target target, Vec3 point, Waypoints via) {
    public static final Codec<Mission> CODEC = RecordCodecBuilder.create(i -> i.group(
            WeaponType.CODEC.fieldOf("weapon").forGetter(Mission::weapon),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(Mission::count),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("spread").forGetter(Mission::spread),
            Target.CODEC.fieldOf("target").forGetter(Mission::target),
            Vec3.CODEC.fieldOf("point").forGetter(Mission::point),
            Waypoints.CODEC.optionalFieldOf("via", Waypoints.NONE).forGetter(Mission::via)
    ).apply(i, Mission::new));

    public Mission {
        if (!(target instanceof Target.Point) && !(target instanceof Target.Ground)) target = new Target.Point(point);
        if (!weapon.spec().route().waypoints()) via = Waypoints.NONE;
    }

    /**
     * Оружие, которое берёт стационарная пусковая: то, что стартует с пакета пусковой (паспорт, {@link WeaponSpec#rack}) —
     * шахед, «Ланцет», крылатая ракета, «Град». B-2 заходит издалека, у МБР своя площадка.
     */
    public static boolean accepts(WeaponType weapon) {
        return weapon.spec().rack() != null;
    }

    /** Куда смотрит пакет с этой задачей: на первую точку маршрута, без точек — на цель. */
    public Vec3 heading() {
        return via.isEmpty() ? point : via.points().getFirst();
    }
}
