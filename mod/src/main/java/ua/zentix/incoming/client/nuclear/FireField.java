package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Где и сколько горит после подрыва — модель клиента для звука горящего города и вторичных взрывов (сам огонь ставит
 * сервер, клиент видит только блоки огня в своих чанках). Кольца вокруг эпицентра шириной {@link #RING}: в кольце
 * горит доля колонок по той же вероятности, что у руин ({@code RuinPlanner.fire}: min(1, q/20), вдвое меньше от
 * 2 psi, q — свет на земле, к краю руин след света сходит на нет), на {@link #SHARE} колонок есть чему гореть. Огонь
 * встаёт с фронтом (руины ставит волна), сервер зажигает не больше {@link #MAX_FIRES} на подрыв по порядку прихода
 * волны — модель режет кольца там же. Сколько горит потом — {@link #envelope}: разгорается {@link #RISE} тиков,
 * потом гаснет со временем {@link #TAU}. Что на деле горит рядом, клиент сверяет по своим чанкам ({@link FireWatch}).
 */
final class FireField {
    /** Ширина кольца, блоков. */
    static final double RING = 32;
    /** Доля колонок, где огню есть на чём встать (трава, земля, дерево — не камень и не вода). */
    static final double SHARE = 0.6;
    /** Пожаров на подрыв у сервера по умолчанию ({@code fires_per_detonation}); клиент своё число сервера не знает. */
    static final int MAX_FIRES = 20_000;
    /** Огонь разгорается, тиков. */
    static final double RISE = 20 * 20;
    /** И гаснет со временем, тиков: трава и земля прогорают за секунды, дома и лес — минуты. */
    static final double TAU = 5 * 60 * 20;
    /** Порог вероятности пожара от света ({@code RuinPlanner.fire}). */
    private static final double FULL_Q = 20;
    /** К краю руин след света сходит на нет на этой доле радиуса ({@code RuinPlanner.edge}). */
    private static final double EDGE_FADE = 0.2;

    final Detonation d;
    /** Ожидаемых пожаров в кольце (после среза по {@link #MAX_FIRES}). */
    final double[] fires;
    /** Приход фронта к середине кольца, тиков после подрыва. */
    final double[] arrival;
    final double total;

    FireField(Detonation d) {
        this.d = d;
        double ruin = d.ruinRadius(), inner = d.surface() ? d.fireballRadius() * 0.8 : 0;
        int rings = (int) Math.ceil(ruin / RING);
        double[] f = new double[rings];
        double[] at = new double[rings];
        double sum = 0;
        for (int i = 0; i < rings; i++) {
            double r = (i + 0.5) * RING;
            at[i] = d.arrivalTicks(r);
            if (r < inner || sum >= MAX_FIRES) continue;
            Vec3 p = new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z);
            double q = d.fluence(p) * edge(r, ruin);
            if (q < ThermalModel.IGNITE) continue;
            double chance = Math.min(1, q / FULL_Q) * (d.psi(p) >= 2 ? 0.5 : 1);
            double area = Math.PI * (Math.pow((i + 1) * RING, 2) - Math.pow(i * RING, 2));
            f[i] = Math.min(MAX_FIRES - sum, SHARE * chance * area);
            sum += f[i];
        }
        this.fires = f;
        this.arrival = at;
        this.total = sum;
    }

    private static double edge(double r, double ruin) {
        double f = Math.max(0, Math.min(1, (1 - r / ruin) / EDGE_FADE));
        return f * f * (3 - 2 * f);
    }

    static double radius(int ring) {
        return (ring + 0.5) * RING;
    }

    /** Доля огня, что горит через {@code ticks} после того, как его зажгла волна. */
    static double envelope(double ticks) {
        if (ticks <= 0) return 0;
        return ticks < RISE ? ticks / RISE : Math.exp(-(ticks - RISE) / TAU);
    }

    /** Сколько горит в кольце через {@code ticks} после подрыва. */
    double burning(int ring, double ticks) {
        return fires[ring] * envelope(ticks - arrival[ring]);
    }

    /** Сколько горит всего. */
    double burning(double ticks) {
        double s = 0;
        for (int i = 0; i < fires.length; i++) s += burning(i, ticks);
        return s;
    }

    /** Огонь кончился: тиков после подрыва, когда от него осталось меньше 2 %. */
    double endTicks() {
        double last = 0;
        for (int i = 0; i < fires.length; i++) if (fires[i] > 0) last = arrival[i];
        return last + RISE + TAU * Math.log(50);
    }

    /** Вторичный взрыв в горящем городе: газ, топливо, склады. */
    record Secondary(double ticks, double x, double z, boolean big, long seed) {}

    /**
     * Вторичные взрывы подрыва (всё — от зерна подрыва: у всех клиентов и в повторе одни и те же): чем больше горит,
     * тем больше их, по месту — где горит, по времени — от {@link #SECONDARY_AFTER} после прихода волны, всё реже.
     */
    List<Secondary> secondaries() {
        List<Secondary> out = new ArrayList<>();
        if (total < 1) return out;
        RandomSource random = RandomSource.create(d.seed() ^ 0x5EC0DA2L);
        int n = (int) Math.round(Math.max(4, Math.min(24, total / 800)));
        for (int k = 0; k < n; k++) {
            double u = random.nextDouble() * total;
            int ring = 0;
            while (ring < fires.length - 1 && u > fires[ring]) u -= fires[ring++];
            double r = (ring + random.nextDouble()) * RING;
            double angle = random.nextDouble() * 2 * Math.PI;
            // время: после того как разгорелось, всё реже (среднее — SECONDARY_TAU), не позже SECONDARY_END
            double t = arrival[ring] + SECONDARY_AFTER - SECONDARY_TAU * Math.log(1 - random.nextDouble() * (1 - Math.exp(-SECONDARY_END / SECONDARY_TAU)));
            out.add(new Secondary(t, d.burst().x + Math.cos(angle) * r, d.burst().z + Math.sin(angle) * r, random.nextFloat() < 0.2f, random.nextLong()));
        }
        out.sort((a, b) -> Double.compare(a.ticks, b.ticks));
        return out;
    }

    /** Вторичные взрывы — не раньше стольких тиков после прихода волны, в среднем через столько и не позже. */
    static final double SECONDARY_AFTER = 30 * 20, SECONDARY_TAU = 3 * 60 * 20, SECONDARY_END = 10 * 60 * 20;
}
