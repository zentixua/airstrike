package ua.zentix.incoming.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhQueueTest {
    private static final long MS = 1_000_000L;

    /** Наименьшая задержка DH 3.3.3 — 250 мс очереди, 1 с до записи участка и 100 мс паузы потока записи: 27 тиков. */
    @Test
    void leadIsDhMinimumLatency() {
        assertEquals(27, DhQueue.LEAD);
        assertTrue(DhQueue.LEAD * 50 >= DhQueue.DEBOUNCE_MS + DhQueue.SAVE_DELAY_MS + DhQueue.SAVE_PAUSE_MS, "раньше наименьшей задержки LOD сменился бы до фронта");
        assertTrue((DhQueue.LEAD - 1) * 50 < DhQueue.DEBOUNCE_MS + DhQueue.SAVE_DELAY_MS + DhQueue.SAVE_PAUSE_MS, "запас сверх наименьшей задержки — опоздание");
    }

    /** Окно — половина наименьшей очереди DH: 1000 на поток его разбора; потоков не знаем — один. */
    @Test
    void windowIsHalfOfSmallestQueue() {
        assertEquals(500, DhQueue.window(1));
        assertEquals(2000, DhQueue.window(4));
        assertEquals(500, DhQueue.window(0));
    }

    /** Отданная копия ждёт подтверждения; подтверждённая уходит из окна, задержка — в счёт; чужое подтверждение не в счёт. */
    @Test
    void ackFreesWindow() {
        DhQueue q = new DhQueue();
        q.sent(1, 0);
        q.sent(2, 0);
        assertEquals(2, q.waiting());
        assertFalse(q.ack(3, 10 * MS), "своё обновление DH — не наша копия");
        assertTrue(q.ack(1, 1500 * MS));
        assertFalse(q.ack(1, 1600 * MS), "второе подтверждение того же чанка");
        assertEquals(1, q.waiting());
        assertTrue(q.ack(2, 2500 * MS));
        assertArrayEquals(new long[] {2, 2, 0, 2000, 2500, 0}, q.take());
        assertArrayEquals(new long[] {0, 0, 0, 0, 0, 0}, q.take(), "счёт — с прошлого вызова");
    }

    /** Без подтверждения (DH только читает, неизменный чанк) копия уходит из окна по сроку, не раньше. */
    @Test
    void unconfirmedLeaveAfterTimeout() {
        DhQueue q = new DhQueue();
        q.sent(1, 0);
        q.sent(2, DhQueue.TIMEOUT_NANOS / 2);
        q.expire(DhQueue.TIMEOUT_NANOS - 1);
        assertEquals(2, q.waiting());
        q.expire(DhQueue.TIMEOUT_NANOS);
        assertEquals(1, q.waiting());
        assertFalse(q.ack(1, DhQueue.TIMEOUT_NANOS + 1), "ушедшая по сроку не подтверждается");
        q.expire(DhQueue.TIMEOUT_NANOS * 2);
        assertEquals(0, q.waiting());
        assertEquals(2, q.take()[2]);
    }
}
