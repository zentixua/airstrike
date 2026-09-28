package ua.zentix.airstrike.nuclear.model;

/**
 * Радиоактивный след одного подрыва: формула от (x, z, время), по блокам ничего не хранится.
 * x, z — метры от эпицентра по осям мира; ветер дует в сторону угла {@code windDirRad}
 * (вектор (cos, sin) в плоскости x–z).
 *
 * <p>Мощность дозы в H+1 (Р/ч) — сумма двух гауссовых пятен:
 * <ul>
 *   <li>эпицентр: {@code 0.5·B·exp(−r²/(2·(0.6·R_ш)²))};</li>
 *   <li>след по ветру: осевая {@code B·(1 + x/(x0·S))^−n}, поперёк — гаусс с σ = 0.1·x + R_ш,
 *       против ветра — гаусс с σ = R_ш.</li>
 * </ul>
 * R_ш — максимальный радиус шара наземного взрыва; S = (Y/15)^0.45·(ветер/ветер₀) растягивает след,
 * множитель ветер₀/ветер разбавляет его. Подбор B = 10⁵ Р/ч, x0 = 500 м, n = 2.1 даёт для 15 кт
 * наземного подрыва при ветре 24 км/ч осевые контуры 1000 / 100 / 10 Р/ч на 4 / 13 / 40 км —
 * порядок контуров NUKEMAP (модель Миллера) для этого случая.
 * Всё умножается на долю наземного подрыва {@link #surfaceFraction()}.
 *
 * <p>Время: осадки приходят в точку через (путь по ветру)/(скорость ветра) реальных секунд,
 * а спад идёт по игровым часам (закон Уэя–Вигнера t^−1.2, правило 7–10) — см. {@link #doseRate}.
 */
public record FalloutModel(double yieldKt, double hobM, double windDirRad, double windSpeedMps) {
    /** Мощность дозы на оси следа у эпицентра в H+1, Р/ч. */
    public static final double PEAK_H1 = 1e5;
    /** Масштаб спада вдоль оси, м (при 15 кт и опорном ветре). */
    public static final double TRAIL_SCALE_M = 500;
    /** Показатель спада вдоль оси. */
    public static final double TRAIL_EXP = 2.1;
    /** Опорный ветер подбора: 24 км/ч. */
    public static final double REFERENCE_WIND = 24 / 3.6;
    /** Пятно эпицентра: доля от PEAK_H1 и радиус в долях R_ш. */
    private static final double GZ_PEAK = 0.5;
    private static final double GZ_RADIUS = 0.6;
    /** Раньше этого (игровые часы) спад не считается, иначе t^−1.2 уходит в бесконечность. */
    public static final double MIN_DECAY_HOURS = 0.5;
    /** Закон спада Уэя–Вигнера. */
    public static final double DECAY_EXP = 1.2;
    private static final double MIN_WIND = 0.5;

    /**
     * Доля наземного подрыва 0..1: 0, если шар не касается земли (HOB ≥ максимального радиуса
     * воздушного шара), к касанию растёт как (1 − HOB/R)².
     */
    public double surfaceFraction() {
        double u = Solve.clamp(1 - hobM / FireballModel.maxRadius(yieldKt, false), 0, 1);
        return u * u;
    }

    /** Мощность дозы в H+1 в точке (x, z), Р/ч — без учёта времени прихода. */
    public double doseRateAtH1(double x, double z) {
        double frac = surfaceFraction();
        if (frac <= 0) return 0;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double rf = FireballModel.maxRadius(yieldKt, true);
        double wind = wind();
        double stretch = Math.pow(yieldKt / 15, 0.45) * wind / REFERENCE_WIND;
        double amp = PEAK_H1 * frac * REFERENCE_WIND / wind;

        double along = down >= 0
                ? Math.pow(1 + down / (TRAIL_SCALE_M * stretch), -TRAIL_EXP)
                : Math.exp(-down * down / (2 * rf * rf));
        double sigma = 0.1 * Math.max(down, 0) + rf;
        double trail = along * Math.exp(-cross * cross / (2 * sigma * sigma));

        double gzSigma = GZ_RADIUS * rf;
        double gz = GZ_PEAK * Math.exp(-(x * x + z * z) / (2 * gzSigma * gzSigma));
        return amp * (trail + gz);
    }

    /** Через сколько реальных секунд после подрыва осадки ложатся в (x, z): путь по ветру / скорость; против ветра — 0. */
    public double arrivalSeconds(double x, double z) {
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        return Math.max(down, 0) / wind();
    }

    /**
     * Мощность дозы, Р/ч. {@code secondsSinceDetonation} — реальные секунды (для прихода осадков),
     * {@code gameHoursSinceDetonation} — игровые часы (1000 тиков) для спада: Ḋ = Ḋ₁·t^−1.2,
     * t не меньше {@link #MIN_DECAY_HOURS}. До прихода осадков — 0.
     */
    public double doseRate(double x, double z, double secondsSinceDetonation, double gameHoursSinceDetonation) {
        if (secondsSinceDetonation < arrivalSeconds(x, z)) return 0;
        double t = Math.max(gameHoursSinceDetonation, MIN_DECAY_HOURS);
        return doseRateAtH1(x, z) * Math.pow(t, -DECAY_EXP);
    }

    private double wind() {
        return Math.max(windSpeedMps, MIN_WIND);
    }
}
