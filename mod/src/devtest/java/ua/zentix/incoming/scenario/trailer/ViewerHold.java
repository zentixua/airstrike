package ua.zentix.incoming.scenario.trailer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import ua.zentix.incoming.Incoming;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;

/**
 * Пакеты зрителю повтора после пакета входа — только когда у клиента есть мир. Клиент разбирает пакеты в своём сетевом
 * потоке по приходу (соединение в памяти тоже кодирует их: {@code Connection.configureInMemoryPipeline}), а мир создаёт
 * поток клиента, когда дойдёт до пакета входа ({@code ClientPacketListener.handleLogin}). Сервер повтора — в том же
 * процессе и шлёт сразу за входом, раньше, чем клиент создал мир: пакет мода, чей разбор читает мир клиента (Point
 * Blank — состояние оружия в руке других игроков, {@code ClientUtil.getClientLevel()} в {@code decode}), падает, и
 * клиент отключается от повтора. Поэтому всё, что сервер шлёт зрителю после пакета входа, ждёт в канале зрителя на
 * сервере (в том же порядке), пока у клиента не будут мир и игрок ({@link #release}).
 */
final class ViewerHold extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "incoming_film_viewer_hold";
    private static volatile ViewerHold current;

    private record Pending(Object msg, ChannelPromise promise) {}

    // поля — только в потоке канала
    private final ArrayDeque<Pending> held = new ArrayDeque<>();
    private volatile ChannelHandlerContext ctx;
    private boolean login, released;
    private int mods;

    /** Соединение зрителя на сервере повтора, при настройке (до пакета входа). */
    static void install(Connection connection) {
        ViewerHold hold = new ViewerHold();
        connection.channel().pipeline().addBefore(HandlerNames.PACKET_HANDLER, NAME, hold);
        current = hold;
    }

    /** У клиента есть мир повтора и игрок: держанное — зрителю (в потоке канала). */
    static void release() {
        ViewerHold hold = current;
        if (hold == null || hold.ctx == null) return;
        current = null;
        hold.ctx.executor().execute(hold::drain);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (released || !login) {
            if (msg instanceof ClientboundLoginPacket) login = true;
            ctx.write(msg, promise);
            return;
        }
        if (msg instanceof ClientboundCustomPayloadPacket) mods++;
        held.add(new Pending(msg, promise));
    }

    /** Канал закрылся раньше, чем у клиента появился мир: держанное — не отправлено. */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        for (Pending p; (p = held.poll()) != null; ) {
            ReferenceCountUtil.release(p.msg);
            p.promise.tryFailure(new ClosedChannelException());
        }
    }

    private void drain() {
        if (ctx.isRemoved()) return;
        released = true;
        int n = held.size();
        for (Pending p; (p = held.poll()) != null; ) ctx.write(p.msg, p.promise);
        ctx.flush();
        ctx.pipeline().remove(this);
        if (login) {
            Incoming.LOG.info("SCENARIO film: зрителю повтора после входа — {} пакетов (модов {}), когда у клиента есть мир", n, mods);
        } else {
            Incoming.LOG.warn("SCENARIO film: пакет входа зрителя прошёл мимо удержания, пакеты после входа не держались");
        }
    }
}
