package ua.zentix.airstrike.strike;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.entity.LauncherRack;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.nuclear.world.BlockResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Числа паспорта согласованы между собой: выводимые (радиус разворота, упреждение сирены) — как в описании оружия,
 * а подобранные (геометрия атаки, последний участок, пакет пусковой) не противоречат выводимым. Поменяв скорость или
 * поворот в паспорте, этот тест скажет, какое подобранное число пересмотреть.
 */
class WeaponSpecTest {
    private static final List<WeaponSpec> GUIDED = List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE);

    @Test
    void turnRadiusIsSpeedOverTurnRate() {
        // шахед ~40 блоков, ракета ~76, B-2 ~690 (описание BomberEntity)
        assertEquals(40.1, WeaponSpec.DRONE.airframe().turnRadius(WeaponSpec.DRONE.airframe().cruiseSpeed()), 0.1);
        assertEquals(76.4, WeaponSpec.MISSILE.airframe().turnRadius(WeaponSpec.MISSILE.airframe().cruiseSpeed()), 0.1);
        assertEquals(687.5, WeaponSpec.BUNKER.airframe().turnRadius(WeaponSpec.BUNKER.airframe().cruiseSpeed()), 0.1);
    }

    /**
     * Как снаряд видит и бьёт ЗРК: B-2 и «Ланцет» малозаметны, бомба под B-2 — почти не видна; МБР и РСЗО ЗРК видит,
     * но не перехватывает (МБР — не его цель, «Град» дешевле ракеты); шахед, ракета, «Ланцет» и B-2 — да.
     */
    @Test
    void radarSignature() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec.Signature r = w.spec().airframe().radar();
            assertTrue(r.visibility() > 0 && r.visibility() <= 1, w + ": заметность " + r.visibility());
            assertEquals(w == WeaponType.DRONE || w == WeaponType.MISSILE || w == WeaponType.LOITER || w == WeaponType.BUNKER, r.intercept(),
                    w + ": перехват");
        }
        assertTrue(WeaponSpec.BUNKER.airframe().radar().visibility() <= 0.2, "B-2 виден издалека");
        assertTrue(WeaponSpec.LOITER.airframe().radar().visibility() < WeaponSpec.DRONE.airframe().radar().visibility());
        assertTrue(WeaponSpec.MISSILE.airframe().radar().visibility() < WeaponSpec.DRONE.airframe().radar().visibility());
        WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload();
        assertTrue(bomb != null && !bomb.radar().intercept() && bomb.radar().visibility() <= WeaponSpec.BUNKER.airframe().radar().visibility());
    }

    @Test
    void sirenLeadIsSecondsInTicks() {
        assertEquals(500, WeaponSpec.DRONE.sirenLead());
        assertEquals(300, WeaponSpec.MISSILE.sirenLead());
        assertEquals(160, WeaponSpec.ROCKET.sirenLead());
        assertEquals(400, WeaponSpec.LOITER.sirenLead());
        assertEquals(400, WeaponSpec.BUNKER.sirenLead());
    }

    /**
     * Заряд боевой части есть у того, у кого есть наземный взрыв, и стёкла (порог хрупкого) он выбивает до дальности
     * из описания оружия.
     */
    @Test
    void chargeBreaksGlassAsDescribed() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.ROCKET, WeaponSpec.LOITER, WeaponSpec.BUNKER, WeaponSpec.NUKE)) {
            assertEquals(w.blast() == WeaponSpec.Blast.NONE, w.charge() == 0, () -> w.blast() + ": заряд " + w.charge());
        }
        assertEquals(70, glassRange(WeaponSpec.DRONE), 7);
        assertEquals(150, glassRange(WeaponSpec.MISSILE), 15);
        assertEquals(37, glassRange(WeaponSpec.ROCKET), 4);
        assertEquals(29, glassRange(WeaponSpec.LOITER), 3);
    }

    private static double glassRange(WeaponSpec w) {
        return BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), w.charge());
    }

    /** Рассеивание: у РСЗО СКО — 1 % дальности, не меньше блока; управляемые бьют в саму точку. */
    @Test
    void dispersionIsShareOfRange() {
        assertEquals(6, WeaponSpec.ROCKET.route().sigma(600), 1e-9);
        assertEquals(1, WeaponSpec.ROCKET.route().sigma(40), 1e-9);
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER, WeaponSpec.BUNKER)) assertEquals(0, w.route().sigma(600));
    }

    /**
     * Промах: у ракеты и бомбы меньше, чем у шахеда; «Ланцет» (камера), МБР и РСЗО (у неё рассеивание) — без промаха.
     * Выборка промахов шахеда — с его СКО по каждой оси, по горизонтали.
     */
    @Test
    void missIsPerWeapon() {
        assertTrue(WeaponSpec.MISSILE.route().error() < WeaponSpec.DRONE.route().error());
        assertTrue(WeaponSpec.BUNKER.route().error() < WeaponSpec.DRONE.route().error());
        RandomSource random = RandomSource.create(1);
        for (WeaponSpec w : List.of(WeaponSpec.LOITER, WeaponSpec.NUKE, WeaponSpec.ROCKET)) assertEquals(Vec3.ZERO, w.route().miss(random));
        int n = 20_000;
        double sx = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            Vec3 m = WeaponSpec.DRONE.route().miss(random);
            assertEquals(0, m.y);
            sx += m.x * m.x;
            sz += m.z * m.z;
        }
        assertEquals(WeaponSpec.DRONE.route().error(), Math.sqrt(sx / n), 0.05);
        assertEquals(WeaponSpec.DRONE.route().error(), Math.sqrt(sz / n), 0.05);
    }

    /** Тревога звучит раньше, чем снаряд выходит на последний прямой участок: у цели успевают услышать заход. */
    @Test
    void sirenBeforeFinalLeg() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.BUNKER)) {
            double warned = w.sirenLead() * w.airframe().cruiseSpeed();
            assertTrue(warned >= w.route().finalLeg(), "тревога за " + warned + " блоков, последний участок " + w.route().finalLeg());
        }
    }

    /**
     * Промах ближе {@code reattackMin} атаку не отменяет: он меньше круга разворота с запасом, иначе снаряд у самой цели
     * ушёл бы на новый заход там, где взрыватель ещё добирает.
     */
    @Test
    void reattackInsideTurnCircle() {
        for (WeaponSpec w : GUIDED) {
            WeaponSpec.Airframe a = w.airframe();
            double circle = a.turnRadius(a.cruiseSpeed()) * WeaponSpec.TURN_MARGIN;
            assertTrue(a.attack().reattackMin() > 0 && a.attack().reattackMin() < circle,
                    "reattackMin " + a.attack().reattackMin() + ", круг разворота " + circle);
        }
    }

    /**
     * Горка начинается вне круга разворота на скорости пикирования (иначе цель на атаке сразу «внутри круга» и атака
     * отменяется), не дальше последнего прямого участка и в полосе, которую видно у цели; заход с горкой — издалека,
     * чем её начало.
     */
    @Test
    void popUpGeometryFitsTurnAndLegs() {
        WeaponSpec.Airframe a = WeaponSpec.MISSILE.airframe();
        WeaponSpec.Attack attack = a.attack();
        assertTrue(attack.terminalRange() > a.turnRadius(a.diveSpeed()) * WeaponSpec.TURN_MARGIN);
        assertTrue(attack.terminalRange() <= WeaponSpec.MISSILE.route().finalLeg());
        assertTrue(attack.terminalRange() <= a.visibleLeg());
        assertTrue(attack.popUpMinRange() > attack.terminalRange());
    }

    /** Последний прямой участок вмещает два радиуса разворота: снаряд выходит из обхода на курс цели до атаки. */
    @Test
    void finalLegFitsTurn() {
        for (WeaponSpec w : GUIDED) {
            WeaponSpec.Airframe a = w.airframe();
            assertTrue(w.route().finalLeg() >= 2 * a.turnRadius(a.cruiseSpeed()), "последний участок " + w.route().finalLeg());
        }
    }

    /**
     * Точка маршрута взята около радиуса разворота: ближе снаряд кружил бы вокруг неё, а много дальше срезал бы поворот.
     * Дальность по маршруту оператора — путь до цели на краю карты по умолчанию (10 000 блоков) с обходом в полтора раза
     * длиннее прямой.
     */
    @Test
    void waypointCaptureAndReach() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER)) {
            WeaponSpec.Airframe a = w.airframe();
            double r = a.turnRadius(a.cruiseSpeed());
            assertTrue(w.route().capture() >= 0.95 * r && w.route().capture() <= 2 * r, "захват точки " + w.route().capture() + ", радиус разворота " + r);
            assertTrue(w.route().reach() >= 1.5 * 10_000, "дальность по маршруту " + w.route().reach());
        }
        for (WeaponSpec w : List.of(WeaponSpec.BUNKER, WeaponSpec.NUKE, WeaponSpec.ROCKET)) assertTrue(!w.route().waypoints());
    }

    /** Ячейка пусковой занята, пока снаряд горит на направляющей и сходит с неё; пуски не чаще, чем освобождается ячейка. */
    @Test
    void rackHoldsSlotThroughLaunch() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER)) {
            WeaponSpec.LaunchProfile lp = w.airframe().launchProfile();
            LauncherRack rack = w.rack();
            assertTrue(rack != null && lp != null);
            assertTrue(rack.busyTicks() >= lp.ignitionTicks() + lp.railTicks(), w.launch() + ": ячейка " + rack.busyTicks());
            assertTrue(rack.spacing() <= rack.busyTicks());
        }
    }

    /** У каждого вида оружия свой паспорт, и порядок видов (номера в сети и сохранениях) не менялся. */
    @Test
    void everyWeaponHasSpec() {
        assertEquals(List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.BUNKER, WeaponType.NUKE, WeaponType.ROCKET, WeaponType.LOITER),
                List.of(WeaponType.values()));
        for (WeaponType w : WeaponType.values()) {
            assertTrue(w.spec() != null, w.getSerializedName());
            assertEquals(w.ordinal(), w.id());
        }
        assertTrue(WeaponType.BUNKER.spec().payload() != null);
    }

    /**
     * Боеприпас у каждого оружия: по предмету на снаряд, а пакет «Града» — на все трубы пусковой (40, как перезарядка
     * БМ-21): залп по умолчанию из пульта в него помещается.
     */
    @Test
    void munitionPerShotAndGradPack() {
        for (WeaponType w : WeaponType.values()) {
            if (w == WeaponType.ROCKET) continue;
            assertEquals(1, w.spec().munition().rounds(), w.getSerializedName());
        }
        assertEquals(LauncherRack.ROCKET.slots(), WeaponSpec.ROCKET.munition().rounds());
        assertEquals(40, WeaponSpec.ROCKET.munition().rounds());
        assertTrue(WeaponSpec.ROCKET.salvo().count() <= WeaponSpec.ROCKET.munition().rounds());
    }
}
