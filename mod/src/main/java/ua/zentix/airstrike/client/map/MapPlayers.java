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
import ua.zentix.airstrike.target.Sightings;

import java.util.ArrayList;
import java.util.List;

/**
 * Метки карты наведения: свои игроки и замеченное своей стороной (чужие игроки, аппараты, снаряды). Что заметили,
 * знает только сервер, поэтому карта и список игроков пульта, пока открыты, спрашивают его раз в полсекунды
 * ({@link C2S.MapPlayers}). Своего и чужого, которого видят сейчас, карта ведёт по его сущности, если клиент её
 * знает, — плавно между ответами; давно не виденного — там, где его видели.
 */
public final class MapPlayers {
    /**
     * Метка: имя (у игрока — ник, им пульт и целится), где он, что это, свой ли и сколько секунд назад видели (0 — сейчас).
     */
    public record Mark(String name, double x, double z, Sightings.Kind kind, boolean friendly, int age) {
        /** По метке можно ударить: чужой игрок (по нику). */
        public boolean target() {
            return kind == Sightings.Kind.PLAYER && !friendly;
        }
    }

    private static List<S2C.MapPlayer> players = List.of();
    /** Карта рисует метки ({@code map_players}); иначе они только цели пульта. */
    private static boolean shown;
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
        shown = p.shown();
        dimension = level.dimension();
    }

    /** Метки карты в измерении {@code level} на момент кадра {@code partialTick} (выключены в настройках мира — пусто). */
    public static List<Mark> marks(@Nullable ClientLevel level, float partialTick) {
        if (level == null || !shown || !level.dimension().equals(dimension)) return List.of();
        List<Mark> marks = new ArrayList<>(players.size());
        for (S2C.MapPlayer p : players) {
            Player seen = p.kind() == Sightings.Kind.PLAYER && p.age() == 0 ? level.getPlayerByUUID(p.id()) : null;
            marks.add(seen != null
                    ? new Mark(p.name(), seen.getPosition(partialTick).x, seen.getPosition(partialTick).z, p.kind(), p.friendly(), 0)
                    : new Mark(p.name(), p.x(), p.z(), p.kind(), p.friendly(), p.age()));
        }
        return marks;
    }

    /** Ники чужих игроков, по которым можно ударить (замечены своей стороной), по алфавиту. */
    public static List<String> targets(@Nullable ClientLevel level) {
        if (level == null || !level.dimension().equals(dimension)) return List.of();
        List<String> names = new ArrayList<>();
        for (S2C.MapPlayer p : players) {
            if (p.kind() == Sightings.Kind.PLAYER && !p.friendly()) names.add(p.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static void reset() {
        players = List.of();
        shown = false;
        dimension = null;
    }
}
