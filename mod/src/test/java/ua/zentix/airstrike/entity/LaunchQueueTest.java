package ua.zentix.airstrike.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Очередь пусков пакета ({@link LaunchQueue}) и поворот пакета ({@link Launcher#turnedYaw}) — общие у прицепа и
 * стационарной пусковой: ячейки по очереди, интервал между поджигами, первый пуск — не раньше подъёма пакета;
 * доворачивается только молчащий пакет и только на заметный угол.
 */
class LaunchQueueTest {
    /** Пакет ракет: 2 ячейки, интервал 16 тиков, поджиг не раньше 12 тиков, ячейка занята 24 тика. */
    private static final LauncherRack RACK = LauncherRack.MISSILE;

    @Test
    void slotsInTurnWithSpacing() {
        LaunchQueue q = new LaunchQueue();
        assertArrayEquals(new int[]{0, 12}, q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks()));
        // вторая ячейка свободна, но поджиг — не раньше интервала после первого
        assertArrayEquals(new int[]{1, 28}, q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks()));
        // обе заняты: первая освободится в 136, интервал после второго — 144
        assertArrayEquals(new int[]{0, 44}, q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks()));
    }

    @Test
    void firstLaunchWaitsForRaisedRack() {
        LaunchQueue q = new LaunchQueue();
        assertArrayEquals(new int[]{0, 100}, q.reserve(RACK, 100, 200, RACK.minReady(), RACK.busyTicks()));
    }

    @Test
    void silentAfterSettling() {
        LaunchQueue q = new LaunchQueue();
        assertTrue(q.silent(0), "новая очередь молчит");
        int start = 100 + q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks())[1];
        assertFalse(q.silent(100), "впереди поджиг");
        assertFalse(q.silent(start + LaunchQueue.SETTLE_TICKS - 1));
        assertTrue(q.silent(start + LaunchQueue.SETTLE_TICKS));
    }

    @Test
    void turnsOnlySilentAndNotByTrifles() {
        Fixed l = new Fixed();
        assertEquals(0, l.turnedYaw(Launcher.TURN_THRESHOLD - 1, 0), "мелкий доворот не нужен");
        assertEquals(40, l.turnedYaw(40, 0), "молчащий доворачивается");
        l.queue().reserve(RACK, 0, 0, RACK.minReady(), RACK.busyTicks());
        assertEquals(0, l.turnedYaw(40, 1), "не молчит — курс прежний");
        l.turnTo(40, 1);
        assertEquals(0, l.yaw());
        l.turnTo(40, 200);
        assertEquals(40, l.yaw());
        assertEquals(LauncherEntity.DEPLOY_TICKS, l.raisingTicks(200), "довёрнутый поднимается заново");
    }

    @Test
    void padRailsAboveBlock() {
        // ось стационарной пусковой — над серединой блока: ячейки пакета симметричны его курсу и выше верха блока
        Vec3 pos = new Vec3(0.5, 64, 0.5);
        for (int slot = 0; slot < LauncherRack.MISSILE.slots(); slot++) {
            Vec3 rail = LauncherMount.PAD.railPoint(pos, 0, WeaponType.MISSILE, slot);
            assertTrue(rail.y > 65, "ячейка " + slot + " ниже верха блока: " + rail);
        }
        Vec3 a = LauncherMount.PAD.railPoint(pos, 0, WeaponType.MISSILE, 0), b = LauncherMount.PAD.railPoint(pos, 0, WeaponType.MISSILE, 1);
        assertEquals(pos.x, (a.x + b.x) / 2, 1e-9);
        assertEquals(a.z, b.z, 1e-9);
    }

    /** Пусковая без мира: курс и очередь. */
    private static final class Fixed implements Launcher {
        private final LaunchQueue queue = new LaunchQueue();
        private float yaw;
        private long deployedAt = Long.MIN_VALUE / 2;

        @Override
        public WeaponType weapon() {
            return WeaponType.MISSILE;
        }

        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public LauncherMount mount() {
            return LauncherMount.PAD;
        }

        @Override
        public long deployedAt() {
            return deployedAt;
        }

        @Override
        public LaunchQueue queue() {
            return queue;
        }

        @Override
        public void turnTo(float yaw, long now) {
            if (turnedYaw(yaw, now) == this.yaw) return;
            this.yaw = yaw;
            deployedAt = now;
        }
    }
}
