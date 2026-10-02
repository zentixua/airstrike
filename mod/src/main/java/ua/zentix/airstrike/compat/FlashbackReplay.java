package ua.zentix.airstrike.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.net.S2C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Сервер повтора Flashback — встроенный сервер, который читает запись и отдаёт её пакеты зрителю. Пакеты модов он
 * отдаёт, как прочёл ({@code ReplayServer.handleGamePacket}), а перематывая, в одном своём тике ставит снимок куска
 * записи (Flashback NeoForge Fixed кладёт в него последний пакет каждого вида) и читает пакеты от его начала до нужного
 * места, последние 20 тиков — по тику, с поднятым {@code fastForwarding}. Каждое чтение кончается тиком сервера
 * ({@code runUpdates} → {@code tickServer}), поэтому в его конце мод шлёт зрителям метку {@link S2C.ReplayTick}: место
 * в записи и перематывает ли сервер — по {@code doClientRendering}: в этом чтении был снимок или идёт досмотр по тику.
 * При выводе видео Flashback не снимает отметку снимка до конца вывода — там перемотка только {@code fastForwarding},
 * а скачок места клиент видит сам. Пакеты одного соединения клиент разбирает по порядку, так что всё, что пришло
 * до метки, прочитано в этом тике сервера (клиент решает, живое ли оно: {@code client.replay.Replay}).
 * <p>
 * API для модов у Flashback нет, мод необязательный и только клиентский: открытые методы и поле сервера повтора —
 * через отражение; сервер и клиент повтора — в одной JVM и смотрят одни и те же поля. Без Flashback или без этих
 * членов — меток нет, и клиент принимает события, как раньше.
 */
public final class FlashbackReplay {
    private static boolean looked;
    @Nullable
    private static volatile Class<?> serverClass;
    @Nullable
    private static Method place, rendering, viewers;
    @Nullable
    private static Field fastForwarding, exportJob;

    private FlashbackReplay() {}

    /** {@code s} — сервер повтора Flashback и мод его понимает. */
    public static boolean isReplay(@Nullable MinecraftServer s) {
        if (s == null) return false;
        if (!looked) find();
        Class<?> c = serverClass;
        return c != null && c.isInstance(s);
    }

    /** Конец тика сервера повтора (и каждого чтения перемотки): зрителям — метка с местом в записи. */
    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer s = e.getServer();
        if (!isReplay(s)) return;
        try {
            boolean seeking = exportJob.get(null) != null ? fastForwarding.getBoolean(s) : !(boolean) rendering.invoke(s);
            S2C.ReplayTick tick = new S2C.ReplayTick((int) place.invoke(s), seeking);
            for (Object v : (Collection<?>) viewers.invoke(s)) {
                if (v instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, tick);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            off(ex);
        }
    }

    private static synchronized void find() {
        if (looked) return;
        try {
            if (!ModList.get().isLoaded("flashback")) return;
            // без инициализации класса: в обычной игре сервера повтора нет
            Class<?> c = Class.forName("com.moulberry.flashback.playback.ReplayServer", false, FlashbackReplay.class.getClassLoader());
            place = c.getMethod("getReplayTick");
            rendering = c.getMethod("doClientRendering");
            viewers = c.getMethod("getReplayViewers");
            fastForwarding = c.getField("fastForwarding");
            exportJob = Class.forName("com.moulberry.flashback.Flashback", false, FlashbackReplay.class.getClassLoader()).getField("EXPORT_JOB");
            serverClass = c;
        } catch (ReflectiveOperationException | LinkageError ex) {
            off(ex);
        } finally {
            looked = true;
        }
    }

    private static void off(Throwable ex) {
        if (serverClass == null && looked) return;
        serverClass = null;
        Airstrike.LOG.warn("Flashback: не читаются члены сервера повтора ({}) — перемотку повтора мод не узнает, события в нём идут как живые", ex.toString());
    }
}
