package ua.zentix.airstrike.client.fx.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import org.joml.FrustumIntersection;
import ua.zentix.airstrike.client.fx.layer.FxFrame;
import ua.zentix.airstrike.client.fx.layer.FxQuads;

import java.util.Arrays;

/**
 * Все живые частицы эффектов: свой пул вместо ванильного движка частиц. Их рисует один проход после мира вместе
 * с дальней картинкой ({@link ua.zentix.airstrike.client.fx.layer.FxLayer}): отсортированными от дальних к ближним,
 * с мягкими краями у земли и рельефа Distant Horizons. В ванильном движке у слоя одна очередь без сортировки (кто
 * родился позже, тот поверх), и её предел вытеснял старые облака.
 * <p>
 * Места — по группам {@link FxBudget}: полная группа новую частицу не принимает. Объекты частиц переиспользуются,
 * рождение и смерть ничего не выделяют. Тикает поток игры (не на паузе, мир не заморожен), рисует — поток отрисовки
 * (тот же поток).
 */
public final class FxPool {
    public static final FxPool INSTANCE = new FxPool();

    final RandomSource random = RandomSource.create();
    private final FxParticle[] parts = new FxParticle[FxBudget.total()];
    /** Живые — {@code parts[0..count)}; за ними — объекты для новых. */
    private int count;
    /** Сколько было живых после прошлого тика: рождённые позже ждут следующего тика, как в ванильном движке. */
    private int settled;
    private final int[] live = new int[FxBudget.values().length];
    private final long[] refused = new long[FxBudget.values().length];
    /** Мир, в котором живут частицы: сменился (другое измерение) — старые убираются, как в ванильном движке. */
    private ClientLevel level;

    private FxPool() {}

    /**
     * Новая частица по настройке в (x, y, z), размеры — с множителями (хвост искры тоньше к концу). Полная группа —
     * не рождается (и это считается).
     */
    void spawn(Fx.Spec spec, ClientLevel level, double x, double y, double z, float sizeScale0, float sizeScale1) {
        int g = spec.budget.ordinal();
        if (live[g] >= spec.budget.limit) {
            refused[g]++;
            return;
        }
        FxParticle p = parts[count];
        if (p == null) parts[count] = p = new FxParticle();
        p.init(spec, x, y, z, sizeScale0, sizeScale1, random, level);
        count++;
        live[g]++;
    }

    /**
     * Тик частиц (после тика мира, как ванильный движок; не на паузе и не в заморозке). Рождённые после прошлого
     * тика — в тиках сущностей, эффектами, хвостами искр — встают в конец и тикают со следующего, как в ванильном
     * движке; умершие уходят за живых одним проходом, порядок живых сохраняется.
     */
    public void tick(ClientLevel level) {
        if (level != this.level) {
            clear();
            this.level = level;
            return;
        }
        int end = settled;
        for (int i = 0; i < end; i++) parts[i].tick(level, this);
        int w = 0;
        for (int i = 0; i < count; i++) {
            FxParticle p = parts[i];
            if (p.dead) {
                live[p.budget.ordinal()]--;
                continue;
            }
            if (w != i) {
                parts[i] = parts[w];
                parts[w] = p;
            }
            w++;
        }
        count = w;
        settled = w;
    }

    /** Видимые в кадре — в общий кадр слоя. */
    public void collect(FxFrame frame, FxQuads out, float partial) {
        FrustumIntersection frustum = frame.frustum;
        for (int i = 0; i < count; i++) {
            FxParticle p = parts[i];
            float x = (float) (p.x - frame.cam.x), y = (float) (p.y - frame.cam.y), z = (float) (p.z - frame.cam.z);
            if (!frustum.testSphere(x, y, z, (float) p.reach())) continue;
            p.emit(frame, out, partial);
        }
    }

    /** Выход из мира, смена измерения, сценарии. */
    public void clear() {
        level = null;
        count = settled = 0;
        Arrays.fill(live, 0);
    }

    public int count() {
        return count;
    }

    /** Сколько частиц группы живёт. */
    public int live(FxBudget b) {
        return live[b.ordinal()];
    }

    /** Сколько частиц группе не досталось места с запуска игры. */
    public long refused(FxBudget b) {
        return refused[b.ordinal()];
    }

    /** Сколько живых частиц ближе {@code radius} к точке и в конусе {@code cos} вокруг направления (для сценариев). */
    public int near(double ex, double ey, double ez, double lx, double ly, double lz, double radius, double cos) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            FxParticle p = parts[i];
            double dx = p.x - ex, dy = p.y - ey, dz = p.z - ez, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < radius && dx * lx + dy * ly + dz * lz > len * cos) n++;
        }
        return n;
    }
}
