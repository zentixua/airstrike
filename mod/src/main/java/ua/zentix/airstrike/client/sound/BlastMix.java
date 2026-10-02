package ua.zentix.airstrike.client.sound;

import ua.zentix.airstrike.net.S2C;

import java.util.SplittableRandom;

/**
 * Что звучит от взрыва на расстоянии d и как громко (без Minecraft, проверяется юнит-тестом).
 * <p>
 * У взрыва три ракурса одной и той же записи ({@code tools/build_sounds.py}, раздел blasts): вблизи — удар и тело
 * взрыва, на средней дистанции — запись целиком с эхом от склонов и домов, вдали — она же через километр воздуха,
 * без верхов. Вариант у всех ракурсов один (его выбирает зерно взрыва), поэтому в переходе по расстоянию ракурсы
 * складываются как одна запись: веса по амплитуде, в сумме 1. У РСЗО вблизи своя короткая запись, другая, чем дальний
 * ракурс, — их переход по мощности (сумма квадратов весов 1). Под ними — «удар в грудь» снизу (вблизи) и эхо вокруг
 * (стерео, без места в мире: отражения приходят со всех сторон).
 * <p>
 * Громкость: до {@link Profile#flat} блоков полная, дальше −3 дБ на удвоение расстояния — тот же закон, что у дальней
 * модели {@link Outdoor} (слух сжимает громкость), поэтому на её краю ({@link Outdoor#NEAR}) нет ни ступеньки, ни
 * излома: громкость дальнего ракурса там — {@link #v640}.
 */
public final class BlastMix {
    /** Громкость файлов ракурсов, LUFS (наибольшая мгновенная) — те же числа, что в {@code tools/build_sounds.py}. */
    static final double NEAR_LUFS = -13.5, MID_LUFS = -13.5, FAR_LUFS = -15, TAIL_LUFS = -15, ROCKET_LUFS = -16;
    /**
     * Эхо тише прямого звука вблизи и с расстояния, дБ: прямой звук слабеет с расстоянием быстрее отражённого;
     * переход — от {@link #ECHO_FROM} до {@link #ECHO_OPEN} блоков (× масштаб).
     */
    static final double ECHO_CLOSE = -8, ECHO_FAR = -3, ECHO_FROM = 30, ECHO_OPEN = 150;
    /** Разброс тона от взрыва к взрыву: ± доля, по зерну. */
    static final double JITTER = 0.03;

    /**
     * Звук взрыва одного вида.
     *
     * @param scale масштаб расстояний: больше заряд — те же ракурсы дальше
     * @param pitch тон всех слоёв
     * @param lufs  громкость ближнего ракурса, LUFS
     * @param mid   есть средний ракурс (у РСЗО своя короткая запись звучит до дальнего)
     * @param sub   «удар в грудь», доля
     * @param echo  эхо вокруг, доля
     */
    public record Profile(double scale, float pitch, double lufs, boolean mid, double sub, double echo) {
        /** До стольких блоков громкость полная. */
        public double flat() {
            return 40 * scale;
        }
    }

    /** Шахед и «Ланцет» (~50 кг). */
    public static final Profile DRONE = new Profile(1, 1, NEAR_LUFS, true, 1, 1);
    /** Крылатая ракета (~450 кг): ниже тоном, полная громкость дальше, эха больше. */
    public static final Profile MISSILE = new Profile(1.5, 0.92f, NEAR_LUFS, true, 1, 1.4);
    /** Снаряд РСЗО (~20 кг): свой сухой разрыв, выше тоном, низа и эха меньше. */
    public static final Profile ROCKET = new Profile(0.7, 1.12f, ROCKET_LUFS, false, 0.5, 0.5);

    /** Громкость слоёв (0..1, как у звука Minecraft). */
    public record Layers(float near, float mid, float far, float sub, float echo) {}

    private BlastMix() {}

    /** Профиль по виду пакета {@code S2C.Blast}; у бетонобойной бомбы свои звуки — {@code null}. */
    public static Profile of(int kind) {
        return switch (kind) {
            case S2C.Blast.MISSILE -> MISSILE;
            case S2C.Blast.ROCKET -> ROCKET;
            case S2C.Blast.BUNKER -> null;
            default -> DRONE;
        };
    }

    /** Громкость прямого звука взрыва у уха, LUFS: до {@link Profile#flat} — ближний ракурс, дальше −3 дБ на удвоение. */
    public static double loudness(Profile p, double d) {
        return p.lufs - 10 * Math.log10(Math.max(d, p.flat()) / p.flat());
    }

    /** Громкость дальнего ракурса на краю ближней модели ({@link Outdoor#NEAR}): с неё продолжается {@link Outdoor}. */
    public static float v640(Profile p) {
        return gain(loudness(p, Outdoor.NEAR) - FAR_LUFS);
    }

    /** Слои на расстоянии d блоков. */
    public static Layers at(Profile p, double d) {
        double s = p.scale, l = loudness(p, d);
        double far = smoothstep(200 * s, Math.min(500 * s, 600), d);
        double near = p.mid ? 1 - smoothstep(30 * s, 120 * s, d) : Math.sqrt(1 - far);
        double mid = p.mid ? 1 - near - far : 0;
        if (!p.mid) far = Math.sqrt(far);
        double sub = p.sub * (1 - smoothstep(15 * s, 100 * s, d));
        double echo = ECHO_CLOSE + (ECHO_FAR - ECHO_CLOSE) * clamp(log2(d / (ECHO_FROM * s)) / log2(ECHO_OPEN / ECHO_FROM), 0, 1);
        double echoFade = 1 - smoothstep(300 * s, 600, d);
        return new Layers(
                (float) near * gain(l - p.lufs),
                (float) mid * gain(l - MID_LUFS),
                (float) far * gain(l - FAR_LUFS),
                (float) sub,
                (float) (p.echo * echoFade) * gain(l + echo - TAIL_LUFS));
    }

    /** Тон этого взрыва: тон вида ± {@link #JITTER} по зерну — очередь разрывов не звучит одним и тем же звуком. */
    public static float pitch(Profile p, long seed) {
        return (float) (p.pitch * (1 + JITTER * (2 * new SplittableRandom(seed).nextDouble() - 1)));
    }

    /** Множитель громкости для разницы db, не больше 1 (громче файла Minecraft не играет). */
    static float gain(double db) {
        return (float) Math.min(1, Math.pow(10, db / 20));
    }

    private static double log2(double x) {
        return Math.log(Math.max(x, 1e-9)) / Math.log(2);
    }

    private static double clamp(double x, double a, double b) {
        return Math.max(a, Math.min(b, x));
    }

    private static double smoothstep(double a, double b, double x) {
        double t = clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
