package ua.zentix.incoming.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Места снаряда у клиента по тикам ({@link ArrivalQueue}): снаряд летит по 2 блока за тик сервера, а пакеты тиков сервера
 * приходят в тики клиента неровно — ни одного, два, один. Снаряд у клиента идёт ровно, по 2 блока за тик.
 */
class ArrivalQueueTest {
    private static ArrivalQueue.Sample at(double x) {
        return new ArrivalQueue.Sample(x, 64, 0, 0, 0);
    }

    /** Тики клиента: сколько пакетов пришло в каждый; ответ — место снаряда после каждого тика. */
    private static List<Double> play(int... packets) {
        ArrivalQueue q = new ArrivalQueue();
        List<Double> out = new ArrayList<>();
        double x = 0, sent = 0;
        for (int n : packets) {
            for (int i = 0; i < n; i++) q.add(at(sent += 2));
            ArrivalQueue.Sample s = q.next(x, 64, 0, 0, 0);
            if (s != null) x = s.x();
            out.add(x);
        }
        return out;
    }

    @Test
    void evenPacketsPassAsIs() {
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0), play(1, 1, 1, 1));
    }

    @Test
    void missedTickGoesOnByLastStep() {
        // без очереди: 2, 2, 6, 8 — стоит тик и прыгает на два шага
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0), play(1, 0, 2, 1));
    }

    @Test
    void burstQueuesOneTickBehind() {
        // два пакета разом: снаряд идёт по шагу, отставая на тик, пока пропуск не съест запас
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0, 10.0, 12.0), play(1, 2, 1, 1, 0, 1));
    }

    @Test
    void longStallStandsAfterDebt() {
        // сервер стоит 4 тика: два тика прогнозом, дальше — стоять; потом догоняет пакетами разом
        List<Double> x = play(1, 1, 0, 0, 0, 0, 5, 1);
        assertEquals(List.of(2.0, 4.0, 6.0, 8.0, 8.0, 8.0), x.subList(0, 6));
        // за долг два пакета пропущены, лишние старые — тоже: не дальше тика от сервера
        assertEquals(16.0, x.get(7), 2.0);
    }

    @Test
    void jumpResetsQueue() {
        ArrivalQueue q = new ArrivalQueue();
        q.add(at(2));
        assertEquals(2.0, q.next(0, 64, 0, 0, 0).x());
        assertEquals(4.0, q.next(2, 64, 0, 0, 0).x());  // в долг
        q.add(at(500));
        ArrivalQueue.Sample s = q.next(4, 64, 0, 0, 0);
        assertEquals(500.0, s.x());
        assertEquals(500.0, q.latest().x());
    }

    @Test
    void nothingYetStands() {
        assertNull(new ArrivalQueue().next(0, 64, 0, 0, 0));
    }
}
