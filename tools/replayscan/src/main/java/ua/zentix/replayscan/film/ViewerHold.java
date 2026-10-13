package ua.zentix.replayscan.film;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import org.jetbrains.annotations.Nullable;

import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Пакеты зрителю повтора после пакета входа — только когда у клиента есть мир. Клиент разбирает пакеты в своём сетевом
 * потоке по приходу (соединение в памяти тоже кодирует их: {@code Connection.configureInMemoryPipeline}), а мир создаёт
 * поток клиента, когда дойдёт до пакета входа ({@code ClientPacketListener.handleLogin}). Сервер повтора — в том же
 * процессе и шлёт сразу за входом, раньше, чем клиент создал мир: пакет мода, чей разбор читает мир клиента (Point
 * Blank — состояние оружия в руке других игроков, {@code ClientUtil.getClientLevel()} в {@code decode}), падает, и
 * клиент отключается от повтора. Поэтому всё, что сервер шлёт зрителю после пакета входа, ждёт в канале этого
 * соединения на сервере (в том же порядке), пока клиент не прошёл вход по этому же соединению: игрок клиента — на нём,
 * мир — его. Удержание и его отпуск — у каждого соединения свои.
 */
final class ViewerHold extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "replayscan_film_viewer_hold";
    /** Удержания, чей клиент ещё не прошёл вход. */
    private static final Queue<ViewerHold> waiting = new ConcurrentLinkedQueue<>();
    /** Адрес клиента последнего отпущенного удержания: у каждого соединения в памяти он свой. */
    private static volatile SocketAddress released;

    private record Pending(Object msg, ChannelPromise promise) {}

    // held, sent, mods — только в потоке канала
    private final ArrayDeque<Pending> held = new ArrayDeque<>();
    private volatile ChannelHandlerContext ctx;
    private volatile boolean login;
    private boolean sent;
    private int mods;

    /**
     * Удержание на каждом соединении, которое настраивает сервер в этом процессе (у съёмки — только зрители повторов),
     * и его отпуск по тику клиента.
     */
    static void register(IEventBus modBus) {
        modBus.addListener(RegisterConfigurationTasksEvent.class, e -> install(e.getListener().getConnection()));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> releaseReady());
    }

    /** Соединение зрителя на сервере повтора, при настройке (до пакета входа). */
    private static void install(Connection connection) {
        ChannelPipeline pipeline = connection.channel().pipeline();
        if (pipeline.get(NAME) != null) {
            // настройка того же соединения заново, пока прежнее удержание не отпущено: оно и держит
            Film.LOG.warn("Film: the replay viewer's connection is configured again before its held packets were sent");
            return;
        }
        ViewerHold hold = new ViewerHold();
        pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, hold);
        waiting.add(hold);
    }

    /** Клиент прошёл вход по соединению удержания (его игрок — на этом соединении): держанное — ему. */
    private static void releaseReady() {
        if (waiting.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (mc.level == null || listener == null) return;
        SocketAddress client = listener.getConnection().channel().localAddress();
        for (ViewerHold hold : waiting) {
            ChannelHandlerContext c = hold.ctx;
            if (!hold.login || c == null || !client.equals(c.channel().remoteAddress())) continue;
            waiting.remove(hold);
            released = client;
            c.executor().execute(hold::drain);
        }
    }

    /** Держанное для соединения клиента отпущено: пакеты после входа идут ему. */
    static boolean releasedFor(@Nullable ClientPacketListener listener) {
        return listener != null && listener.getConnection().channel().localAddress().equals(released);
    }

    /** Почему держанное ещё не отпущено (для строки в лог, когда повтор не открылся). */
    static String state() {
        if (waiting.isEmpty()) return "no holds";
        return waiting.stream().map(h -> h.login ? "logged in, client not on this connection" : "no login packet yet")
                .toList().toString();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (sent || !login) {
            if (msg instanceof ClientboundLoginPacket) login = true;
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof ClientboundCustomPayloadPacket) mods++;
        held.add(new Pending(msg, promise));
    }

    /**
     * Снят с канала: после отпуска — пусто; канал закрылся раньше, чем клиент прошёл вход, — держанное не отправлено.
     * Обещания не закрываются: ошибку каждого {@code Connection} писала бы в лог, а канала уже нет.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        waiting.remove(this);
        for (Pending p; (p = held.poll()) != null; ) ReferenceCountUtil.release(p.msg);
    }

    private void drain() {
        if (ctx.isRemoved()) return;
        sent = true;
        int n = held.size();
        for (Pending p; (p = held.poll()) != null; ) ctx.write(p.msg, p.promise);
        ctx.flush();
        ctx.pipeline().remove(this);
        Film.LOG.info("Film: replay viewer got {} packets held after login ({} mod payloads) once the client had its level", n, mods);
    }
}
