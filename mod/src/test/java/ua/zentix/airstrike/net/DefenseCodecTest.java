package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пакеты ЗРК проходят через буфер без потерь. */
class DefenseCodecTest {
    @Test
    void interceptRoundTrip() {
        for (S2C.Intercept sent : List.of(
                new S2C.Intercept(new Vec3(-1234.5, 180.25, 98765.125), true, Optional.of(UUID.randomUUID()), -42L),
                new S2C.Intercept(new Vec3(0, -64, 0), false, Optional.empty(), Long.MAX_VALUE))) {
            ByteBuf buf = Unpooled.buffer();
            S2C.Intercept.CODEC.encode(buf, sent);
            assertEquals(sent, S2C.Intercept.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes(), "прочитано всё, что записано");
        }
    }

    /** Экран радара: дальности, запас, цели со сдвигом за минусом, курсом, видом оружия и признаками. */
    @Test
    void radarScopeRoundTrip() {
        S2C.RadarScope sent = new S2C.RadarScope(new Vec3(100.5, 70, -300.5), 3000, 1500, 3, 12, List.of(
                new S2C.Blip(-1499.5f, 20.25f, -170f, 0, S2C.Blip.HOSTILE | S2C.Blip.ENGAGEABLE | S2C.Blip.ENGAGED),
                new S2C.Blip(2999f, -2999f, 45f, 5, 0)));
        ByteBuf buf = Unpooled.buffer();
        S2C.RadarScope.CODEC.encode(buf, sent);
        S2C.RadarScope got = S2C.RadarScope.CODEC.decode(buf);
        assertEquals(sent, got);
        assertEquals(0, buf.readableBytes(), "прочитано всё, что записано");
        assertTrue(got.blips().getFirst().is(S2C.Blip.ENGAGED));
        assertTrue(!got.blips().get(1).is(S2C.Blip.HOSTILE));
    }

    /** Целей больше предела в пакет не уходит (кодек списка бросает): сервер режет список сам. */
    @Test
    void blipLimit() {
        List<S2C.Blip> many = new ArrayList<>();
        for (int i = 0; i <= S2C.RadarScope.MAX_BLIPS; i++) many.add(new S2C.Blip(i, i, 0, 0, 0));
        assertThrows(RuntimeException.class, () -> S2C.RadarScope.CODEC.encode(Unpooled.buffer(), new S2C.RadarScope(Vec3.ZERO, 1, 1, 0, 0, many)));
    }
}
