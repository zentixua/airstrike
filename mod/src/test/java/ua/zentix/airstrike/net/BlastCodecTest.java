package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Взрыв в пакете: со снарядом, который взорвался (его путь у клиента кончается), и без него. */
class BlastCodecTest {
    @Test
    void blastComesBackWithItsProjectile() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), roundTrip(new S2C.Blast(S2C.Blast.MISSILE, new Vec3(1234.5, 64.25, -987.125), 3, 70.5f, -42L, Optional.of(id))).projectile());
        assertEquals(Optional.empty(), roundTrip(new S2C.Blast(S2C.Blast.BUNKER, new Vec3(-8.5, -12, 4096.75), 0, 80, Long.MAX_VALUE, Optional.empty())).projectile());
    }

    private static S2C.Blast roundTrip(S2C.Blast b) {
        ByteBuf buf = Unpooled.buffer();
        try {
            S2C.Blast.CODEC.encode(buf, b);
            S2C.Blast back = S2C.Blast.CODEC.decode(buf);
            assertFalse(buf.isReadable(), "лишние байты после взрыва");
            assertEquals(b, back);
            return back;
        } finally {
            buf.release();
        }
    }
}
