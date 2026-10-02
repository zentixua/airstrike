package ua.zentix.airstrike.client.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.lang.reflect.Method;

/**
 * Повтор Flashback и его перемотка. Перематывая, сервер повтора Flashback в одном своём тике ставит снимок куска
 * записи и читает пакеты от его начала до нужного места, а пакеты модов отдаёт клиенту сразу, как прочёл
 * ({@code ReplayServer.handleGamePacket}); Flashback NeoForge Fixed ещё и кладёт в каждый снимок последний пакет
 * каждого вида. Клиент принимал их как живые — и взрывы и подрывы с начала куска повторялись разом, со вспышкой
 * и звуком. Теперь событие из перемотки — прошлое: картинки и звука удара нет, ядерный подрыв виден в своём возрасте.
 * <p>
 * Перемотку видно по двум счётчикам сервера повтора: место в записи ({@code getReplayTick}) и тики самого сервера
 * ({@link net.minecraft.server.MinecraftServer#getTickCount}). Повтор идёт — за тик сервера место сдвигается на тик,
 * стоит — не сдвигается; перемотка назад сдвигает его назад, вперёд — дальше, чем прошло тиков (снимок и чтение до
 * места — один тик, потом до 20 тиков по одному). Пока сервер перематывает, он не даёт клиенту рисовать и тикать
 * ({@code doClientRendering}), поэтому пакеты перемотки клиент разбирает раньше своего следующего тика — и сверяет
 * счётчики прямо в разборе пакета с замером своего прошлого тика.
 * <p>
 * API для модов у Flashback нет, мод необязательный: открытые методы сервера повтора — через отражение. Без Flashback,
 * не в повторе или без этих методов — всё вживую, как раньше.
 */
public final class Replay {
    private static final Seeks SEEKS = new Seeks();
    /** Мир, в котором сделан прошлый замер: новый мир повтора (открытие, смена измерения) — замеры заново. */
    @Nullable
    private static ClientLevel level;
    private static boolean looked;
    @Nullable
    private static Class<?> serverClass;
    @Nullable
    private static Method place, rendering;

    private Replay() {}

    /** Тик клиента (и на паузе): замер счётчиков. {@code true} — повтор перемотан (или только открыт) с прошлого тика. */
    public static boolean tick() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer s = server();
        if (s == null || mc.level == null) {
            reset();
            return false;
        }
        if (mc.level != level) {
            reset();
            level = mc.level;
        }
        Sample now = sample(s);
        return now != null && SEEKS.sample(now.place, now.ticks, now.seeking);
    }

    /**
     * Событие от сервера сейчас — живое: не из перемотки повтора. Не в повторе — всегда. В повторе — не в первый тик
     * после открытия или перемотки и не посреди перемотки.
     */
    public static boolean live() {
        IntegratedServer s = server();
        if (s == null) return true;
        if (Minecraft.getInstance().level != level) return false;
        Sample now = sample(s);
        return now == null || SEEKS.live(now.place, now.ticks, now.seeking);
    }

    /** Выход из мира: мир прошлого замера не держать. */
    public static void reset() {
        level = null;
        SEEKS.reset();
    }

    /** Клиент смотрит повтор Flashback. */
    public static boolean active() {
        return server() != null;
    }

    /** Сервер повтора Flashback, если клиент сейчас смотрит повтор. */
    @Nullable
    private static IntegratedServer server() {
        IntegratedServer s = Minecraft.getInstance().getSingleplayerServer();
        if (s == null) return null;
        if (!looked) find();
        return serverClass != null && serverClass.isInstance(s) ? s : null;
    }

    private static void find() {
        looked = true;
        if (!ModList.get().isLoaded("flashback")) return;
        try {
            // без инициализации класса: в обычной игре сервера повтора нет
            Class<?> c = Class.forName("com.moulberry.flashback.playback.ReplayServer", false, Replay.class.getClassLoader());
            place = c.getMethod("getReplayTick");
            rendering = c.getMethod("doClientRendering");
            serverClass = c;
        } catch (ReflectiveOperationException | LinkageError e) {
            off(e);
        }
    }

    @Nullable
    private static Sample sample(IntegratedServer s) {
        try {
            // место — первым: поле volatile, счётчик тиков сервера после него не старее
            int at = (int) place.invoke(s);
            int ticks = s.getTickCount();
            return new Sample(at, ticks, !(boolean) rendering.invoke(s));
        } catch (ReflectiveOperationException | RuntimeException e) {
            off(e);
            return null;
        }
    }

    private static void off(Throwable e) {
        serverClass = null;
        Airstrike.LOG.warn("Flashback: не читаются getReplayTick/doClientRendering сервера повтора ({}) — перемотку повтора мод не узнает, события в нём идут как живые", e.toString());
    }

    private record Sample(int place, int ticks, boolean seeking) {}

    /** Перемотка по замерам счётчиков; без Minecraft — для проверок. */
    static final class Seeks {
        /**
         * На сколько место в записи и тики сервера могут разойтись между замерами без перемотки: они читаются без замка
         * в потоке клиента, и сервер между ними может сделать тик. Перемотка вперёд на меньшее (до 20 + запас тиков
         * сервер проходит по тику) — не перемотка: события этих тиков идут как живые.
         */
        static final int SLACK = 5;
        private boolean sampled, steady;
        private int place, ticks;

        /** Замер в тике клиента; {@code true} — перемотали с прошлого замера (первый замер — тоже). */
        boolean sample(int place, int ticks, boolean seeking) {
            boolean jump = !sampled || jumped(place, ticks, seeking);
            steady = !jump;
            sampled = true;
            this.place = place;
            this.ticks = ticks;
            return jump;
        }

        /** Пакет сейчас — живой: прошлый замер чистый, и с него не перематывали. */
        boolean live(int place, int ticks, boolean seeking) {
            return steady && !jumped(place, ticks, seeking);
        }

        private boolean jumped(int place, int ticks, boolean seeking) {
            int moved = place - this.place, ran = ticks - this.ticks;
            // назад; вперёд дальше, чем шёл сервер; или сервер шёл дольше, чем сдвинулось место, а место не стояло:
            // перемотка на тик-другой назад, после которой повтор успел пройти дальше прошлого замера
            return seeking || moved < 0 || moved - ran > SLACK || moved > 0 && ran - moved > SLACK;
        }

        void reset() {
            sampled = false;
            steady = false;
        }
    }
}
