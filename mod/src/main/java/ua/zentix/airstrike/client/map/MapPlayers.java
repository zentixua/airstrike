package ua.zentix.airstrike.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.net.S2C;

import java.util.ArrayList;
import java.util.List;

/**
 * Другие игроки для карты наведения: где они, знает только сервер (сущность игрока дальше дальности отслеживания
 * клиенту не приходит), поэтому карта, пока открыта, спрашивает его раз в полсекунды ({@link C2S.MapPlayers}).
 * Игрока, которого клиент видит сам, карта ведёт по его сущности — плавно между ответами.
 */
public final class MapPlayers {
    /** Игрок на карте: имя (им пульт и целится) и где он сейчас. */
    public record Mark(String name, double x, double z) {}

    private static List<S2C.MapPlayer> players = List.of();
    /** Измерение, в котором пришёл список: после смены измерения старый список — чужие координаты. */
    @Nullable
    private static ResourceKey<Level> dimension;

    private MapPlayers() {}

    public static void request() {
        PacketDistributor.sendToServer(new C2S.MapPlayers());
    }

    public static void received(S2C.MapPlayers p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        players = List.copyOf(p.players());
        dimension = level.dimension();
    }

    /** Игроки в измерении {@code level} на момент кадра {@code partialTick}. */
    public static List<Mark> marks(@Nullable ClientLevel level, float partialTick) {
        if (level == null || !level.dimension().equals(dimension)) return List.of();
        List<Mark> marks = new ArrayList<>(players.size());
        for (S2C.MapPlayer p : players) {
            Player seen = level.getPlayerByUUID(p.id());
            marks.add(seen != null
                    ? new Mark(p.name(), seen.getPosition(partialTick).x, seen.getPosition(partialTick).z)
                    : new Mark(p.name(), p.x(), p.z()));
        }
        return marks;
    }

    public static void reset() {
        players = List.of();
        dimension = null;
    }
}
