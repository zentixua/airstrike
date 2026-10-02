package ua.zentix.airstrike.client.fx.layer;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Arrays;

/**
 * Огненные шары кадра как источники света для частиц и лент шлейфов: шейдер слоя ({@code fx.vsh}) добавляет их свет
 * к карте освещения. Без него пыль и дым у шара ночью освещала только луна, синим, и полупрозрачные клубы поверх
 * жёлтого шара выходили лиловыми. Ламбертов шар яркости b (против белого днём) освещает свою поверхность как b солнц,
 * дальше — как (r/d)². Шаров — не больше {@link #MAX}, самые заметные: поток света шара на квадрат расстояния до глаза.
 * Светящееся (шар, искры, вспышки, своё — {@code FULL_BRIGHT}) света шаров не берёт.
 */
public final class FxLights {
    public static final int MAX = 4;
    /** Столбец i: центр шара i от камеры (xyz), радиус (w; 0 — шара нет). */
    final Matrix4f balls = new Matrix4f().zero();
    /** Столбец i: освещённость у поверхности шара i против света вокруг глаза, с цветом (rgb). */
    final Matrix4f light = new Matrix4f().zero();
    private final double[] weight = new double[MAX];
    private final Vector4f column = new Vector4f();

    /** Новый кадр: шаров нет. */
    void begin() {
        balls.zero();
        light.zero();
        Arrays.fill(weight, 0);
    }

    /**
     * Шар с центром (x, y, z) от камеры, радиуса r, яркости b против белого экрана днём с привыканием глаза
     * ({@code Sight#adapted}), цвета (cr, cg, cb).
     */
    public void add(double x, double y, double z, double r, double b, float cr, float cg, float cb) {
        if (r <= 0 || b <= 0) return;
        double w = b * r * r / Math.max(x * x + y * y + z * z, r * r);
        int slot = 0;
        for (int i = 1; i < MAX; i++) if (weight[i] < weight[slot]) slot = i;
        if (w <= weight[slot]) return;
        weight[slot] = w;
        balls.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
        light.setColumn(slot, column.set((float) (b * cr), (float) (b * cg), (float) (b * cb), 0));
    }

    /** Радиус шара в столбце i (0 — пусто), для проверок. */
    float radius(int i) {
        return balls.getColumn(i, column).w;
    }
}
