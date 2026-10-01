package ua.zentix.airstrike.client;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarLook;
import ua.zentix.airstrike.client.ClientWeaponSpec.FarTrail;
import ua.zentix.airstrike.client.ClientWeaponSpec.Stage;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Вид снарядов вдали по паспорту: в каких фазах огонь и дым (как вблизи у {@code Exhaust}), как тают шлейфы. */
class FarLookTest {
    private static FarLook far(WeaponType weapon) {
        return ClientWeaponSpec.of(weapon).airframe(false).far();
    }

    private static List<FarLook> all() {
        List<FarLook> looks = new ArrayList<>();
        for (WeaponType w : WeaponType.values()) {
            looks.add(ClientWeaponSpec.of(w).airframe(false).far());
            if (ClientWeaponSpec.of(w).payload() != null) looks.add(ClientWeaponSpec.of(w).airframe(true).far());
        }
        return looks;
    }

    @Test
    void nothingOnPadOrUnderground() {
        for (FarLook look : all()) {
            assertSame(Stage.NONE, look.stage(FlightPhase.READY), "на пусковой до поджига");
            assertSame(Stage.NONE, look.stage(FlightPhase.DRILL), "под землёй");
        }
    }

    @Test
    void rocketBurnsOnlyWhileMotorIsLit() {
        FarLook rocket = far(WeaponType.ROCKET);
        for (FlightPhase ph : List.of(FlightPhase.IGNITION, FlightPhase.BOOST)) {
            assertNotNull(rocket.stage(ph).flame());
            assertNotNull(rocket.stage(ph).trail());
        }
        assertSame(Stage.NONE, rocket.stage(FlightPhase.CRUISE), "выгорел — летит по инерции без следа");
        assertSame(Stage.NONE, rocket.stage(FlightPhase.TERMINAL));
    }

    @Test
    void boostersOfDroneAndMissileBurnThenGoDark() {
        FarLook drone = far(WeaponType.DRONE), missile = far(WeaponType.MISSILE);
        assertNotNull(drone.stage(FlightPhase.BOOST).flame());
        assertNull(drone.stage(FlightPhase.CRUISE).flame(), "поршневой мотор не светит");
        assertTrue(drone.stage(FlightPhase.TERMINAL).trail().alpha() > drone.stage(FlightPhase.CRUISE).trail().alpha(), "в пике выхлоп гуще");
        assertTrue(drone.stage(FlightPhase.BOOST).trail().life() > 5 * drone.stage(FlightPhase.CRUISE).trail().life(), "дым ускорителя висит");

        double booster = missile.stage(FlightPhase.BOOST).flame().brightness();
        double sustainer = missile.stage(FlightPhase.CRUISE).flame().brightness();
        double dive = missile.stage(FlightPhase.TERMINAL).flame().brightness();
        assertTrue(sustainer < dive && dive < booster, "маршевый ТРД тусклый, в пике ярче, ускоритель ярче всех");
        assertTrue(missile.stage(FlightPhase.CRUISE).trail().alpha() < 0.2f, "след ТРД — едва заметная дымка");
    }

    @Test
    void icbmBurnsWholeFlightAndItsTrailHangsForAMinute() {
        FarLook icbm = far(WeaponType.NUKE);
        for (FlightPhase ph : List.of(FlightPhase.IGNITION, FlightPhase.BOOST)) {
            assertNotNull(icbm.stage(ph).flame());
            assertTrue(icbm.stage(ph).trail().life() >= 45 * 20);
        }
    }

    @Test
    void bomberLeavesContrailsWithoutFlameAndLoiterNothing() {
        FarLook b2 = far(WeaponType.BUNKER), bomb = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(true).far();
        assertEquals(52, b2.size(), 1e-9, "размах B-2");
        for (FlightPhase ph : FlightPhase.values()) {
            assertNull(b2.stage(ph).flame());
            assertNull(bomb.stage(ph).flame());
            assertSame(Stage.NONE, far(WeaponType.LOITER).stage(ph), "катапульта и электромотор — без огня и дыма");
        }
        assertNotNull(b2.stage(FlightPhase.CRUISE).trail());
        assertSame(b2.stage(FlightPhase.CRUISE).trail(), b2.stage(FlightPhase.EGRESS).trail(), "след и на отходе");
    }

    @Test
    void bodiesAreNoBiggerThanTheirSizeAndShadowFitsIt() {
        for (FarLook look : all()) {
            assertTrue(look.size() > 0 && look.area() > 0);
            assertTrue(look.area() <= look.size() * look.size() / 2, "средний силуэт меньше квадрата размера");
        }
    }

    @Test
    void trailsGrowFadeAndDriftLikeNearPuffs() {
        for (FarLook look : all()) {
            for (FlightPhase ph : FlightPhase.values()) {
                FarTrail s = look.stage(ph).trail();
                if (s == null) continue;
                assertEquals(s.width0(), s.width(0), 1e-9);
                assertEquals(s.width1(), s.width(s.life()), 1e-9);
                assertTrue(s.width(s.life() / 2.0) > s.width(s.life() / 4.0), "расплывается");
                assertEquals(0, s.opacity(0), 1e-6, "у сопла ещё не проявился");
                assertEquals(s.alpha(), s.opacity(s.fadeIn()), 1e-6, "проявился");
                assertEquals(0, s.opacity(s.life()), 1e-6, "растаял");
                assertTrue(s.expired(s.life()) && !s.expired(s.life() - 1));
                double late = s.fadeFrom() * s.life();
                assertTrue(s.opacity(late + 1) > s.opacity((late + s.life()) / 2), "тает");
                assertEquals(0, s.drift(0), 1e-12);
                assertEquals(s.wind() * 9.0 * 100, s.drift(200) - s.drift(100), 1e-6, "снос — установившейся скоростью клуба");
                assertTrue(s.step() >= 1 && s.step() <= 4);
            }
        }
    }
}
