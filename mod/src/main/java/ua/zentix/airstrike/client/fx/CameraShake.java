package ua.zentix.airstrike.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.airstrike.AirstrikeConfig;

/**
 * Тряска камеры без сдвига прицела: крутится только камера (курс, тангаж, крен), взгляд игрока остаётся на месте —
 * в датапаке трясли настоящий взгляд телепортами, и сбивался прицел. Амплитуды те же, что у датапака.
 */
public final class CameraShake {
    /** Удар взрывной волны: сколько тиков ещё трясёт. */
    private static int shake;
    /** Толчки грунта (подземный взрыв, бурение): медленнее и мягче. */
    private static int quake;
    private static long ticks;
    /** Доля тика последнего кадра, пока мир шёл: в /tick freeze счётчики стоят, и тряска стоит на ней. */
    private static float partial;
    /** Ядерная волна: сколько тиков ещё, сколько всего и размах, °. */
    private static int nuke, nukeTotal;
    private static double nukeAmp;

    private CameraShake() {}

    public static void blast(int ticks) {
        shake = Math.max(shake, ticks);
    }

    public static void quake(int ticks) {
        quake = Math.max(quake, ticks);
    }

    /**
     * Приход ядерной волны: удар и долгое затухающее дрожание. Размах — от давления у игрока
     * (1 psi — 4°, 5 psi — 9°, 20 psi — 14°), длительность — до 10 с.
     */
    public static void nuke(double psi) {
        double amp = Mth.clamp(4 + 7 * Math.log10(Math.max(1, psi)), 2.5, 16);
        if (psi < 1) amp = 2.5 + 1.5 * psi;
        int ticks = (int) Mth.clamp(40 + psi * 12, 40, 200);
        if (amp >= nukeAmp * Math.max(0, (double) nuke / Math.max(1, nukeTotal))) {
            nukeAmp = amp;
            nuke = nukeTotal = ticks;
        }
    }

    public static void tick() {
        ticks++;
        if (shake > 0) shake--;
        if (quake > 0) quake--;
        if (nuke > 0) nuke--;
    }

    public static void reset() {
        shake = quake = nuke = 0;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        double k = AirstrikeConfig.CLIENT.cameraShake.get();
        if (k <= 0 || shake <= 0 && quake <= 0 && nuke <= 0 || Minecraft.getInstance().isPaused()) return;
        // мир остановлен (/tick freeze): счётчики тиков стоят, а доля тика у игры бежит по кругу — без этого
        // тряска в стоп-кадре дёргалась с частотой 20 Гц, возвращаясь каждый тик
        var level = Minecraft.getInstance().level;
        if (level == null || level.tickRateManager().runsNormally()) partial = (float) e.getPartialTick();
        double t = ticks + partial;
        // амплитуды датапака: курс/тангаж, °
        double yaw = 0, pitch = 0;
        if (shake > 0) {
            double[] a = shake >= 26 ? new double[]{5, 3.5} : shake >= 18 ? new double[]{3, 2} : shake >= 8 ? new double[]{1.5, 1} : new double[]{0.6, 0.4};
            double fade = Math.min(1, shake / 4.0);
            yaw += a[0] * fade * wobble(t, 9.7, 0.0);
            pitch += a[1] * fade * wobble(t, 12.3, 1.7);
        }
        if (quake > 0) {
            double[] a = quake >= 50 ? new double[]{1.6, 1.1} : quake >= 24 ? new double[]{0.9, 0.6} : new double[]{0.35, 0.25};
            double fade = Math.min(1, quake / 6.0);
            yaw += a[0] * fade * wobble(t, 5.1, 3.1);
            pitch += a[1] * fade * wobble(t, 6.7, 4.4);
        }
        double roll = 0;
        if (nuke > 0) {
            // первые полсекунды — удар (вдвое сильнее и резче), дальше затухание по экспоненте
            double age = nukeTotal - nuke + partial;
            double env = Math.exp(-age / (nukeTotal * 0.35)) * (age < 10 ? 2 - age / 10 : 1);
            double a = nukeAmp * env;
            yaw += a * wobble(t, 11.3, 0.4);
            pitch += a * 0.8 * wobble(t, 14.1, 2.2);
            roll += a * 0.9 * wobble(t, 8.7, 5.1);
        }
        e.setYaw(e.getYaw() + (float) (yaw * k));
        e.setPitch(e.getPitch() + (float) (pitch * k));
        e.setRoll(e.getRoll() + (float) ((yaw * 0.6 + roll) * k));
    }

    /** Гладкое «дрожание» из двух синусов разной частоты (Гц при 20 тиках/с), амплитуда ~1. */
    private static double wobble(double t, double hz, double phase) {
        double s = t / 20.0;
        return 0.7 * Mth.sin((float) (s * hz * Mth.TWO_PI + phase)) + 0.3 * Mth.sin((float) (s * hz * 1.73 * Mth.TWO_PI + phase * 2.1));
    }
}
