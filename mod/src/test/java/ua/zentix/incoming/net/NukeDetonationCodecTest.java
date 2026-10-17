package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Ядерный подрыв в пакете — и в повторе Flashback, записанном до поля {@code blackout}: там пакет без него. */
class NukeDetonationCodecTest {
    private static final Detonation D = new Detonation(4, new Vec3(-120.5, 64, 2048.25), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);

    @Test
    void roundTrip() {
        ByteBuf buf = Unpooled.buffer();
        try {
            S2C.NukeDetonation p = new S2C.NukeDetonation(D, 242f);
            S2C.NukeDetonation.CODEC.encode(buf, p);
            S2C.NukeDetonation back = S2C.NukeDetonation.CODEC.decode(buf);
            assertFalse(buf.isReadable(), "лишние байты после подрыва");
            assertEquals(p, back);
        } finally {
            buf.release();
        }
    }

    @Test
    void oldReplayWithoutBlackout() {
        ByteBuf buf = Unpooled.buffer();
        try {
            Detonation.STREAM_CODEC.encode(buf, D);
            S2C.NukeDetonation back = S2C.NukeDetonation.CODEC.decode(buf);
            assertFalse(buf.isReadable());
            assertEquals(new S2C.NukeDetonation(D, 0f), back);
        } finally {
            buf.release();
        }
    }
}
