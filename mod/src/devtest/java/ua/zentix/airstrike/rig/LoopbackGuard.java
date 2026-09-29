package ua.zentix.airstrike.rig;

import io.netty.channel.ChannelFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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
 * В jar мода не входит (devtest).
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
        if (bad == null) return;
        Airstrike.LOG.error("RIG сервер проверки слушает не петлю: {} — нужен server-ip=127.0.0.1, сервер остановлен", bad);
        server.getConnection().stop();
        throw new IllegalStateException("сервер проверки слушает не петлю: " + bad);
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

    /** Первый адрес слушающего сокета, который не петля (0.0.0.0 и :: тоже), или null. */
    static String publicAddress(MinecraftServer server) {
        ServerConnectionListener connection = server.getConnection();
        if (connection == null) return null;
        List<ChannelFuture> channels;
        try {
            @SuppressWarnings("unchecked")
            List<ChannelFuture> live = (List<ChannelFuture>) CHANNELS.get(connection);
            synchronized (live) {
                channels = new ArrayList<>(live);
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
        for (ChannelFuture f : channels) {
            SocketAddress address = f.channel().localAddress();
            // канал в памяти встроенного сервера — LocalAddress, не сеть
            if (address instanceof InetSocketAddress inet && (inet.getAddress() == null || !inet.getAddress().isLoopbackAddress())) {
                return inet.toString();
            }
        }
        return null;
    }
}
