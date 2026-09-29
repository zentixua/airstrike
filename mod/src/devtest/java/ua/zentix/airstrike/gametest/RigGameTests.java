package ua.zentix.airstrike.gametest;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.rig.LoopbackGuard;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Стенд и серверы проверок (devtest). */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RigGameTests {
    private RigGameTests() {}

    /**
     * Сторож петли видит UDP-канал Sable: миксин Sable кладёт свой канал (Netty, датаграммы) в тот же список каналов
     * сервера, что и TCP игры, на тот же адрес. Здесь такой канал поднимается на петле (адрес 0.0.0.0 тест открывать
     * не должен) и кладётся в каналы сервера GameTest, как это делает Sable: сторож читает его адрес и молчит, а тот же
     * порт на 0.0.0.0 и на :: — называет.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "rig_loopback")
    public static void loopbackGuardSeesUdpChannel(GameTestHelper h) throws UnknownHostException {
        MinecraftServer server = h.getLevel().getServer();
        List<ChannelFuture> live = LoopbackGuard.channels(server.getConnection());
        ChannelFuture udp = new Bootstrap().channel(NioDatagramChannel.class).group(ServerConnectionListener.SERVER_EVENT_GROUP.get())
                .handler(new ChannelInboundHandlerAdapter()).localAddress(InetAddress.getLoopbackAddress(), 0).bind().syncUninterruptibly();
        try {
            live.add(udp);
            SocketAddress bound = udp.channel().localAddress();
            h.assertTrue(bound instanceof InetSocketAddress, "адрес UDP-канала не сетевой: " + bound);
            h.assertTrue(LoopbackGuard.publicAddress(server) == null, "сторож поднял тревогу на петле: " + LoopbackGuard.publicAddress(server));
            int port = ((InetSocketAddress) bound).getPort();
            SocketAddress any4 = new InetSocketAddress(InetAddress.getByAddress(new byte[4]), port);
            SocketAddress any6 = new InetSocketAddress(InetAddress.getByAddress(new byte[16]), port);
            h.assertTrue(LoopbackGuard.firstPublic(List.of(bound, any4)) != null, "сторож не заметил UDP на 0.0.0.0");
            h.assertTrue(LoopbackGuard.firstPublic(List.of(bound, any6)) != null, "сторож не заметил UDP на ::");
        } finally {
            live.remove(udp);
            udp.channel().close().syncUninterruptibly();
        }
        h.succeed();
    }
}
