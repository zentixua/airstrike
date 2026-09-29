package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.guidance.Ballistics;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.Hearing;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Слои звука на всём пути снаряда: свист крылатой ракеты на подлёте, вой снаряда РСЗО по всей дуге. */
class EngineSoundTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Vec3 EAR = Vec3.ZERO;

    /** Крылатая ракета с {@code from} блоков прямо на слушателя; цель — {@code aim}. */
    private static SourceTrack missile(double from, Vec3 aim) {
        return missile(from, aim, 100);
    }

    /** То же, {@code ticks} тиков полёта (дальше 11.5·ticks − from блоков она уходит за слушателя). */
    private static SourceTrack missile(double from, Vec3 aim, int ticks) {
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        for (int k = 0; k <= ticks; k++) {
            double x = from - 11.5 * k;
            t.record(k, new S2C.HeardFlight(ID, WeaponType.MISSILE.id(), false, false, new Vec3(x, 12, 0), new Vec3(-11.5, 0, 0), 90, 0,
                    FlightPhase.CRUISE.ordinal(), 200 + k, aim));
        }
        return t;
    }

    private static EngineSound.Tone heard(EngineSound.Layer layer, SourceTrack t, double now) {
        double te = Acoustics.emissionTime(t, now, EAR.x, EAR.y, EAR.z);
        return layer.tone(t, Emission.at(t, te, EAR, false));
    }

    @Test
    void missileWhistlesTheWholeApproachFromAfar() {
        // ракета на последнем участке, слушатель у цели: свист слышно с 850 блоков (раньше — только с 260), и он нарастает
        SourceTrack t = missile(1200, EAR);
        double te = Acoustics.emissionTime(t, 80, EAR.x, EAR.y, EAR.z);
        Emission e = Emission.at(t, te, EAR, false);
        double far = EngineSound.Layer.MISSILE_WHISTLE.tone(t, e).gain();
        assertTrue(e.aimDistance() > 800 && far > 0.1, "в " + Math.round(e.aimDistance()) + " блоках от цели свист " + far);
        assertTrue(heard(EngineSound.Layer.MISSILE_WHISTLE, t, 95).gain() > far, "на подлёте свист нарастает");
    }

    @Test
    void missileWhistleWarnsFromThreeKilometres() {
        // путь по пакетам (раз в 2 тика), как у ракеты вне мира: с 3000 блоков на слушателя у цели; свист слышно
        // до прихода ракеты не меньше 4 с (d·(1/v − 1/c) ≈ 86 тиков), и история пути не теряет слышимую точку
        double from = Hearing.WHISTLE + Hearing.FADE, v = 11.5;
        int arrival = (int) (from / v);
        SourceTrack t = new SourceTrack(ID, WeaponType.MISSILE, false);
        int first = -1;
        double firstDistance = 0;
        for (int k = 0; k <= arrival; k++) {
            if (k % 2 == 0) {
                t.record(k, new S2C.HeardFlight(ID, WeaponType.MISSILE.id(), false, false, new Vec3(from - v * k, 12, 0), new Vec3(-v, 0, 0), 90, 0,
                        FlightPhase.CRUISE.ordinal(), 200 + k, EAR));
            }
            double te = Acoustics.emissionTime(t, k, EAR.x, EAR.y, EAR.z);
            Emission e = Emission.at(t, te, EAR, false);
            if (first < 0 && te > t.start() + 1 && EngineSound.Layer.MISSILE_WHISTLE.tone(t, e).gain() >= 0.02) {
                first = k;
                firstDistance = e.distance();
            }
        }
        assertTrue(first >= 0, "свиста не было");
        assertTrue(firstDistance > 2800, "свист слышно с " + Math.round(firstDistance) + " блоков");
        assertTrue(arrival - first >= 80, "свист за " + (arrival - first) + " тиков до прихода ракеты");
    }

    @Test
    void missileOnDetourDoesNotWhistle() {
        // тот же пролёт над слушателем, но цель в стороне (ракета идёт по обходу маршрута): свиста подлёта нет
        SourceTrack t = missile(1200, new Vec3(-300, 0, 800));
        assertEquals(0, heard(EngineSound.Layer.MISSILE_WHISTLE, t, 80).gain(), 1e-12);
    }

    @Test
    void missileEngineDoesNotDipOnPassOrLayerHandoff() {
        // ракета проходит в 12 блоках над головой и уходит дальше (цель далеко за слушателем): мощность мотора (все его
        // слои: спереди, сзади, в пике, вдали — разные записи, складываются по мощности) на подлёте только растёт,
        // вслед только падает — без провала над головой и на смене ближнего гула дальним
        SourceTrack t = missile(600, new Vec3(-3000, 0, 0), 110);
        EngineSound.Layer[] engine = {EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                EngineSound.Layer.MISSILE_FAR};
        double prev = -1;
        boolean prevApproaching = true;
        int passed = 0;
        for (double now = 5; now <= 110; now += 0.5) {
            double te = Acoustics.emissionTime(t, now, EAR.x, EAR.y, EAR.z);
            Emission e = Emission.at(t, te, EAR, false);
            double power = 0;
            for (EngineSound.Layer l : engine) power += Math.pow(l.tone(t, e).gain(), 2);
            if (prev >= 0 && e.approaching() == prevApproaching) {
                String at = "тик " + now + ", " + Math.round(e.distance()) + " блоков, " + (e.approaching() ? "подлёт" : "вслед");
                if (e.approaching()) assertTrue(power >= prev - 1e-9, at + ": тише, чем тиком раньше: " + prev + " → " + power);
                else assertTrue(power <= prev + 1e-9, at + ": громче, чем тиком раньше: " + prev + " → " + power);
            }
            if (!e.approaching()) passed++;
            prev = power;
            prevApproaching = e.approaching();
        }
        assertTrue(passed > 40, "ракета ушла за слушателя: отсчётов вслед " + passed);
    }

    @Test
    void shareKeepsPowerOfTwoLayers() {
        for (double w = 0; w <= 1; w += 0.05) {
            double a = EngineSound.share(w), b = EngineSound.share(1 - w);
            assertEquals(1, a * a + b * b, 1e-12);
        }
    }

    @Test
    void rocketHowlsAcrossTheWholeArc() {
        // снаряд РСЗО на 1200 блоков (вершина дуги ~360 блоков): после выгорания двигателя он не замолкает — вой слышно
        // у пусковой, под дугой и у цели везде, докуда слышно и двигатель
        double range = 1200;
        int ticks = Ballistics.ticksFor(Vec3.ZERO, new Vec3(range, 0, 0), 50, 50);
        Vec3 v0 = Ballistics.launchVelocity(Vec3.ZERO, new Vec3(range, 0, 0), ticks);
        SourceTrack t = new SourceTrack(ID, WeaponType.ROCKET, false);
        for (int k = 0; k <= ticks; k++) {
            Vec3 p = Ballistics.at(Vec3.ZERO, v0, k), v = Ballistics.at(Vec3.ZERO, v0, k + 1).subtract(p);
            FlightPhase ph = k < 40 ? FlightPhase.BOOST : v.y < 0 ? FlightPhase.TERMINAL : FlightPhase.CRUISE;
            t.record(k, new S2C.HeardFlight(ID, WeaponType.ROCKET.id(), false, false, p, v, 90, 0,
                    ph.ordinal(), k, new Vec3(range, 0, 0)));
        }
        int heard = 0;
        for (double x : new double[] {-10, range / 4, range / 2, range + 5}) {
            Vec3 ear = new Vec3(x, 2, 0);
            for (int now = 0; now <= ticks + 100; now += 5) {
                double te = Acoustics.emissionTime(t, now, ear.x, ear.y, ear.z);
                if (te < 42 || te > ticks) continue;
                Emission e = Emission.at(t, te, ear, false);
                if (e.distance() > Hearing.ROCKET_AIR) continue;
                double gain = EngineSound.Layer.ROCKET_AIR.tone(t, e).gain();
                assertTrue(gain > 0.02, "слушатель у " + x + ", тик " + now + ": в " + Math.round(e.distance()) + " блоках вой " + gain);
                heard++;
            }
        }
        assertTrue(heard > 50, "проверено отсчётов: " + heard);
    }

    @Test
    void towardAimMeansHeadingForItHorizontally() {
        assertTrue(Emission.toward(10, 0, 500, 100), "курс 11° от цели");
        assertTrue(!Emission.toward(10, 0, 500, 500), "курс 45° от цели — обход");
        assertTrue(!Emission.toward(-10, 0, 500, 0), "от цели");
        assertTrue(Emission.toward(0, 0, 0.5, 0), "над самой целью");
    }
}
