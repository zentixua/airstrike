package ua.zentix.airstrike.client.fx;

/**
 * Сила вспышки взрыва на экране (0..1) — как её видит глаз, без Minecraft, чтобы кривую можно было проверить тестом.
 * <ul>
 *   <li>Освещённость от точечного источника падает как 1/d² (закон обратных квадратов), ближе {@code near}
 *       (огненный шар и то, что он заливает светом) — предельная.</li>
 *   <li>Засветка на весь экран — это блеск: вуаль, которую яркий источник рассеивает в глазу. По формуле
 *       Стайлса–Холладея (CIE, слепящий блеск) её яркость пропорциональна освещённости у глаза и падает с углом между
 *       взглядом и источником как 1/θ². Воспринимаемая яркость — степенная функция Стивенса с показателем 0.5 для
 *       точечного источника, поэтому на экране вуаль спадает как near/d, а не как 1/d².</li>
 *   <li>Взрыв вне поля зрения не слепит: остаётся отсвет на окружении ({@link #AMBIENT}); за преградой — только он
 *       и слабее ({@link #OCCLUDED}).</li>
 *   <li>К {@code range} сила плавно доходит до нуля, без скачка на краю.</li>
 * </ul>
 * Ядерная вспышка — отдельная ({@code client/nuclear/NukeFlash}): она и должна ослеплять.
 */
public final class FlashFalloff {
    /** Сила вблизи, лицом к взрыву. */
    static final float PEAK = 0.85f;
    /** Доля силы от взрыва за спиной при прямой видимости: свет, отражённый окружением. */
    static final float AMBIENT = 0.5f;
    /** Доля силы от взрыва за преградой. */
    static final float OCCLUDED = 0.35f;

    private FlashFalloff() {}

    /**
     * @param d       расстояние от глаз до взрыва, блоки
     * @param near    ближе этого — полная сила (радиус огненного шара с запасом), блоки
     * @param range   дальше этого вспышки нет, блоки
     * @param cosView косинус угла между взглядом и направлением на взрыв
     * @param visible взрыв виден напрямую
     */
    public static float strength(double d, double near, double range, double cosView, boolean visible) {
        if (d >= range) return 0;
        double distance = Math.min(1, near / Math.max(d, 1e-6));
        double r = d / range;
        double edge = 1 - r * r * r * r;
        double facing = Math.max(0, cosView);
        double view = visible ? AMBIENT + (1 - AMBIENT) * facing * facing : OCCLUDED;
        return (float) (PEAK * distance * edge * view);
    }
}
