package ua.zentix.airstrike.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientNuclearTest {
    /** Часы клиента идут ровно — запаса нет, «задерживается» только после {@link ClientNuclear#DELAY_GRACE_TICKS}. */
    @Test
    void steadyClockNeedsOnlyGrace() {
        ClientNuclear.ClockLead clock = new ClientNuclear.ClockLead();
        for (long t = 0; t < 100; t++) clock.tick(t);
        assertEquals(0, clock.lead(), 1e-9);
        assertFalse(ClientNuclear.overdue(1020, 1000, clock.lead()), "20 тиков после срока — ещё не задержка");
        assertTrue(ClientNuclear.overdue(1000 + ClientNuclear.DELAY_GRACE_TICKS + 1, 1000, clock.lead()));
    }

    /**
     * Сервер отстаёт (10 и 5 тиков в секунду): часы клиента за его секунду убегают на 20–60 тиков и прыгают назад. Отсчёт
     * по часам клиента доходит до нуля раньше подрыва — «задерживается» при этом не показывается.
     */
    @Test
    void laggingServerIsNotADelay() {
        for (int slow : new int[] {2, 4}) { // сервер в 10 и в 5 тиков в секунду
            ClientNuclear.ClockLead clock = new ClientNuclear.ClockLead();
            long detonate = 400, server = 0, client = 0;
            boolean shown = false;
            for (int tick = 1; server < detonate; tick++) {
                client++;
                if (tick % slow == 0) server++;
                // сервер присылает время раз в 20 своих тиков
                if (tick % (20 * slow) == 0) client = server;
                clock.tick(client);
                if (ClientNuclear.overdue(client, detonate, clock.lead())) shown = true;
            }
            assertTrue(clock.lead() >= 20 * (slow - 1) - 1, "запас " + clock.lead());
            assertFalse(shown, "сервер в " + 20 / slow + " тиков/с");
        }
    }

    /** Прыжок назад забывается за полминуты. */
    @Test
    void leadDecays() {
        ClientNuclear.ClockLead clock = new ClientNuclear.ClockLead();
        clock.tick(100);
        clock.tick(80);
        assertEquals(21, clock.lead(), 1e-9);
        for (long t = 81; t < 81 + 600; t++) clock.tick(t);
        assertEquals(0, clock.lead(), 1e-9);
    }
}
