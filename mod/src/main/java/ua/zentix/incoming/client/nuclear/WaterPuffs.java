package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Клубы подрыва на воде (DESIGN-nuke §6, «Вода»; «Бейкер», Crossroads 1946): полый столб воды с шапкой брызг, кольцо
 * тумана — облако конденсации, которое бубликом ложится на воду за фронтом в первые секунды, — и базисная волна: туман
 * упавшей воды столба, что катится от подрыва кольцом минутами. Те же клубы, что у гриба ({@link CloudPuffs}): постоянные
 * случайные параметры по сиду подрыва, место и вид в каждый момент — по {@link WaterModel}. Метры модели → блоки через
 * {@link Detonation#blocks}; высота — от поверхности воды под эпицентром.
 */
final class WaterPuffs {
    enum Kind { COLUMN, MIST, SURGE }

    private record Puff(Kind kind, float a, float b, float c, float size, int tex, float rot, float spin, float shade) {}

    /** Вода в брызгах и тумане: белая с серым, базисная волна чуть темнее (в ней капли и пар со взвесью). */
    static final int SPRAY = 0xE8EBED, MIST = 0xF0F2F4, SURGE = 0xDCE0E3;
    /** Доли клубов: столб, кольцо тумана, базисная волна (остальное). */
    private static final float COLUMN_SHARE = 0.3f, MIST_SHARE = 0.25f;
    /** Верхние клубы столба (эта доля) — шапка брызг шире столба. */
    private static final float CROWN = 0.8f;
    /** Под это число клубов подобраны размеры (как у гриба). */
    private static final double REFERENCE_PUFFS = 600;

    private final Detonation d;
    private final Puff[] puffs;
    private final int surgeCount;
    private final double detail;
    /** Насколько подрыв «водяной» 0..1 ({@link WaterModel#wetBurst} × вода под эпицентром). */
    private double wet;
    /** Откуда известна вода под эпицентром; null — ещё решается ({@code NukeWater#tick}). */
    private NukeWater.Source source;

    /**
     * Сколько клубов у подрыва: три четверти клубов гриба по настройке качества; у высокого воздушного подрыва — ни
     * одного (шар воды не касается, {@link WaterModel#wetBurst}).
     */
    static int count(Detonation d) {
        if (WaterModel.wetBurst(d.hobMetres(), d.yieldKt()) <= 0) return 0;
        return IncomingConfig.CLIENT.nukeCloudQuality.get().puffs * 3 / 4;
    }

    WaterPuffs(Detonation d, int n) {
        this.d = d;
        RandomSource r = RandomSource.create(d.seed() ^ 0x5EA5_1DE5L);
        puffs = new Puff[n];
        int column = Math.round(n * COLUMN_SHARE), mist = Math.round(n * MIST_SHARE);
        for (int i = 0; i < n; i++) {
            Kind k = i < column ? Kind.COLUMN : i < column + mist ? Kind.MIST : Kind.SURGE;
            puffs[i] = new Puff(k, r.nextFloat(), r.nextFloat(), r.nextFloat(), 0.7f + 0.6f * r.nextFloat(), r.nextInt(8),
                    r.nextFloat() * Mth.TWO_PI, (r.nextFloat() - 0.5f) * 0.02f, 0.85f + 0.3f * r.nextFloat());
        }
        surgeCount = Math.max(1, n - column - mist);
        detail = Math.cbrt(REFERENCE_PUFFS / Math.max(1, n));
    }

    void wet(double wet) {
        this.wet = wet;
    }

    double wet() {
        return wet;
    }

    /** Вода под эпицентром решена: дальше не меняется (столб не появляется и не пропадает посреди подрыва). */
    void settle(NukeWater.Source source) {
        this.source = source;
    }

    boolean settled() {
        return source != null;
    }

    /** Откуда известна вода под эпицентром (для строки сценария); «?» — ещё решается. */
    String source() {
        return source == null ? "?" : source.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Клуб в момент: место от эпицентра (высота — над водой) и размер, м; непрозрачность, цвет, свет шара на нём. */
    private static final class Spot {
        double px, py, pz, size, a, shade, heat;
        int rgb;
    }

    /** Общее для клубов в момент t (секунды модели). */
    private static final class Moment {
        double t, top, fall, colR, wall, mistR, mistEnv, surgeR, surgeH, surgeA, fireball, windX, windZ;
        int fireballRgb;
    }

    private Moment moment(double t) {
        double y = d.yieldKt();
        Moment m = new Moment();
        m.t = t;
        m.top = WaterModel.columnTop(t, y);
        m.fall = WaterModel.columnFall(t, y);
        m.colR = WaterModel.columnRadius(y) * (1 + 0.6 * m.fall);
        m.wall = WaterModel.columnWall(y);
        m.mistEnv = WaterModel.mistRing(t, y);
        double front = d.metres(d.frontRadius(t * 20 * d.scale())), hob = Math.max(0, d.hobMetres());
        m.mistR = 0.85 * Math.sqrt(Math.max(0, front * front - hob * hob));
        m.surgeR = WaterModel.surgeRadius(t, y);
        m.surgeH = WaterModel.surgeHeight(t, y);
        m.surgeA = WaterModel.surgeDensity(t, y);
        m.fireball = CloudPuffs.fireballGlow(t, y);
        m.fireballRgb = FireballModel.colorArgb(t, y) & 0xFFFFFF;
        m.windX = Math.cos(d.windDir()) * d.windSpeed();
        m.windZ = Math.sin(d.windDir()) * d.windSpeed();
        return m;
    }

    /** Клуб p в момент m — в s; false — клуба сейчас нет. */
    private boolean place(Puff p, Moment m, Spot s) {
        double ang = p.a * Math.PI * 2, t = m.t;
        s.shade = p.shade;
        s.heat = 0;
        double drift;
        switch (p.kind) {
            case COLUMN -> {
                if (m.top < 1) return false;
                double r;
                if (p.b < CROWN) {
                    // стенка: у воды раструбом (вода расходится от основания), выше — ровная
                    double f = p.b / CROWN, h = f * m.top;
                    r = (m.colR + m.wall * (p.c - 0.5) * 2) * (1 + 0.6 * Math.pow(1 - f, 4));
                    s.py = h;
                    // клубы стенки перекрываются втрое: столб — сплошная стена воды, а не гроздь шаров
                    s.size = m.wall * 2.3 * (1 + 1.2 * Math.pow(1 - f, 4)) * p.size * detail;
                    s.shade *= 0.72 + 0.32 * f;
                    // низ столба освещён шаром изнутри
                    s.heat = 0.8 * m.fireball * (1 - f);
                } else {
                    // шапка брызг: шире столба, поверху, оседает вместе с ним
                    double f = (p.b - CROWN) / (1 - CROWN);
                    r = m.colR * (0.2 + 1.25 * Math.sqrt(p.c));
                    s.py = m.top * (0.9 + 0.12 * f) - m.colR * 0.3 * p.c;
                    s.size = m.colR * 0.75 * p.size * detail;
                    s.shade *= 0.9 + 0.2 * f;
                    s.heat = 0.3 * m.fireball;
                }
                s.px = Math.cos(ang) * r;
                s.pz = Math.sin(ang) * r;
                s.a = 0.85 * CloudPuffs.smooth(0.4, 2.5, t) * (1 - m.fall);
                s.rgb = SPRAY;
                drift = 0.15 * t * Mth.clamp(s.py / Math.max(1, m.top), 0, 1);
            }
            case MIST -> {
                // облако конденсации: бублик на воде за фронтом, редеет кверху
                if (m.mistEnv <= 0.005 || m.mistR < 1) return false;
                double el = Math.asin(p.b * p.b * 0.9), r = m.mistR * (0.9 + 0.14 * p.c);
                s.px = Math.cos(ang) * Math.cos(el) * r;
                s.pz = Math.sin(ang) * Math.cos(el) * r;
                s.py = Math.sin(el) * r * 0.5;
                s.size = Math.max(25, m.mistR * 0.3) * p.size * detail;
                s.a = 0.62 * m.mistEnv * (1 - 0.55 * p.b);
                s.rgb = MIST;
                s.shade *= 1.05;
                // конденсат у раскалённого шара освещён им
                s.heat = 0.9 * m.fireball;
                drift = 0;
            }
            default -> { // SURGE
                if (m.surgeR <= 0 || m.surgeA < 0.01) return false;
                // кольцо: катящийся вал у края выше и гуще, к середине — ниже и реже
                double rr = m.surgeR * (0.5 + 0.5 * Math.sqrt(p.c));
                s.px = Math.cos(ang) * rr;
                s.pz = Math.sin(ang) * rr;
                s.size = Math.max(m.surgeH * 0.8, 2 * Math.PI * m.surgeR / surgeCount * 3) * p.size * detail;
                s.py = 0.3 * s.size * 0.5 + m.surgeH * p.b * (0.3 + 0.7 * p.c) * 0.7;
                s.a = 0.72 * m.surgeA * (0.45 + 0.55 * p.c);
                s.rgb = SURGE;
                s.shade *= 0.88 + 0.3 * p.b;
                s.heat = 0.25 * m.fireball;
                // у воды ветер слабее: волну сносит медленнее, чем верх гриба, — как пелену (PallField.DRIFT), с которой
                // её тело и туман брызг идут в объёме: снос пелены — по настоящим секундам, t модели × масштаб
                drift = PallField.DRIFT * d.scale() * t;
            }
        }
        s.px += m.windX * drift;
        s.pz += m.windZ * drift;
        return true;
    }

    /**
     * Видимые клубы в момент t (секунды модели); ambient — свет неба 0..1. Высота клубов — от поверхности воды под
     * эпицентром ({@code waterY}, блоки).
     */
    void sprites(double t, float ambient, double waterY, CloudPuffs.Sink out) {
        if (wet <= 0.01 || t <= 0) return;
        Moment m = moment(t);
        Spot s = new Spot();
        float[] c = new float[3];
        for (Puff p : puffs) {
            if (!place(p, m, s)) continue;
            double a = s.a * wet;
            if (a < 0.01) continue;
            CloudPuffs.smoke(s.rgb, s.shade, ambient, m.fireballRgb, s.heat, c);
            float hr = ((m.fireballRgb >> 16) & 0xFF) / 255f, hg = ((m.fireballRgb >> 8) & 0xFF) / 255f, hb = (m.fireballRgb & 0xFF) / 255f;
            out.puff(d.burst().x + d.blocks(s.px), waterY + d.blocks(s.py), d.burst().z + d.blocks(s.pz), d.blocks(s.size),
                    p.rot + (float) (p.spin * t), c[0], c[1], c[2], (float) Math.min(1, a), hr, hg, hb, (float) (a * s.heat * 0.25), p.tex);
        }
    }

    /** Сколько клубов каждого вида видно в момент t (для сценария и тестов). */
    int[] visible(double t) {
        int[] n = new int[Kind.values().length];
        if (wet <= 0.01 || t <= 0) return n;
        Moment m = moment(t);
        Spot s = new Spot();
        for (Puff p : puffs) {
            if (place(p, m, s) && s.a * wet >= 0.01) n[p.kind.ordinal()]++;
        }
        return n;
    }
}
