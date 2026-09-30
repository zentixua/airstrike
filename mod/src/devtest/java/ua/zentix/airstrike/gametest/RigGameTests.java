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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.rig.LoopbackGuard;
import ua.zentix.airstrike.scenario.ScenarioCommands;
import ua.zentix.airstrike.scenario.StrikeWatch;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /** Команды сценария {@code commands}: «;» в NBT-массиве и в кавычках — часть команды, пустые пункты отбрасываются. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "rig_tools")
    public static void scenarioCommandsSplitTopLevel(GameTestHelper h) {
        String cow = "summon cow -272 110 -1142 {NoAI:1b,UUID:[I;7001,7002,7003,7004],Tags:[\"drone_target\"]}";
        List<String> got = ScenarioCommands.split("gamemode spectator; " + cow + ";wait:200;;say \"a;b\";tp @s 0 100 0 ;");
        List<String> want = List.of("gamemode spectator", cow, "wait:200", "say \"a;b\"", "tp @s 0 100 0");
        h.assertTrue(got.equals(want), "разбор: " + got);
        h.assertTrue(ScenarioCommands.split(" ; ").isEmpty(), "пустые пункты не отброшены");
        String quoted = "summon armor_stand 0 64 0 {CustomName:'a;b',Tags:['x;y']}";
        List<String> mixed = ScenarioCommands.split("say it's here; " + quoted + "; time set day");
        h.assertTrue(mixed.equals(List.of("say it's here", quoted, "time set day")), "апостроф и строки SNBT: " + mixed);
        h.succeed();
    }

    /**
     * Удар сценария {@code fx}: снаряд — единственный новый UUID после команды, удар — первый взрыв с ним в источнике.
     * Чужой взрыв, взрыв без снаряда и второй взрыв своего снаряда (вторичный подрыв) ударом не считаются; без
     * однозначного пуска (новых нет или два) не считается ничего.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "rig_tools")
    public static void strikeWatchCountsOwnBlastOnly(GameTestHelper h) {
        UUID old = new UUID(1, 1), mine = new UUID(2, 2), other = new UUID(3, 3);
        Vec3 at = new Vec3(10, 64, 10);
        StrikeWatch w = new StrikeWatch();
        h.assertTrue(w.launched(Set.of(old), Set.of(old, mine)), "пуск не найден");
        h.assertTrue(mine.equals(w.projectile()), "не тот снаряд: " + w.projectile());
        h.assertFalse(w.onBlast(other, at, 5), "чужой взрыв засчитан");
        h.assertFalse(w.onBlast(null, at, 6), "взрыв без снаряда засчитан");
        h.assertTrue(w.impactTick() < 0, "удар до своего взрыва");
        h.assertTrue(w.onBlast(mine, at, 7), "свой взрыв не засчитан");
        h.assertFalse(w.onBlast(mine, at.add(3, 0, 0), 9), "вторичный подрыв засчитан ударом");
        h.assertTrue(w.impactTick() == 7 && at.equals(w.impactAt()), "удар: " + w.impactTick() + " у " + w.impactAt());
        StrikeWatch two = new StrikeWatch();
        h.assertFalse(two.launched(Set.of(), Set.of(mine, other)), "два новых снаряда — пуск засчитан");
        h.assertFalse(two.onBlast(mine, at, 1), "без пуска засчитан удар");
        StrikeWatch none = new StrikeWatch();
        h.assertFalse(none.launched(Set.of(old), Set.of(old)), "нового снаряда нет — пуск засчитан");
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
