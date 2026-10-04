package ua.zentix.airstrike.target;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Function;

/**
 * Свой или чужой — по командам {@code /team} (ванильное табло): свои — один и тот же игрок или игроки одной команды.
 * Игрок без команды — сам себе сторона. Удары от консоли и командных блоков (без владельца) — ничьи: чужие всем.
 */
public final class Sides {
    private Sides() {}

    /** Свои: одна сущность или одна команда ({@link Entity#isAlliedTo(Entity)}). */
    public static boolean friendly(Entity a, Entity b) {
        return a == b || a.isAlliedTo(b);
    }

    /**
     * Свои по UUID игроков (владельцы снарядов и пусковых; игрок может быть не в сети): тот же игрок или одна команда.
     * Null — ничей (консоль), не свой никому.
     */
    public static boolean friendly(MinecraftServer server, @Nullable UUID a, @Nullable UUID b) {
        return friendly(server.getScoreboard(), a, b, names(server));
    }

    /** {@link #friendly(MinecraftServer, UUID, UUID)} по табло {@code board} и именам игроков {@code names}. */
    public static boolean friendly(Scoreboard board, @Nullable UUID a, @Nullable UUID b, Function<UUID, @Nullable String> names) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        PlayerTeam team = team(board, a, names);
        return team != null && team.isAlliedTo(team(board, b, names));
    }

    /**
     * Ключ стороны: общий у своих — имя команды, у игрока без команды — его UUID (имена команд и UUID не путаются:
     * у них разные приставки).
     */
    public static String side(MinecraftServer server, UUID player) {
        return side(server.getScoreboard(), player, names(server));
    }

    /** {@link #side(MinecraftServer, UUID)} по табло {@code board} и именам игроков {@code names}. */
    public static String side(Scoreboard board, UUID player, Function<UUID, @Nullable String> names) {
        return key(team(board, player, names), player);
    }

    /** Ключ стороны игрока в сети ({@link #side(MinecraftServer, UUID)}): команда — по его имени на табло. */
    public static String side(ServerPlayer player) {
        return key(player.getTeam(), player.getUUID());
    }

    private static String key(@Nullable PlayerTeam team, UUID player) {
        return team != null ? "team:" + team.getName() : "player:" + player;
    }

    /** Команда игрока по UUID: табло хранит участников команд по именам ({@link #names}). */
    @Nullable
    public static PlayerTeam team(Scoreboard board, UUID player, Function<UUID, @Nullable String> names) {
        String name = names.apply(player);
        return name == null ? null : board.getPlayersTeam(name);
    }

    /**
     * Имя игрока на табло по UUID: у игрока в сети — его, иначе — из кэша профилей сервера (нет там — null: команды не
     * узнать).
     */
    public static Function<UUID, @Nullable String> names(MinecraftServer server) {
        return id -> {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return online.getScoreboardName();
            GameProfileCache cache = server.getProfileCache();
            return cache == null ? null : cache.get(id).map(GameProfile::getName).orElse(null);
        };
    }
}
