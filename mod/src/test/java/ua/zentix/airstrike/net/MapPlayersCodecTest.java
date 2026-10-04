package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.target.Sightings;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapPlayersCodecTest {
    /**
     * Метки карты проходят через буфер без потерь: UUID, имя (и не латиницей), координаты с дробью и за минусом, что это,
     * свой ли, давность; рисует ли их карта.
     */
    @Test
    void roundTrip() {
        S2C.MapPlayers sent = new S2C.MapPlayers(List.of(
                new S2C.MapPlayer(UUID.randomUUID(), "ENOTzRPG", -1234.75, 98765.5, Sightings.Kind.PLAYER, false, 12),
                new S2C.MapPlayer(UUID.randomUUID(), "Друг_1", 0.125, -29_999_984.0, Sightings.Kind.PLAYER, true, 0),
                new S2C.MapPlayer(UUID.randomUUID(), "", 7, 8, Sightings.Kind.AIRCRAFT, false, 600),
                new S2C.MapPlayer(UUID.randomUUID(), "Шахед", -7, -8, Sightings.Kind.PROJECTILE, false, 0)), true);
        ByteBuf buf = Unpooled.buffer();
        S2C.MapPlayers.CODEC.encode(buf, sent);
        S2C.MapPlayers got = S2C.MapPlayers.CODEC.decode(buf);
        assertEquals(sent, got);
        assertEquals(0, buf.readableBytes(), "прочитано всё, что записано");

        ByteBuf empty = Unpooled.buffer();
        S2C.MapPlayers.CODEC.encode(empty, new S2C.MapPlayers(List.of(), false));
        assertEquals(new S2C.MapPlayers(List.of(), false), S2C.MapPlayers.CODEC.decode(empty));
    }

    /** Имя длиннее предела не уходит в пакет (кодек строки бросает, а не режет молча). */
    @Test
    void nameLimit() {
        S2C.MapPlayers tooLong = new S2C.MapPlayers(List.of(new S2C.MapPlayer(UUID.randomUUID(), "x".repeat(S2C.MapPlayer.MAX_NAME + 1), 0, 0,
                Sightings.Kind.PLAYER, false, 0)), true);
        assertThrows(RuntimeException.class, () -> S2C.MapPlayers.CODEC.encode(Unpooled.buffer(), tooLong));
    }
}
