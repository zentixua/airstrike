package ua.zentix.airstrike.strike;

import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.guidance.Route;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Маршрут оператора: длина, дальность оружия, заход издалека, разбор пакета и сохранения. */
class WaypointsTest {
    private static final Vec3 FROM = new Vec3(0, 70, 0);

    /** Путь — по горизонтали от пуска через точки к цели; высота точек не хранится (её держит снаряд). */
    @Test
    void lengthGoesThroughPoints() {
        Waypoints via = new Waypoints(List.of(new Vec3(300, 90, 400), new Vec3(300, 0, 1400)));
        assertEquals(500 + 1000 + 600, via.length(FROM, new Vec3(900, 64, 1400)), 1e-9);
        assertEquals(0, via.points().getFirst().y);
        assertEquals(new Vec3(900, 0, 1400).length(), Waypoints.NONE.length(FROM, new Vec3(900, 64, 1400)), 1e-9);
    }

    /** По точкам летают шахед, ракета и «Ланцет» и только в своей дальности; баллистика, РСЗО и МБР — нет. */
    @Test
    void withinIsWeaponReach() {
        for (WeaponType w : WeaponType.values()) {
            double reach = w.spec().route().reach();
            Waypoints via = new Waypoints(List.of(new Vec3(0, 0, 1000)));
            boolean flies = w == WeaponType.DRONE || w == WeaponType.MISSILE || w == WeaponType.LOITER;
            assertEquals(flies, w.spec().route().waypoints(), w.getSerializedName());
            assertEquals(flies, via.within(w, FROM, new Vec3(0, 0, Math.max(1000, reach - 1))), w.getSerializedName());
            assertFalse(via.within(w, FROM, new Vec3(0, 0, reach + 1)), w.getSerializedName());
        }
    }

    /**
     * Издалека снаряд заходит к первой точке по курсу первого участка; первая точка над целью (курса нет) — с севера.
     * Маршрут от места старта начинается первой точкой оператора, без лишних.
     */
    @Test
    void afarLiesBeforeFirstPoint() {
        Waypoints via = new Waypoints(List.of(new Vec3(1000, 0, 0), new Vec3(1000, 0, 500)));
        assertEquals(new Vec3(1000, 0, -320), via.afar(new Vec3(0, 0, 0), 320));
        Waypoints last = new Waypoints(List.of(new Vec3(1000, 0, 0)));
        assertEquals(new Vec3(1400, 0, 0), last.afar(new Vec3(0, 64, 0), 400));
        Waypoints onAim = new Waypoints(List.of(new Vec3(50, 0, 50)));
        assertEquals(new Vec3(50, 0, -50), onAim.afar(new Vec3(50, 64, 50), 100));
        Route route = via.route(new Vec3(1000, 120, -320));
        assertEquals(via.points(), route.points());
        assertEquals(320 + 500 + 500 * Math.sqrt(5), route.remaining(new Vec3(1000, 120, -320), new Vec3(0, 64, 0)), 1e-9);
    }

    /** Точек не больше {@link Waypoints#MAX}: лишние отбрасываются, а пакет с лишними — ошибка разбора. */
    @Test
    void streamCodecLimitsPoints() {
        Waypoints six = new Waypoints(IntStream.range(0, Waypoints.MAX + 1).mapToObj(i -> new Vec3(i * 100.5, 7, -i * 33.25)).toList());
        assertEquals(Waypoints.MAX, six.size());
        ByteBuf buf = Unpooled.buffer();
        try {
            Waypoints.STREAM_CODEC.encode(buf, six);
            assertEquals(six, Waypoints.STREAM_CODEC.decode(buf));
            assertFalse(buf.isReadable(), "лишние байты после маршрута");
            Waypoints.STREAM_CODEC.encode(buf, Waypoints.NONE);
            assertTrue(Waypoints.STREAM_CODEC.decode(buf).isEmpty());
            buf.clear();
            ByteBufCodecs.VAR_INT.encode(buf, Waypoints.MAX + 1);
            for (int i = 0; i < 2 * (Waypoints.MAX + 1); i++) buf.writeDouble(i);
            assertThrows(RuntimeException.class, () -> Waypoints.STREAM_CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    /** Сохранение залпа: маршрут читается тем же; NaN — не маршрут для приказа. */
    @Test
    void codecRoundTripAndFinite() {
        Waypoints via = new Waypoints(List.of(new Vec3(-12_345.5, 0, 678.25), new Vec3(4, 0, -9)));
        assertEquals(via, Waypoints.CODEC.parse(JsonOps.INSTANCE, Waypoints.CODEC.encodeStart(JsonOps.INSTANCE, via).getOrThrow()).getOrThrow());
        assertTrue(via.finite());
        assertFalse(new Waypoints(List.of(new Vec3(Double.NaN, 0, 0))).finite());
        assertFalse(new Waypoints(List.of(new Vec3(0, 0, Double.POSITIVE_INFINITY))).finite());
    }
}
