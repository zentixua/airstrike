package ua.zentix.incoming.nuclear.model;

/**
 * Свет чёрного тела в единицах кадра: линейный sRGB, белая стена под полуденным солнцем — 1. Закон Планка по видимому
 * спектру, функции сложения цветов CIE 1931 — многолепестковое приближение Wyman, Sloan, Shirley (2013), как у кадров
 * огненного шара ({@code tools/gen_particles.py}). Яркость — от солнца: чёрное тело 5 800 K — диск солнца, в
 * {@link #SUN_DISK} раз ярче белой стены (1,6·10⁹ кд/м² против E/π ≈ 3,5·10⁴ кд/м² у стены под солнцем и небом).
 */
public final class BlackBody {
    /** Диск солнца против белой стены в полдень. */
    public static final double SUN_DISK = 46_200;
    /** Температура солнца как чёрного тела, K. */
    static final double SUN_K = 5_800;
    /** Длины волн, нм: 380–780 через 2. */
    private static final double FROM_NM = 380, TO_NM = 780, STEP_NM = 2;
    /** Вторая радиационная постоянная c₂ = hc/k, м·K. */
    private static final double C2 = 1.4388e-2;
    /** XYZ → линейный sRGB (D65). */
    private static final double[][] TO_RGB = {{3.2406, -1.5372, -0.4986}, {-0.9689, 1.8758, 0.0415}, {0.0557, -0.2040, 1.0570}};
    private static final double SUN_Y = xyz(SUN_K)[1];

    private BlackBody() {}

    /**
     * Свет чёрного тела температуры {@code kelvin} в единицах кадра: r, g, b линейного sRGB (без отрицательных) и яркость Y
     * — в {@code out} (4 числа). Цвет вне охвата sRGB срезан по каналам, яркость — настоящая.
     */
    public static void light(double kelvin, double[] out) {
        double[] xyz = xyz(kelvin);
        double k = SUN_DISK / SUN_Y;
        for (int i = 0; i < 3; i++) {
            double c = TO_RGB[i][0] * xyz[0] + TO_RGB[i][1] * xyz[1] + TO_RGB[i][2] * xyz[2];
            out[i] = Math.max(0, c) * k;
        }
        out[3] = xyz[1] * k;
    }

    /** Яркость чёрного тела в единицах кадра (белая стена в полдень — 1). */
    public static double luminance(double kelvin) {
        return xyz(kelvin)[1] * SUN_DISK / SUN_Y;
    }

    /** XYZ чёрного тела (в своих единицах: Планк без постоянных перед дробью, сумма по 2 нм). */
    private static double[] xyz(double kelvin) {
        double x = 0, y = 0, z = 0;
        for (double nm = FROM_NM; nm <= TO_NM + 1e-9; nm += STEP_NM) {
            double lam = nm * 1e-9;
            double b = 1 / (Math.pow(lam, 5) * Math.expm1(C2 / (lam * kelvin)));
            x += b * (1.056 * lobe(nm, 599.8, 37.9, 31.0) + 0.362 * lobe(nm, 442.0, 16.0, 26.7) - 0.065 * lobe(nm, 501.1, 20.4, 26.2));
            y += b * (0.821 * lobe(nm, 568.8, 46.9, 40.5) + 0.286 * lobe(nm, 530.9, 16.3, 31.1));
            z += b * (1.217 * lobe(nm, 437.0, 11.8, 36.0) + 0.681 * lobe(nm, 459.0, 26.0, 13.8));
        }
        return new double[]{x, y, z};
    }

    /** Лепесток приближения CIE: гаусс с разной шириной слева и справа. */
    private static double lobe(double nm, double mu, double left, double right) {
        double s = nm < mu ? left : right, u = (nm - mu) / s;
        return Math.exp(-0.5 * u * u);
    }
}
