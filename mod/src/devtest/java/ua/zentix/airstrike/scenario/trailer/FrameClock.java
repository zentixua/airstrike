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

    /** К следующему кадру игра продвинется на {@code ticks} тиков (при нынешней длине тика клиента). */
    static void advanceNext(double ticks) {
        Minecraft mc = Minecraft.getInstance();
        float mspt = Math.max(50f, mc.level == null ? 50f : mc.level.tickRateManager().millisecondsPerTick());
        try {
            LAST_MS.setLong(timer(), Util.getMillis() - Math.round(ticks * mspt));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
