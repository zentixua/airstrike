package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapPlayersCodecTest {
    /** Список игроков карты проходит через буфер без потерь: UUID, имя (и не латиницей), координаты с дробью и за минусом. */
    @Test
    void roundTrip() {
        S2C.MapPlayers sent = new S2C.MapPlayers(List.of(
                new S2C.MapPlayer(UUID.randomUUID(), "ENOTzRPG", -1234.75, 98765.5),
                new S2C.MapPlayer(UUID.randomUUID(), "Друг_1", 0.125, -29_999_984.0)));
        ByteBuf buf = Unpooled.buffer();
        S2C.MapPlayers.CODEC.encode(buf, sent);
        S2C.MapPlayers got = S2C.MapPlayers.CODEC.decode(buf);
        assertEquals(sent, got);
        assertEquals(0, buf.readableBytes(), "прочитано всё, что записано");

        ByteBuf empty = Unpooled.buffer();
        S2C.MapPlayers.CODEC.encode(empty, new S2C.MapPlayers(List.of()));
        assertEquals(List.of(), S2C.MapPlayers.CODEC.decode(empty).players());
    }

    /** Имя длиннее предела не уходит в пакет (кодек строки бросает, а не режет молча). */
    @Test
    void nameLimit() {
        S2C.MapPlayers tooLong = new S2C.MapPlayers(List.of(new S2C.MapPlayer(UUID.randomUUID(), "x".repeat(S2C.MapPlayer.MAX_NAME + 1), 0, 0)));
        assertThrows(RuntimeException.class, () -> S2C.MapPlayers.CODEC.encode(Unpooled.buffer(), tooLong));
    }
}
