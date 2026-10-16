package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuinWorkersTest {
    /**
     * Очередь пула руин: сперва задачи чанков, которые видит игрок, потом остальные у волны, потом дальние; внутри
     * ступени — по постановке. Счёт задач ступени снимается и у задачи, брошенной до начала.
     */
    @Test
    void queueGoesByPriorityThenByOrder() {
        List<String> ran = new ArrayList<>();
        PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
        String[][] tasks = {{"far 1", "FAR"}, {"near 1", "NEAR"}, {"far 2", "FAR"}, {"seen 1", "SEEN"}, {"near 2", "NEAR"}, {"seen 2", "SEEN"}};
        List<RuinWorkers.Task<Boolean>> made = new ArrayList<>();
        for (String[] t : tasks) {
            RuinWorkers.Task<Boolean> task = new RuinWorkers.Task<>(() -> ran.add(t[0]), RuinWorkers.Priority.valueOf(t[1]));
            made.add(task);
            queue.add(task);
        }
        assertEquals(2, RuinWorkers.inFlight(RuinWorkers.Priority.SEEN));
        assertEquals(4, RuinWorkers.inFlight(RuinWorkers.Priority.NEAR));
        assertEquals(6, RuinWorkers.inFlight());
        made.get(2).cancel(false);
        for (Runnable r; (r = queue.poll()) != null; ) r.run();
        assertEquals(List.of("seen 1", "seen 2", "near 1", "near 2", "far 1"), ran);
        assertEquals(0, RuinWorkers.inFlight());
    }
}
