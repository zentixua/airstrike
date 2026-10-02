package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.flight.FlightTrack;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.guidance.Ballistics;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.Hearing;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Слои звука на всём пути снаряда: свист крылатой ракеты на подлёте, вой снаряда РСЗО по всей дуге. */
class EngineSoundTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Vec3 EAR = Vec3.ZERO;
    /** Маршевая скорость ракеты, блоков/тик. */
    private static final double V = WeaponSpec.MISSILE.airframe().cruiseSpeed();

    /** Крылатая ракета с {@code from} блоков прямо на слушателя; цель — {@code aim}. */
    private static FlightTrack missile(double from, Vec3 aim) {
        return missile(from, aim, 100);
    }

    /** То же, {@code ticks} тиков полёта на маршевой скорости (дальше v·ticks − from блоков она уходит за слушателя). */
    private static FlightTrack missile(double from, Vec3 aim, int ticks) {
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        for (int k = 0; k <= ticks; k++) {
            double x = from - V * k;
            t.record(k, new S2C.FarFlight(ID, WeaponType.MISSILE.id(), false, false, true, new Vec3(x, 12, 0), new Vec3(-V, 0, 0), 90, 0, 0,
                    FlightPhase.CRUISE.ordinal(), 200 + k, aim));
        }
        return t;
    }

    private static EngineSound.Tone heard(EngineSound.Layer layer, FlightTrack t, double now) {
        double te = Acoustics.emissionTime(t, now, EAR.x, EAR.y, EAR.z);
        return layer.tone(t, Emission.at(t, te, EAR, false));
    }

    /** Когда до слушателя у начала координат доходит звук ракеты, вылетевшей с {@code from}, из точки в {@code d} блоках. */
    private static double heardFrom(double from, double d) {
        return (from - d) / V + d / Acoustics.SPEED;
    }

    @Test
    void missileWhistlesTheWholeApproachFromAfar() {
        // ракета на последнем участке, слушатель у цели: свист слышно с 850 блоков (раньше — только с 260), и он нарастает
        FlightTrack t = missile(1200, EAR, 300);
        double te = Acoustics.emissionTime(t, heardFrom(1200, 850), EAR.x, EAR.y, EAR.z);
        Emission e = Emission.at(t, te, EAR, false);
        double far = EngineSound.Layer.MISSILE_WHISTLE.tone(t, e).gain();
        assertTrue(e.aimDistance() > 800 && far > 0.1, "в " + Math.round(e.aimDistance()) + " блоках от цели свист " + far);
        assertTrue(heard(EngineSound.Layer.MISSILE_WHISTLE, t, heardFrom(1200, 600)).gain() > far, "на подлёте свист нарастает");
    }

    @Test
    void missileWhistleWarnsFromThreeKilometres() {
        // путь по пакетам (раз в 2 тика), как у ракеты вне мира: с 3000 блоков на слушателя у цели; свист слышно
        // до прихода ракеты почти на d·(1/v − 1/c) (≈ 575 тиков), и история пути не теряет слышимую точку
        double from = Hearing.WHISTLE + Hearing.FADE, v = V;
        int arrival = (int) (from / v);
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        int first = -1;
        double firstDistance = 0;
        for (int k = 0; k <= arrival; k++) {
            if (k % 2 == 0) {
                t.record(k, new S2C.FarFlight(ID, WeaponType.MISSILE.id(), false, false, true, new Vec3(from - v * k, 12, 0), new Vec3(-v, 0, 0), 90, 0, 0,
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
        // слышимая точка — в firstDistance блоках: звук оттуда опережает ракету на d·(1/v − 1/c), с запасом 10 %
        double lead = firstDistance * (1 / v - 1 / Acoustics.SPEED);
        assertTrue(arrival - first >= 0.9 * lead, "свист за " + (arrival - first) + " тиков до прихода ракеты, ожидание ~" + Math.round(lead));
    }

    @Test
    void missileOnDetourDoesNotWhistle() {
        // тот же пролёт над слушателем, но цель в стороне (ракета идёт по обходу маршрута): свиста подлёта нет
        FlightTrack t = missile(1200, new Vec3(-300, 0, 800), 300);
        assertEquals(0, heard(EngineSound.Layer.MISSILE_WHISTLE, t, heardFrom(1200, 450)).gain(), 1e-12);
    }

    @Test
    void missileEngineDoesNotDipOnPassOrLayerHandoff() {
        // ракета проходит в 12 блоках над головой и уходит дальше (цель далеко за слушателем): мощность мотора (все его
        // слои: спереди, сзади, в пике, вдали — разные записи, складываются по мощности) на подлёте только растёт,
        // вслед только падает — без провала над головой и на смене ближнего гула дальним
        FlightTrack t = missile(200, new Vec3(-3000, 0, 0), 110);
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
        FlightTrack t = new FlightTrack(ID, WeaponType.ROCKET, false);
        for (int k = 0; k <= ticks; k++) {
            Vec3 p = Ballistics.at(Vec3.ZERO, v0, k), v = Ballistics.at(Vec3.ZERO, v0, k + 1).subtract(p);
            FlightPhase ph = k < 40 ? FlightPhase.BOOST : v.y < 0 ? FlightPhase.TERMINAL : FlightPhase.CRUISE;
            t.record(k, new S2C.FarFlight(ID, WeaponType.ROCKET.id(), false, false, true, p, v, 90, 0, 0,
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
    void towardAimIsAShareOfTheHeading() {
        assertEquals(1, Emission.toward(10, 0, 500, 70), 1e-12, "курс 8° от цели");
        assertEquals(0, Emission.toward(10, 0, 500, 420), 1e-12, "курс 40° от цели — плечо обхода");
        assertEquals(0, Emission.toward(-10, 0, 500, 0), 1e-12, "от цели");
        assertEquals(1, Emission.toward(0, 0, 0.5, 0), 1e-12, "над самой целью");
        // поворот маршрута: доля убывает плавно, на градус курса — не больше чем на десятую
        double prev = 1;
        for (int deg = 0; deg <= 90; deg++) {
            double a = Math.toRadians(deg), w = Emission.toward(Math.cos(a), Math.sin(a), 500, 0);
            assertTrue(w <= prev + 1e-12 && prev - w < 0.1, deg + "°: " + prev + " → " + w);
            prev = w;
        }
    }

    @Test
    void whistleFadesThroughThePassInsteadOfCuttingOff() {
        // ракета идёт на цель за слушателем и проходит в 60 блоках сбоку: свист подлёта стихает за пролёт, а не обрывается
        // в ближайшей точке (раньше — с полной громкости до шума обтекания за тик)
        Vec3 ear = new Vec3(0, 12, 60);
        FlightTrack t = missile(600, new Vec3(-700, 0, 0), 300);
        double prev = -1, peak = 0, worst = 0;
        for (double now = 40; now <= 220; now += 1) {
            double te = Acoustics.emissionTime(t, now, ear.x, ear.y, ear.z);
            double g = EngineSound.Layer.MISSILE_WHISTLE.tone(t, Emission.at(t, te, ear, false)).gain();
            if (prev >= 0) worst = Math.max(worst, Math.abs(g - prev));
            peak = Math.max(peak, g);
            prev = g;
        }
        assertTrue(peak > 0.5, "свист на подлёте " + peak);
        assertTrue(worst < 0.15 * peak, "скачок громкости свиста за тик " + worst + " при пике " + peak);
    }
}
