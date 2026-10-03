package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {
    private static final Vec3 LAUNCH = new Vec3(0, 64, 0);
    private static final Vec3 TARGET = new Vec3(0, 64, 400);
    private static final Vec3 NORTH_TO_SOUTH = new Vec3(0, 0, 1);

    @Test
    void longFlightGetsSideWaypointOfRequestedLength() {
        // шахед: 50 с × 2.1 бл/тик = 2100 блоков пути, последний участок 300
        Route r = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, 1);
        assertEquals(2, r.size(), "обход сбоку и точка входа");
        assertEquals(2100, r.remaining(LAUNCH, TARGET), 1.0, "длина маршрута");
        Vec3 entry = r.points().get(1);
        assertEquals(100, entry.z, 1e-6, "вход в 300 блоках до цели по направлению захода");
        assertTrue(Math.abs(r.points().get(0).x) > 500, "точка обхода вынесена вбок");
    }

    @Test
    void sideSwitchesWithSign() {
        Route left = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, 1);
        Route right = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, -1);
        assertEquals(-left.points().get(0).x, right.points().get(0).x, 1e-6);
    }

    @Test
    void shortFlightGoesStraightToEntry() {
        Route r = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 100, 300, 1);
        assertEquals(1, r.size(), "только точка входа");
    }

    @Test
    void waypointPassedWhenReachedOrOvershot() {
        Route r = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, 1);
        Vec3 side = r.points().get(0);
        r.update(side.add(5, 0, 5), 20);
        assertEquals(1, r.index(), "точка обхода взята в радиусе захвата");
        // точку входа проскочили мимо радиуса, но за перпендикуляром — тоже взята
        Vec3 entry = r.points().get(1);
        r.update(new Vec3(entry.x + 25, 64, entry.z + 10), 20);
        assertTrue(r.finished(), "дальше — цель");
        assertNull(r.current());
        assertEquals(TARGET.z - entry.z - 10, r.remaining(new Vec3(entry.x, 64, entry.z + 10), TARGET), 1e-6);
    }

    @Test
    void entryBehindLaunchIsTakenWhenOvershot() {
        // полёт короче последнего участка: точка входа позади старта, шахед разворачивается к ней по кругу
        // радиусом ~40 блоков и мог кружить вокруг неё вечно, не входя в радиус захвата
        Vec3 start = new Vec3(0, 64, 200);
        Route r = Route.plan(start, TARGET, NORTH_TO_SOUTH, 200, 300, 1);
        Vec3 entry = r.points().get(0);
        assertEquals(100, entry.z, 1e-6, "вход позади старта");
        r.update(new Vec3(entry.x + 30, 64, entry.z - 25), 20);
        assertTrue(r.finished(), "за точкой входа по ходу участка от старта — взята");
    }

    @Test
    void skipGoesStraightToTarget() {
        Route r = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, 1);
        r.skip();
        assertEquals(400, r.remaining(LAUNCH, TARGET), 1e-6);
    }

    @Test
    void survivesSaving() {
        Route r = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, -1);
        r.update(r.points().get(0), 20);
        Route back = Route.load(r.save());
        assertEquals(r.points(), back.points());
        assertEquals(r.index(), back.index());
        Route orbit = Route.load(Route.plan(new Vec3(0, 64, 200), TARGET, NORTH_TO_SOUTH, 200, 300, 1).save());
        orbit.update(new Vec3(30, 64, 75), 20);
        assertTrue(orbit.finished(), "начало маршрута сохраняется");
    }

    @Test
    void operatorMarkSurvivesGateAndSaving() {
        Route plan = Route.plan(LAUNCH, TARGET, NORTH_TO_SOUTH, 2100, 300, 1);
        assertFalse(plan.byOperator(), "маршрут пуска — не оператора");
        assertFalse(Route.load(plan.after(LAUNCH, LAUNCH).save()).byOperator());
        Route op = Route.operator(List.of(new Vec3(100, 0, 100)), LAUNCH).after(LAUNCH, LAUNCH);
        assertTrue(op.byOperator(), "точка у пусковой перед точками оператора — всё ещё его маршрут");
        assertTrue(Route.load(op.save()).byOperator());
    }
}
