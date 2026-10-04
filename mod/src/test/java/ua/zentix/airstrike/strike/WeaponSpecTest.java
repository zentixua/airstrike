package ua.zentix.airstrike.strike;

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
