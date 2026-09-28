package ua.zentix.airstrike.client.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.warhead.GroundMaterial;

/**
 * Картинка обычного взрыва из частиц {@link Fx}, по кадрам как на съёмках: вспышка → огненный шар, который за
 * доли секунды «остывает» в чёрный клубящийся дым и всплывает; раскалённые осколки с дымными хвостами; кольцо
 * ударной волны и пыль цвета грунта, бегущая по земле; столб дыма, который тянет вверх и сносит ветром;
 * догорание в воронке. Размер задаёт радиус огненного шара {@code r} (блоки).
 */
final class Explosions {
    private Explosions() {}

    /** Мгновенная часть: вспышка, шар, осколки, кольцо и пыль (t = 0). */
    static void burst(ClientLevel level, Vec3 c, float r, GroundMaterial mat, RandomSource rnd) {
        float k = Fx.density(c);
        Fx.flash().size(r * 3.2f, r * 4).life(4).spawn(level, c.add(0, r * 0.4, 0));
        Fx.flash().size(r * 7, r * 9).life(7).alpha(0.35f).color(0xFFD090, 0xFF6010).spawn(level, c.add(0, r * 0.5, 0));

        // ядро шара: белое → оранжевое → тёмно-красное за долю секунды
        int n = Math.round(26 * r / 4 * k) + 4;
        for (int i = 0; i < n; i++) {
            Vec3 d = dir(rnd, 0.25);
            Fx.fire().vel(d.scale(r * (0.05 + 0.08 * rnd.nextDouble())).add(0, 0.05, 0)).size(r * 0.45f, r * (1.0f + 0.5f * rnd.nextFloat()))
                    .growFast().life(12 + rnd.nextInt(14)).drag(0.82f).rise(0.012f).spawn(level, c.add(d.scale(r * 0.5 * rnd.nextDouble())).add(0, r * 0.3, 0));
        }
        // тот же шар, остывающий в дым: сначала раскалён (светится сам), через секунду — чёрные клубы
        n = Math.round(22 * r / 4 * k) + 4;
        for (int i = 0; i < n; i++) {
            Vec3 d = dir(rnd, 0.1);
            Fx.smoke().vel(d.scale(r * (0.05 + 0.06 * rnd.nextDouble())).add(0, 0.08 + 0.06 * rnd.nextDouble(), 0))
                    .size(r * 0.55f, r * (1.6f + 0.7f * rnd.nextFloat())).growFast().life(420 + rnd.nextInt(300))
                    .color(0x24201D, 0x6E6862).colorCurve(0.7f).alpha(0.92f).glow(1, 10 + rnd.nextInt(12)).drag(0.9f).rise(0.011f)
                    .fadeIn(1).fadeFrom(0.45f).spin(0.012f).spawn(level, c.add(d.scale(r * 0.3)).add(0, r * 0.35, 0));
        }
        // раскалённые осколки с дымными хвостами — «щупальца» взрыва
        Fx.Spec tail = Fx.smoke().size(r * 0.12f, r * 0.55f).life(140).color(0x3A3430, 0x8A8279).alpha(0.55f).glow(0.6f, 5).drag(0.93f)
                .rise(0.003f).fadeIn(1).fadeFrom(0.35f).budget(FxBudget.DEBRIS);
        n = Math.round(9 * r / 4 * k) + 3;
        for (int i = 0; i < n; i++) {
            Vec3 d = dir(rnd, 0.35);
            Fx.spark().vel(d.scale(0.9 + rnd.nextDouble() * 1.1)).size(0.16f, 0.08f).life(28 + rnd.nextInt(24)).gravity(0.045f).drag(0.985f)
                    .streak(1.5f).trail(tail, 0.45f, 0.85f).spawn(level, c.add(0, r * 0.3, 0));
        }
        // искры
        n = Math.round(60 * r / 4 * k) + 10;
        for (int i = 0; i < n; i++) {
            Vec3 d = dir(rnd, 0.0);
            Fx.spark().vel(d.scale(0.4 + rnd.nextDouble() * 1.3)).life(10 + rnd.nextInt(30)).gravity(0.04f).drag(0.96f)
                    .spawn(level, c.add(0, r * 0.3, 0));
        }
        // кольцо ударной волны и вал пыли по земле
        Fx.ring().size(r * 0.6f, r * 11).life(12).alpha(0.5f).spawn(level, c.add(0, 0.4, 0));
        Fx.ring().size(r * 0.4f, r * 6).life(22).alpha(0.35f).color(rgb(mat), rgb(mat)).spawn(level, c.add(0, 0.25, 0));
        dustSurge(level, c, r, mat, rnd, k);
    }

    /** Пыль и дым, которые ударная волна гонит по земле во все стороны. */
    private static void dustSurge(ClientLevel level, Vec3 c, float r, GroundMaterial mat, RandomSource rnd, float k) {
        int n = Math.round(44 * r / 4 * k) + 6;
        int light = lighten(rgb(mat), 0.35f);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Mth.TWO_PI, v = 0.5 + rnd.nextDouble() * 0.9;
            Fx.smoke().vel(Math.cos(a) * v * r / 4, 0.03 + rnd.nextDouble() * 0.08, Math.sin(a) * v * r / 4).size(r * 0.35f, r * (0.9f + 0.5f * rnd.nextFloat()))
                    .growFast().life(200 + rnd.nextInt(160)).color(rgb(mat), light).alpha(0.8f).drag(0.88f).collide().rise(0.002f)
                    .fadeIn(2).fadeFrom(0.4f).spawn(level, c.x + Math.cos(a) * r * 0.5, c.y + 0.6, c.z + Math.sin(a) * r * 0.5);
        }
    }

    /**
     * Столб: дым тянется за шаром вверх (ножка), в воронке догорает, изредка стреляют угли.
     *
     * @param t тик после взрыва (≥ 1)
     */
    static void column(ClientLevel level, Vec3 c, float r, int t, int columnTicks, int fireTicks, RandomSource rnd) {
        float k = Fx.density(c);
        if (t <= columnTicks) {
            float f = (float) t / columnTicks;
            int n = Math.max(1, Math.round((3 - 2 * f) * k * r / 4));
            for (int i = 0; i < n; i++) {
                Fx.smoke().vel(rnd.nextGaussian() * 0.04, 0.22 + 0.2 * rnd.nextDouble() * (1 - f), rnd.nextGaussian() * 0.04)
                        .size(r * 0.45f, r * (1.1f + 0.4f * rnd.nextFloat())).life(360 + rnd.nextInt(200))
                        .color(0x2E2A26, 0x7A746E).alpha(0.85f * (1 - 0.5f * f)).glow(t < 10 ? 0.5f : 0, 8).drag(0.95f).rise(0.006f)
                        .fadeIn(4).fadeFrom(0.5f).spin(0.01f).spawn(level, c.x + rnd.nextGaussian() * r * 0.35, c.y + 0.5, c.z + rnd.nextGaussian() * r * 0.35);
            }
        }
        if (t <= fireTicks) {
            float f = (float) t / fireTicks;
            if (rnd.nextFloat() < (1 - f) * k * 1.5f) {
                Vec3 p = c.add(rnd.nextGaussian() * r * 0.5, 0.3, rnd.nextGaussian() * r * 0.5);
                Fx.fire().vel(0, 0.06 + 0.05 * rnd.nextDouble(), 0).size(r * 0.1f, r * 0.22f).life(12 + rnd.nextInt(10)).rise(0.004f)
                        .spawn(level, p);
                Fx.smoke().vel(0, 0.1, 0).size(r * 0.12f, r * 0.6f).life(140 + rnd.nextInt(80)).color(0x2C2826, 0x6C6660).alpha(0.6f)
                        .glow(0.5f, 6).rise(0.006f).fadeFrom(0.4f).spawn(level, p.add(0, r * 0.15, 0));
            }
            if (rnd.nextFloat() < (1 - f) * 0.3f) {
                Fx.spark().vel(rnd.nextGaussian() * 0.05, 0.08 + 0.1 * rnd.nextDouble(), rnd.nextGaussian() * 0.05).size(0.06f, 0.03f)
                        .life(30 + rnd.nextInt(40)).gravity(-0.001f).drag(0.98f).streak(0).color(0xFFC060, 0xA02000)
                        .spawn(level, c.add(rnd.nextGaussian() * r * 0.5, 0.5, rnd.nextGaussian() * r * 0.5));
            }
        }
    }

    /** Вторичный подрыв в воронке (боекомплект, топливо). */
    static void cookoff(ClientLevel level, Vec3 p, float r, RandomSource rnd) {
        Fx.flash().size(r * 2, r * 2.5f).life(3).spawn(level, p.add(0, r * 0.3, 0));
        for (int i = 0; i < 8; i++) {
            Vec3 d = dir(rnd, 0.3);
            Fx.fire().vel(d.scale(r * 0.08)).size(r * 0.25f, r * 0.6f).growFast().life(8 + rnd.nextInt(8)).drag(0.8f).spawn(level, p.add(0, r * 0.3, 0));
            Fx.smoke().vel(d.scale(r * 0.05).add(0, 0.1, 0)).size(r * 0.3f, r * 0.9f).growFast().life(200 + rnd.nextInt(100)).color(0x26221F, 0x6A645E)
                    .alpha(0.85f).glow(1, 8).drag(0.9f).rise(0.01f).fadeFrom(0.45f).spawn(level, p.add(0, r * 0.3, 0));
        }
        for (int i = 0; i < 25; i++) {
            Fx.spark().vel(dir(rnd, 0.2).scale(0.3 + rnd.nextDouble())).life(10 + rnd.nextInt(20)).gravity(0.04f).spawn(level, p.add(0, 0.5, 0));
        }
    }

    /** Облако конденсации: белая полусфера на фронте, видна мгновение (во влажном воздухе у мощных зарядов). */
    static void condensation(ClientLevel level, Vec3 c, float radius, RandomSource rnd) {
        int n = Math.round(60 * Fx.density(c));
        for (int i = 0; i < n; i++) {
            Vec3 d = dir(rnd, 0.05);
            Fx.smoke().vel(d.scale(1.6)).size(radius * 0.12f, radius * 0.22f).life(10).color(0xF2F4F6, 0xFFFFFF).alpha(0.35f)
                    .drag(0.75f).fadeIn(1).fadeFrom(0.2f).wind(0).spawn(level, c.add(d.scale(radius * 0.4)));
        }
    }

    /** Случайное направление; {@code minUp} — нижняя граница вертикальной составляющей (0 — полусфера). */
    static Vec3 dir(RandomSource rnd, double minUp) {
        double y = minUp + (1 - minUp) * rnd.nextDouble();
        double a = rnd.nextDouble() * Mth.TWO_PI, h = Math.sqrt(1 - y * y);
        return new Vec3(Math.cos(a) * h, y, Math.sin(a) * h);
    }

    static int rgb(GroundMaterial m) {
        return (int) (m.r * 255) << 16 | (int) (m.g * 255) << 8 | (int) (m.b * 255);
    }

    static int lighten(int rgb, float f) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return (int) (r + (255 - r) * f) << 16 | (int) (g + (255 - g) * f) << 8 | (int) (b + (255 - b) * f);
    }
}
