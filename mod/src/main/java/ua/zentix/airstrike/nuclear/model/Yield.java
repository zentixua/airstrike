package ua.zentix.airstrike.nuclear.model;

/** Пресеты мощности (килотонны) с ключом перевода и оптимальная высота воздушного подрыва. */
public enum Yield {
    TACTICAL(1, "tactical"),
    HIROSHIMA(15, "hiroshima"),
    W76(100, "w76"),
    W87(300, "w87"),
    MEGATON(1000, "megaton"),
    MEGATON_10(10000, "megaton_10");

    private final double kt;
    private final String key;

    Yield(double kt, String name) {
        this.kt = kt;
        this.key = "airstrike.yield." + name;
    }

    public double kt() {
        return kt;
    }

    /** Ключ перевода, например {@code airstrike.yield.hiroshima}. */
    public String key() {
        return key;
    }

    /** Высота подрыва с наибольшей площадью 5 psi, м: 240·Y^(1/3) (15 кт → ~590 м, 1 Мт → 2.4 км). */
    public static double optimalBurstHeight(double yieldKt) {
        return 240 * Math.cbrt(yieldKt);
    }
}
