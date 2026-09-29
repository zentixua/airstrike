package ua.zentix.airstrike.gametest;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.rig.LoopbackGuard;

import java.lang.reflect.Proxy;
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
     * сервера, что и TCP игры, на тот же адрес. Здесь такой канал поднимается на петле и кладётся в каналы сервера
     * GameTest, как это делает Sable, — сторож молчит; потом рядом кладётся канал датаграмм, который сообщает адрес
     * 0.0.0.0 или :: (заглушка: открывать такой сокет тест не должен), — сторож его называет.
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
            for (byte[] any : List.of(new byte[4], new byte[16])) {
                InetSocketAddress wide = new InetSocketAddress(InetAddress.getByAddress(any), port);
                ChannelFuture open = stub(DatagramChannel.class, wide);
                live.add(open);
                try {
                    h.assertTrue(LoopbackGuard.publicAddress(server) != null, "сторож не заметил UDP на " + wide);
                } finally {
                    live.remove(open);
                }
            }
        } finally {
            live.remove(udp);
            udp.channel().close().syncUninterruptibly();
        }
        h.succeed();
    }

    /**
     * Канал (заглушка: только {@code localAddress}), который уже «слушает» на {@code address}, — как его видит сторож
     * в списке каналов сервера. Настоящий сокет не открывается.
     */
    private static ChannelFuture stub(Class<? extends Channel> type, InetSocketAddress address) {
        Channel channel = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, args) -> switch (m.getName()) {
            case "localAddress" -> address;
            case "toString" -> "заглушка " + type.getSimpleName() + " " + address;
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> throw new UnsupportedOperationException(m.getName());
        }));
        return (ChannelFuture) Proxy.newProxyInstance(ChannelFuture.class.getClassLoader(), new Class<?>[]{ChannelFuture.class}, (self, m, args) -> switch (m.getName()) {
            case "channel" -> channel;
            case "isDone", "isSuccess" -> true;
            case "toString" -> "заглушка " + channel;
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
}
