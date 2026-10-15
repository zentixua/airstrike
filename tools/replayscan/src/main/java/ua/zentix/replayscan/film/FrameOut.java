package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Доля кадра на земле за чанками повтора — одна функция для вывода ({@link FrameLog}) и пилота ({@link Pilot}), чтобы
 * они не расходились: сетка лучей по всему кадру от края до края ({@link #W}×{@link #H}, углы включительно), каждый луч
 * идёт шагом не больше {@link #STEP} блоков до {@link #LOW_NEAR}. Луч «вне», если доходит до столбца без чанка
 * ({@link Column} — NaN) на высоте не выше последней известной земли + 1 — высоты последнего столбца с чанком на луче,
 * без них — уровня моря ванили: там только рельеф Distant Horizons (у моря — дно и пещеры без воды) или пусто. Луч
 * останавливается, встретив землю в чанке; луч вверх не показывает землю — не «вне». Возвращает долю лучей «вне».
 * Прежние три луча у нижнего края (до 15.10) дыру выше по кадру не видели: дома, обрезанные по границам чанков, и
 * пятна LOD в правой половине кадра проходили проверку.
 */
final class FrameOut {
    /** Сетка лучей кадра: по ширине и по высоте, от края до края, углы включительно. */
    static final int W = 9, H = 16;
    /** Шаг луча по его длине, блоков. */
    static final double STEP = 2;
    /**
     * Как далеко от камеры луч ещё проверяется: дальше LOD Distant Horizons в кадре — обычная даль. Уровень моря
     * ванили — высота земли на луче, пока чанков на нём не было.
     */
    static final double LOW_NEAR = 128, SEA = 63;

    /** Столбец мира: высота земли (верх блоков, как {@code LevelChunk.getHeight}) или NaN — чанка нет. */
    @FunctionalInterface
    interface Column {
        double ground(int x, int z);
    }

    /** Доля лучей сетки кадра «вне» — на земле за чанками повтора. */
    static double share(Cameras.View v, Column col) {
        int out = 0;
        for (int i = 0; i < W; i++) {
            double sx = -1 + 2.0 * i / (W - 1);
            for (int j = 0; j < H; j++) {
                double sy = -1 + 2.0 * j / (H - 1);
                Vec3 d = v.fwd().add(v.right().scale(sx * v.tanX())).add(v.up().scale(sy * v.tanY())).normalize();
                if (d.y >= 0) continue;
                double last = SEA;
                for (double k = 0; k <= LOW_NEAR; k += STEP) {
                    Vec3 p = v.eye().add(d.scale(k));
                    double g = col.ground(Mth.floor(p.x), Mth.floor(p.z));
                    if (Double.isNaN(g)) {
                        if (v.eye().y + d.y * LOW_NEAR <= last + 1) out++;
                        break;
                    }
                    last = g;
                    if (p.y <= g + 1) break;
                }
            }
        }
        return (double) out / (W * H);
    }
}
