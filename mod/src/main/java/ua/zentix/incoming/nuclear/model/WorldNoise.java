package ua.zentix.incoming.nuclear.model;

import ua.zentix.incoming.util.noise.FastNoiseLite;

/**
 * Гладкое случайное поле местности для кривых поражения ({@link Fragility}): почему у одного склона трава выгорела, а
 * у соседнего нет (влажность, густота, торф, ветер). OpenSimplex2 fBm (4 октавы: пятна от ~90 м до ~10 м) с доменным
 * искажением (края пятен рваные, не круглые), приведённое к среднему 0 и разбросу 1 — как {@code z ~ N(0, 1)}.
 * Координаты — блоки мира, размер пятен — в метрах модели ({@code blocksPerMetre}), но крупные — не меньше
 * {@link #MIN_PATCH_BLOCKS} блоков: в малом масштабе удара пятна иначе мельчали бы до зерна. Поле зависит только от зерна и
 * места: план руин чанка одинаков в любом порядке и в любом потоке. Экземпляр после создания только читается
 * (FastNoiseLite без изменяемого состояния при чтении) — общий для потоков руин.
 */
public final class WorldNoise {
    /** Разброс fBm OpenSimplex2 (4 октавы, усиление 0.5) после искажения — по замеру ({@code WorldNoiseTest}). */
    static final double STD = 0.333;
    /** Размер самых крупных пятен, м. */
    static final double PATCH_M = 90;
    /** Крупные пятна — не меньше стольких блоков. */
    static final double MIN_PATCH_BLOCKS = 12;
    /** Искажение: сдвиг до ~30 м, волна ~150 м. */
    private static final double WARP_M = 30, WARP_WAVE_M = 150;

    private final FastNoiseLite noise = new FastNoiseLite(), warp = new FastNoiseLite();
    private final double metresPerBlock;

    public WorldNoise(long seed, double blocksPerMetre) {
        this.metresPerBlock = Math.min(1 / blocksPerMetre, PATCH_M / MIN_PATCH_BLOCKS);
        int s = (int) (seed ^ seed >>> 32);
        noise.SetSeed(s);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency((float) (1 / PATCH_M));
        noise.SetFractalType(FastNoiseLite.FractalType.FBm);
        noise.SetFractalOctaves(4);
        noise.SetFractalLacunarity(2f);
        noise.SetFractalGain(0.5f);
        warp.SetSeed(s * 31 + 7);
        warp.SetDomainWarpType(FastNoiseLite.DomainWarpType.OpenSimplex2);
        warp.SetDomainWarpAmp((float) WARP_M);
        warp.SetFrequency((float) (1 / WARP_WAVE_M));
    }

    /** Поле в месте мира (x, z — блоки), ~N(0, 1); {@code layer} — независимый слой (разные последствия — разные пятна). */
    public double at(double x, double z, int layer) {
        FastNoiseLite.Vector2 p = new FastNoiseLite.Vector2(x * metresPerBlock + layer * 7919.0, z * metresPerBlock - layer * 6271.0);
        warp.DomainWarp(p);
        return noise.GetNoise(p.x, p.y) / STD;
    }
}
