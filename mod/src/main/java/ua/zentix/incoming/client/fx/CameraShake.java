package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.incoming.IncomingConfig;

/**
 * Тряска камеры без сдвига прицела: крутится только камера (курс, тангаж, крен), взгляд игрока остаётся на месте —
 * тряска самого взгляда сбивала бы прицел.
 * <p>
 * Глаз видит скорость поворота: размах A° на частоте f — до 2π·f·A °/с (на каждом кадре — её доля). Поэтому дрожь
 * земли и ядерная волна, у которых размах — градусы, медленные, как у тела: голову качает само тело (ниже ~6 Гц), а
 * частую дрожь земли оно до глаз почти не доносит. Ядерная волна — толчок от взрыва (тело отклоняется от напора) и порывы
 * ветра за фронтом, а не частое дёрганье: десятки градусов десять раз в секунду — тысячи градусов в секунду, на экране —
 * мельтешение вместо удара.
 */
public final class CameraShake {
    /** Удар взрывной волны: сколько тиков ещё трясёт. */
    private static int shake;
    /** Толчки грунта (подземный взрыв, бурение): медленнее и мягче. */
    private static int quake;
    private static long ticks;
    /** Ядерная волна: тиков ещё и всего, размах толчка, ° и куда толкает напор (от взрыва, единичный). */
    private static int nuke, nukeTotal;
    private static double nukeAmp, pushX, pushY, pushZ;
    /**
     * Толчок волны доходит до наибольшего за столько тиков и гладко отпускает (тело отклонилось и выпрямилось за
     * полсекунды): фаза сжатия у килотонн в сотнях метров — десятые доли секунды.
     */
    static final double JOLT_TICKS = 3;
    /** Порывы ветра за фронтом: размах — эта доля толчка. */
    static final double BUFFET_SHARE = 0.35;
    /** Подходящая ядерная волна: размах в этот тик, °. */
    private static double approach;
    /** Перед приходом волны размах дрожи доходит до этой доли удара. */
    static final double APPROACH_SHARE = 0.45;
    /** Толчок грунта от наземного ядерного взрыва: сколько тиков ещё, сколько всего и размах, °. */
    private static int seismic, seismicTotal;
    private static double seismicAmp;
    /** Скорость толчка в грунте, м/с (продольная волна в наносах — 1–2 км/с): приходит раньше волны в воздухе. */
    public static final double SEISMIC_SPEED = 1500;
    /** Слабее стольких градусов толчок не ощущается. */
    static final double SEISMIC_MIN = 0.3;

    private CameraShake() {}

    public static void blast(int ticks) {
        shake = Math.max(shake, ticks);
    }

    public static void quake(int ticks) {
        quake = Math.max(quake, ticks);
    }

    /**
     * Приход ядерной волны: толчок от взрыва и порывы ветра, которые гаснут до 10 с. Размах — от давления у игрока
     * ({@link #nukeAmplitude}); {@code away} — от взрыва к игроку (куда толкает напор).
     */
    public static void nuke(double psi, Vec3 away) {
        double amp = nukeAmplitude(psi);
        int ticks = (int) Mth.clamp(40 + psi * 12, 40, 200);
        if (amp >= nukeAmp * Math.max(0, (double) nuke / Math.max(1, nukeTotal))) {
            nukeAmp = amp;
            nuke = nukeTotal = ticks;
            Vec3 n = away.lengthSqr() > 1e-9 ? away.normalize() : new Vec3(0, -1, 0);
            pushX = n.x;
            pushY = n.y;
            pushZ = n.z;
        }
    }

    /** Размах толчка ядерной волны, °: 1 psi — 4°, 5 psi — 9°, 20 psi — 14°, слабее 1 psi — от 2,5°. */
    static double nukeAmplitude(double psi) {
        return psi < 1 ? 2.5 + 1.5 * Math.max(0, psi) : Mth.clamp(4 + 7 * Math.log10(psi), 2.5, 16);
    }

    /** За сколько тиков до прихода ядерной волны начинает дрожать земля: 3 с при 1 psi, 10 с — с 7 psi. */
    public static double approachTicks(double psi) {
        return Mth.clamp(60 + 20 * psi, 60, 200);
    }

    /**
     * Размах дрожи, пока ядерная волна подходит, °, за {@code eta} тиков до неё: с {@link #approachTicks} — от нуля, к её
     * приходу — {@link #APPROACH_SHARE} удара, по квадрату (всё сильнее и сильнее); слабее 0,5 psi не трясёт.
     */
    static double approachAmplitude(double psi, double eta) {
        double total = approachTicks(psi);
        if (psi < 0.5 || eta >= total) return 0;
        double f = 1 - Math.max(0, eta) / total;
        return nukeAmplitude(psi) * APPROACH_SHARE * f * f;
    }

    /**
     * Ядерная волна давлением psi у игрока придёт через {@code eta} тиков: в этот тик земля дрожит ({@link #approachAmplitude}).
     * Толчок грунта бежит быстрее волны в воздухе, а стену пыли видно издалека — дрожь нарастает, пока она подходит.
     */
    public static void approach(double psi, double eta) {
        approach = Math.max(approach, approachAmplitude(psi, eta));
    }

    /**
     * Толчок грунта от наземного ядерного взрыва: резкий удар снизу и дрожь на 1,5–3 с, раньше волны в воздухе
     * ({@link #seismicTicks}). Размах — {@link #seismicAmplitude}.
     */
    public static void seismic(double deg) {
        if (deg < SEISMIC_MIN) return;
        int t = (int) Mth.clamp(30 + deg * 6, 30, 66);
        if (deg >= seismicAmp * Math.max(0, (double) seismic / Math.max(1, seismicTotal))) {
            seismicAmp = deg;
            seismic = seismicTotal = t;
        }
    }

    /**
     * Размах толчка грунта, °, на {@code metres} от наземного взрыва {@code kt} килотонн: по приведённой дальности
     * Z = R / W^⅓ (как давление волны) — 3° на 500 м/кт^⅓, спадает как Z^−1,3, не больше 6°; слабее
     * {@link #SEISMIC_MIN} — 0. У 15 кт: 1 км — 4°, 3 км — 1°, 5 км — 0,5°, дальше 7 км не ощущается.
     */
    public static double seismicAmplitude(double metres, double kt) {
        double z = Math.max(1, metres) / Math.cbrt(Math.max(1e-3, kt));
        double a = Math.min(6, 3 * Math.pow(z / 500, -1.3));
        return a < SEISMIC_MIN ? 0 : a;
    }

    /** Через сколько тиков после подрыва толчок грунта дойдёт до {@code metres} (масштаб удара — блоков на метр). */
    public static double seismicTicks(double metres, double scale) {
        return metres / SEISMIC_SPEED * 20 * scale;
    }

    public static void tick() {
        ticks++;
        approach = 0;
        if (seismic > 0) seismic--;
        if (shake > 0) shake--;
        if (quake > 0) quake--;
        if (nuke > 0) nuke--;
    }

    public static void reset() {
        shake = quake = nuke = seismic = 0;
        approach = 0;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        double k = IncomingConfig.CLIENT.cameraShake.get();
        if (k <= 0 || shake <= 0 && quake <= 0 && nuke <= 0 && seismic <= 0 && approach <= 0 || Minecraft.getInstance().isPaused()) return;
        double[] o = offsets(e.getPartialTick(), e.getYaw());
        e.setYaw(e.getYaw() + (float) (o[0] * k));
        e.setPitch(e.getPitch() + (float) (o[1] * k));
        e.setRoll(e.getRoll() + (float) (o[2] * k));
    }

    /**
     * Поворот камеры тряской в кадре, ° (курс, тангаж — вниз больше нуля, крен — верх вправо больше нуля), при курсе
     * камеры {@code cameraYaw}.
     */
    static double[] offsets(double partial, double cameraYaw) {
        double t = ticks + partial;
        // амплитуды: курс/тангаж, °
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
        double roll = yaw * 0.6;
        if (approach > 0) {
            // земля дрожит под ногами: мельче удара, с креном
            yaw += approach * wobble(t, 3.7, 0.9);
            pitch += approach * 0.9 * wobble(t, 4.6, 2.6);
            roll += approach * 0.5 * wobble(t, 3.0, 4.0);
        }
        if (seismic > 0) {
            // толчок снизу: резкий, больше тангажом и креном, гаснет за пару секунд
            double age = seismicTotal - seismic + partial;
            double a = seismicAmp * Math.exp(-age / (seismicTotal * 0.3)) * Math.min(1, age / 2);
            yaw += a * 0.5 * wobble(t, 4.6, 1.9);
            pitch += a * wobble(t, 5.4, 0.6);
            roll += a * 0.8 * wobble(t, 4.0, 3.3);
        }
        if (nuke > 0) {
            double age = nukeTotal - nuke + partial;
            // толчок: тело отклоняется от взрыва — напор в лицо запрокидывает голову, в спину и сверху клонит вниз, сбоку
            // наклоняет от взрыва; к наибольшему за JOLT_TICKS без скачка скорости и гладко обратно
            double u = age / JOLT_TICKS, jolt = nukeAmp * u * u * Math.exp(2 * (1 - u));
            double r = Math.toRadians(cameraYaw), sin = Math.sin(r), cos = Math.cos(r);
            double ahead = -sin * pushX + cos * pushZ, right = -cos * pushX - sin * pushZ;
            pitch += jolt * Mth.clamp(ahead - pushY, -1, 1);
            roll += jolt * right;
            // порывы ветра за фронтом: медленнее и мельче толчка, гаснут за время волны
            double a = nukeAmp * BUFFET_SHARE * Math.exp(-age / (nukeTotal * 0.35));
            yaw += a * wobble(t, 2.4, 0.4);
            pitch += a * 0.8 * wobble(t, 3.3, 2.2);
            roll += a * 0.9 * wobble(t, 2.0, 5.1);
        }
        return new double[]{yaw, pitch, roll};
    }

    /** Гладкое «дрожание» из двух синусов разной частоты (Гц при 20 тиках/с), амплитуда ~1. */
    private static double wobble(double t, double hz, double phase) {
        double s = t / 20.0;
        return 0.7 * Mth.sin((float) (s * hz * Mth.TWO_PI + phase)) + 0.3 * Mth.sin((float) (s * hz * 1.73 * Mth.TWO_PI + phase * 2.1));
    }
}
