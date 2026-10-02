package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Снаряд вдали в пакете: всё, из чего клиент строит путь и позу модели, с креном. */
class FarFlightCodecTest {
    @Test
    void flightComesBackWithItsRoll() {
        // скорость — float в пакете: числа, точные во float
        S2C.FarFlight f = new S2C.FarFlight(UUID.randomUUID(), WeaponType.MISSILE.id(), false, false, true, new Vec3(1234.5, 96.25, -987.125),
                new Vec3(3.5, -0.25, 1.75), -60, 2.5f, -27.5f, FlightPhase.CRUISE.ordinal(), 314, new Vec3(4000.5, 70, -3000.25));
        ByteBuf buf = Unpooled.buffer();
        try {
            S2C.FarFlight.CODEC.encode(buf, f);
            S2C.FarFlight back = S2C.FarFlight.CODEC.decode(buf);
            assertFalse(buf.isReadable(), "лишние байты после снаряда");
            assertEquals(f, back);
            assertEquals(-27.5f, back.roll());
        } finally {
            buf.release();
        }
    }
}
