package ua.zentix.replayscan;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Flashback — мод Fabric (через Sinytra Connector), API для модов у него нет: открытые члены — отражением, как
 * в {@code compat/FlashbackReplay} Incoming. Имена — Flashback 0.39 (ветка 1.21). Сервер повтора и клиент — в одной
 * JVM; члены сервера повтора зовёт его поток, кроме {@link #goTo} и {@link #setSpeed} (так их зовёт и окно Flashback).
 */
final class FlashbackLink {
    private static final String P = "com.moulberry.flashback.";
    private final Class<?> flashback, server;
    private final Method open, getServer, replayTick, goToTick, setTickRate, localPlayerId;
    private final Field paused, fastForwarding;

    private FlashbackLink() throws ReflectiveOperationException {
        ClassLoader loader = FlashbackLink.class.getClassLoader();
        flashback = Class.forName(P + "Flashback", false, loader);
        server = Class.forName(P + "playback.ReplayServer", false, loader);
        open = flashback.getMethod("openReplayWorld", Path.class);
        getServer = flashback.getMethod("getReplayServer");
        replayTick = server.getMethod("getReplayTick");
        goToTick = server.getMethod("goToReplayTick", int.class);
        setTickRate = server.getMethod("setDesiredTickRate", float.class, boolean.class);
        localPlayerId = server.getMethod("getLocalPlayerId");
        paused = server.getField("replayPaused");
        fastForwarding = server.getField("fastForwarding");
    }

    /** Flashback с нужными членами или исключение с именем недостающего. */
    static FlashbackLink find() throws ReflectiveOperationException {
        return new FlashbackLink();
    }

    boolean isReplayServer(@Nullable MinecraftServer s) {
        return s != null && server.isInstance(s);
    }

    void open(Path zip) throws ReflectiveOperationException {
        open.invoke(null, zip);
    }

    @Nullable
    MinecraftServer server() throws ReflectiveOperationException {
        return (MinecraftServer) getServer.invoke(null);
    }

    int tick(MinecraftServer s) throws ReflectiveOperationException {
        return (int) replayTick.invoke(s);
    }

    void goTo(MinecraftServer s, int tick) throws ReflectiveOperationException {
        goToTick.invoke(s, tick);
    }

    /** Скорость повтора: тиков сервера повтора в секунду (ручная скорость окна Flashback, 20 — как в игре). */
    void setSpeed(MinecraftServer s, float ticksPerSecond) throws ReflectiveOperationException {
        setTickRate.invoke(s, ticksPerSecond, true);
    }

    boolean paused(MinecraftServer s) throws ReflectiveOperationException {
        return paused.getBoolean(s);
    }

    void setPaused(MinecraftServer s, boolean value) throws ReflectiveOperationException {
        paused.setBoolean(s, value);
    }

    boolean fastForwarding(MinecraftServer s) throws ReflectiveOperationException {
        return fastForwarding.getBoolean(s);
    }

    /** id сущности записавшего игрока в мире повтора ({@code -1}, пока её нет). */
    int localPlayerId(MinecraftServer s) throws ReflectiveOperationException {
        return (int) localPlayerId.invoke(s);
    }
}
