package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AimHintCodecTest {
    private static C2S.AimHint roundTrip(C2S.AimHint hint) {
        ByteBuf buf = Unpooled.buffer();
        try {
            C2S.AimHint.CODEC.encode(buf, hint);
            C2S.AimHint back = C2S.AimHint.CODEC.decode(buf);
            assertFalse(buf.isReadable(), "лишние байты после подсказки");
            return back;
        } finally {
            buf.release();
        }
    }

    /** Верх по карте клиента доходит до сервера: без него сервер брал рельеф генератора (Greenfield: 63 под крышей на 107). */
    @Test
    void mapPlaceCarriesMapSurface() {
        C2S.AimHint hint = C2S.AimHint.ground(250.5, -146.5, OptionalInt.of(108));
        C2S.AimHint back = roundTrip(hint);
        assertEquals(hint, back);
        assertEquals(Optional.of(108), back.mapSurface());
    }

    @Test
    void mapPlaceWithoutMapSurface() {
        C2S.AimHint back = roundTrip(C2S.AimHint.ground(-1234.25, 987.75, OptionalInt.empty()));
        assertEquals(Optional.empty(), back.mapSurface());
        assertEquals(new Vec3(-1234.25, 0, 987.75), back.point());
    }

    @Test
    void otherHintsHaveNoMapSurface() {
        C2S.AimHint hint = new C2S.AimHint(C2S.AimHint.AIRCRAFT, new Vec3(1, 2, 3), 7, new Vec3(4, 5, 6));
        assertEquals(hint, roundTrip(hint));
        assertEquals(Optional.empty(), hint.mapSurface());
    }
}
