package ua.zentix.airstrike.rig;

import io.netty.channel.ChannelFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Серверы проверок (стенд нагрузки, мультиплеер без окон, клиенты сценариев и боевой клиент) слушают только петлю:
 * на VPS сервер стенда с {@code online-mode=false} на 0.0.0.0:25565 нашёл сканер из интернета. Скрипты пишут
 * {@code server-ip=127.0.0.1}, а этот сторож роняет сервер, если сокет всё же открыт не на петле: выделенный —
 * до загрузки мира ({@link ServerAboutToStartEvent} приходит сразу после bind), открытый позже мир (LAN) — по тику.
 * Сокеты — все каналы {@link ServerConnectionListener}: TCP игры и UDP Sable (его миксин кладёт свой канал в тот же
 * список, на тот же адрес). Выделенный сервер NeoForge ещё и объявляет себя в LAN ({@code advertiseDedicatedServerToLan}:
 * {@code LanServerPinger} с UDP-сокетом на 0.0.0.0, раз в 1,5 с шлёт MOTD и порт на 224.0.2.60:4445) — у серверов
 * проверок это выключено, включённое тоже роняет сервер. В jar мода не входит (devtest).
 */
@Mod(Airstrike.MOD_ID)
public final class LoopbackGuard {
    private static final Field CHANNELS = ObfuscationReflectionHelper.findField(ServerConnectionListener.class, "channels");
    private static final int PERIOD = 20;

    public LoopbackGuard() {
        NeoForge.EVENT_BUS.addListener(LoopbackGuard::onAboutToStart);
        NeoForge.EVENT_BUS.addListener(LoopbackGuard::onTick);
    }

    private static void onAboutToStart(ServerAboutToStartEvent e) {
        MinecraftServer server = e.getServer();
        String bad = publicAddress(server);
        if (bad == null && server.isDedicatedServer() && NeoForgeConfig.SERVER.advertiseDedicatedServerToLan.get()) {
            // конфиги сервера уже загружены (до события), а объявление в LAN стартует после него
            bad = "объявление в LAN (advertiseDedicatedServerToLan = true в config/neoforge-server.toml)";
        }
        if (bad == null) return;
        Airstrike.LOG.error("RIG сервер проверки открыт в сеть: {} — сервер остановлен", bad);
        server.getConnection().stop();
        throw new IllegalStateException("сервер проверки открыт в сеть: " + bad);
    }

    private static void onTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (server.getTickCount() % PERIOD != 0) return;
        String bad = publicAddress(server);
        if (bad == null) return;
        Airstrike.LOG.error("RIG сервер проверки слушает не петлю: {} — мир открыт в сеть, сервер остановлен", bad);
        server.getConnection().stop();
        server.halt(false);
    }

    /** Первый адрес слушающего сокета сервера (TCP игры, UDP Sable), который не петля, или null. */
    @Nullable
    public static String publicAddress(MinecraftServer server) {
        ServerConnectionListener connection = server.getConnection();
        if (connection == null) return null;
        List<ChannelFuture> live = channels(connection);
        List<SocketAddress> addresses = new ArrayList<>();
        synchronized (live) {
            for (ChannelFuture f : live) addresses.add(f.channel().localAddress());
        }
        return firstPublic(addresses);
    }

    /** Первый адрес, который не петля (0.0.0.0 и :: тоже), или null; канал в памяти (LocalAddress) — не сеть. */
    @Nullable
    public static String firstPublic(List<SocketAddress> addresses) {
        for (SocketAddress address : addresses) {
            if (address instanceof InetSocketAddress inet && (inet.getAddress() == null || !inet.getAddress().isLoopbackAddress())) {
                return inet.toString();
            }
        }
        return null;
    }

    /** Живой список каналов сервера (синхронизированный, как у ванили): в него кладёт свой канал и Sable. */
    @SuppressWarnings("unchecked")
    public static List<ChannelFuture> channels(ServerConnectionListener connection) {
        try {
            return (List<ChannelFuture>) CHANNELS.get(connection);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
