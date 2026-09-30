package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombDropTest {
    /** B-2: эшелон 170, дальность сброса на нём 85, 12 блоков/тик. */
    private static final double RATIO = 85.0 / 170, SPEED = 12;

    /**
     * Где B-2 сбросит бомбу, идя по прямой на высоте {@code h} над точкой (на {@code side} в стороне от курса) из
     * {@code from} блоков до неё; -1 — нигде.
     */
    static double releaseAt(double h, double from, double side) {
        Vec3 aim = Vec3.ZERO;
        for (double z = -from; z < 400; z += SPEED) {
            Vec3 pos = new Vec3(side, h, z);
            if (BombDrop.releaseNow(pos, new Vec3(0, 0, SPEED), 0f, aim, RATIO)) return pos.horizontalDistance();
        }
        return -1;
    }

    /** На эшелоне — как раньше: на черте 85 блоков (за тик B-2 проходит 12), с какого бы места он ни шёл. */
    @Test
    void releasesAtTheLineOnLevel() {
        for (int phase = 0; phase < SPEED; phase++) {
            double at = releaseAt(170, 1200 + phase, 0);
            assertTrue(at > 85 - SPEED && at <= 85, "сброс в " + at + " (сдвиг " + phase + ")");
        }
    }

    /**
     * С любой высоты от 20 до 490 блоков над точкой (вернулся в мир над горой, точку перенацелили выше или ниже) B-2
     * сбрасывает с первого захода (с 100 блоков — и сбоку от курса) и не дальше края окна: выше эшелона на черте для своей
     * высоты, ниже ~140 — на краю, а он не дальше высоты + 20 (дальше — рваные попадания, см. {@link BombDrop#EDGE_REACH}).
     * Что бомба оттуда приходит в точку в мире, проверяет GameTest {@code bomberReleasePointsHitInWorld}.
     */
    @Test
    void releasesFromAnyHeight() {
        for (int h = 20; h <= 490; h += 10) {
            // низко над точкой бомба к точке сбоку не доворачивает (заход снова): ниже 100 блоков — только по курсу
            for (double side : h < 100 ? new double[]{0} : new double[]{0, 20, -35}) {
                for (int phase = 0; phase < SPEED; phase++) {
                    double at = releaseAt(h, 1200 + phase, side);
                    assertTrue(at > 0, "с " + h + " не сбросил (сбоку " + side + ", сдвиг " + phase + ")");
                    assertTrue(at <= Math.max(h * RATIO, h + 20), "с " + h + " сбросил в " + at + " (сбоку " + side + ", сдвиг " + phase + ")");
                }
            }
        }
    }

    /**
     * Край окна — там, где нос бомбы касается земли раньше, чем она подходит к точке (ревью, 30.09.2026): со 100 блоков
     * с 64,5 и со 170 с 67 бомба в мире перелетает точку на 15–16 блоков, а проигрыш без носа и без полублока земли
     * считал это попаданием и сбрасывал там.
     */
    @Test
    void edgeCountsTheNoseAndGround() {
        assertTrue(BombDrop.miss(new Vec3(0, 100, -64.5), 0f, Vec3.ZERO) > 10);
        assertTrue(BombDrop.miss(new Vec3(0, 170, -67), 0f, Vec3.ZERO) > 10);
    }

    /**
     * Промахи, из-за которых сброс стал зависеть от высоты (стенд и ревью #126, 29.09.2026): с 214 блоков с черты 85
     * бомба перелетала точку на ~42 (19 — ровно с черты, до 43 — с 73), с эшелона внутри черты — на 130–155, на точку
     * в 40 блоках впереди — на 111.
     * Первое B-2 теперь сбрасывает дальше, а с последних двух не сбрасывает вовсе и заходит снова.
     */
    @Test
    void doesNotReleaseWhereTheBombMisses() {
        assertTrue(BombDrop.miss(new Vec3(0, 214, -85), 0f, Vec3.ZERO) > 15);
        assertTrue(releaseAt(214, 700, 0) > 85, "с 214 — дальше черты");
        assertTrue(BombDrop.miss(new Vec3(0, 170, -40), 0f, Vec3.ZERO) > 30);
        assertEquals(-1, releaseAt(170, 60, 0), "внутри черты не сбрасывает");
        assertEquals(-1, releaseAt(170, 40, 0), "точка в 40 блоках впереди");
    }

    /** Точка позади: бомба падает по курсу и назад не рулит — не сбрасывать. */
    @Test
    void neverReleasesOverPointBehind() {
        Vec3 pos = new Vec3(0, 170, 60);
        assertTrue(BombDrop.passed(pos, Vec3.ZERO, 0f));
        assertTrue(BombDrop.miss(pos, 0f, Vec3.ZERO) > 0);
    }

    /** B-2 ниже точки (перенацелили на вершину горы над ним): бомба туда не долетит — не сбрасывать. */
    @Test
    void neverReleasesBelowThePoint() {
        assertEquals(-1, releaseAt(-20, 1000, 0));
    }
}
