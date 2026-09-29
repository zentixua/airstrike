package ua.zentix.airstrike.scenario.trailer;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;

/**
 * Часы клиента по кадрам видео: пока идёт запись, игра между кадрами продвигается ровно на шаг кадра (1/60 с видео),
 * сколько бы ни рисовался кадр. Доля тика для отрисовки ставится точно, число тиков — через «прошлое время» таймера
 * (сколько миллисекунд якобы прошло с прошлого кадра). Поля таймера закрыты — только для этой записи, отражением.
 */
final class FrameClock {
    private static final Field LAST_MS = field("lastMs");
    private static final Field RESIDUAL = field("deltaTickResidual");

    private FrameClock() {}

    private static Field field(String name) {
        try {
            Field f = DeltaTracker.Timer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DeltaTracker.Timer." + name, e);
        }
    }

    private static DeltaTracker.Timer timer() {
        return (DeltaTracker.Timer) Minecraft.getInstance().getTimer();
    }

    /** Доля тика, с которой будет нарисован этот кадр. */
    static void setPartial(float partial) {
        try {
            RESIDUAL.setFloat(timer(), partial);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Длина тика, которую сценарий только что попросил у сервера (до клиента она доходит пакетом позже). */
    private static volatile float requestedMspt = 50;

    static void requestTickRate(float rate) {
        requestedMspt = 1000f / rate;
    }

    /**
     * К следующему кадру игра продвинется на {@code ticks} тиков (при нынешней длине тика клиента). Недобор безопасен
     * (кадр не снимается, игра доходит до момента на следующем круге), перебор — нет: клиент уходит вперёд кадра,
     * и кадры до следующего тика стоят. Поэтому длина тика — меньшая из той, что знает клиент, и той, что запрошена
     * у сервера (пока пакет с новым темпом не дошёл, таймер делит на одну, а мы умножали на другую — в дубле 1 так
     * клиент обгонял кадр на тик в планах с замедлением), и миллисекунды — с округлением вниз.
     */
    static void advanceNext(double ticks) {
        Minecraft mc = Minecraft.getInstance();
        boolean normal = mc.level != null && mc.level.tickRateManager().runsNormally();
        // таймер клиента на стоящем мире считает тик за 50 мс (Minecraft.getTickTargetMillis)
        float mspt = normal ? Math.max(50f, Math.min(mc.level.tickRateManager().millisecondsPerTick(), requestedMspt)) : 50f;
        try {
            LAST_MS.setLong(timer(), Util.getMillis() - (long) Math.floor(ticks * mspt));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
