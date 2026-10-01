package ua.zentix.airstrike.client.far;

/**
 * Видимость вдали без Minecraft: дымка по Кошмидеру и яркие точки с сохранением светового потока.
 * <ul>
 * <li>Воздух ослабляет свет как exp(−σ·d), σ = 3,912 / V, где V — дальность видимости: на ней контраст тёмного
 * против неба падает до порога глаза 0,02 (Кошмидер, 1924). Коэффициенты дымки, дождя и грозы складываются.</li>
 * <li>Тёмное (дым, корпус) вдали — того же цвета, что воздушная дымка перед ним: L = L₀·t + Lг·(1 − t).</li>
 * <li>Яркое (вспышка, огненный шар, факел) — свой свет, ослабленный воздухом в t раз; мельче пикселя оно не пропадает,
 * а рисуется точкой с тем же световым потоком, ярче белого экрана — расплывается бликом.</li>
 * </ul>
 */
public final class Sight {
    /** −ln 0,02: порог контраста глаза, по которому определена дальность видимости. */
    public static final double KOSCHMIEDER = 3.912;
    /** Дальность видимости, блоков (= метров): ясно, сильный дождь, гроза. */
    public static final double CLEAR = 20_000, RAIN = 3_000, THUNDER = 1_500;
    /** Меньше этого точка не рисуется: полтора пикселя в поперечнике (свет — тот же, бледнее). */
    public static final double MIN_PIXELS = 1.5;
    /** Доля света меньше этой — не видно (порог Кошмидера). */
    public static final double THRESHOLD = 0.02;

    private Sight() {}

    /** Дальность видимости по погоде мира: дождь и гроза 0..1, как у {@code Level.getRainLevel}/{@code getThunderLevel}. */
    public static double range(float rain, float thunder) {
        double sigma = 1 / CLEAR + rain * (1 / RAIN - 1 / CLEAR) + thunder * (1 / THUNDER - 1 / RAIN);
        return 1 / sigma;
    }

    /** Доля света (и собственного контраста тёмного), дошедшая через d блоков воздуха. */
    public static double transmittance(double d, double range) {
        return Math.exp(-KOSCHMIEDER * d / range);
    }

    /**
     * Как рисовать яркую или маленькую точку: свет — яркость × телесный угол — сохраняется.
     * Крупнее {@link #MIN_PIXELS} и не ярче белого — как есть; мельче — точкой этого размера, бледнее во столько же раз;
     * ярче белого экрана — шире, как блик в глазу или на снимке, а не ярче белого.
     *
     * @param radius     настоящий радиус, блоков
     * @param brightness яркость против белого экрана (тёмному корпусу — 1: тогда это его доля в пикселе)
     * @param t          доля света, дошедшая через воздух ({@link #transmittance}); тёмному — 1
     * @param d          дальность, блоков
     * @param pixel      угол одного пикселя экрана, рад
     * @param out        {радиус для рисования на дальности d, блоков; непрозрачность 0..1}
     */
    public static void point(double radius, double brightness, double t, double d, double pixel, double[] out) {
        double r = radius / Math.max(d, 1e-6);
        double flux = brightness * t * r * r;
        double drawn = Math.max(Math.max(r, 0.5 * MIN_PIXELS * pixel), Math.sqrt(flux));
        out[0] = drawn * d;
        out[1] = Math.min(1, flux / (drawn * drawn));
    }
}
