package ua.zentix.airstrike.target;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

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
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        PlayerTeam team = team(server, a);
        return team != null && team.isAlliedTo(team(server, b));
    }

    /**
     * Ключ стороны: общий у своих — имя команды, у игрока без команды — его UUID (имена команд и UUID не путаются:
     * у них разные приставки).
     */
    public static String side(MinecraftServer server, UUID player) {
        PlayerTeam team = team(server, player);
        return team != null ? "team:" + team.getName() : "player:" + player;
    }

    /** Ключ стороны игрока в сети ({@link #side(MinecraftServer, UUID)}). */
    public static String side(ServerPlayer player) {
        return side(player.server, player.getUUID());
    }

    /**
     * Команда игрока по UUID. Табло хранит участников команд по именам: у игрока в сети имя — его, иначе — из кэша
     * профилей сервера (нет там — команды не узнать).
     */
    @Nullable
    public static PlayerTeam team(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        String name = online != null ? online.getScoreboardName() : cachedName(server, player);
        return name == null ? null : server.getScoreboard().getPlayersTeam(name);
    }

    @Nullable
    private static String cachedName(MinecraftServer server, UUID player) {
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? null : cache.get(player).map(GameProfile::getName).orElse(null);
    }
}
