package ua.zentix.airstrike.gametest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Раскладка GameTest по машинам CI: каждая партия ровно в одной части, у всех машин одна раскладка, части ровные. */
class GameTestShardsTest {
    @Test
    void everyBatchInExactlyOnePart() {
        Map<String, Double> weights = randomWeights(new Random(1), 222);
        for (int count = 1; count <= 8; count++) {
            Map<String, Integer> plan = GameTestShards.assign(weights, count);
            assertEquals(weights.keySet(), plan.keySet());
            for (int part : plan.values()) assertTrue(part >= 0 && part < count, "часть " + part + " из " + count);
        }
    }

    @Test
    void sameOnEveryMachine() {
        Map<String, Double> weights = randomWeights(new Random(2), 100);
        // одинаковые времена — порядок решает имя, а не порядок, в котором партии пришли
        weights.put("tie_b", 5.0);
        weights.put("tie_a", 5.0);
        List<String> shuffled = new ArrayList<>(weights.keySet());
        Collections.shuffle(shuffled, new Random(3));
        Map<String, Double> reordered = new LinkedHashMap<>();
        for (String b : shuffled) reordered.put(b, weights.get(b));
        assertEquals(GameTestShards.assign(weights, 6), GameTestShards.assign(reordered, 6));
    }

    @Test
    void partsEvenWithinOneBatch() {
        // жадная раскладка: часть, получившая последнюю партию, до неё была не больше средней
        Map<String, Double> weights = randomWeights(new Random(4), 222);
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double largest = weights.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        for (int count = 2; count <= 8; count++) {
            double[] load = new double[count];
            Map<String, Integer> plan = GameTestShards.assign(weights, count);
            plan.forEach((b, part) -> load[part] += weights.get(b));
            for (double l : load) assertTrue(l <= total / count + largest + 1e-9, "часть " + l + " с при средней " + total / count);
        }
    }

    @Test
    void unknownBatchWeighsMedian() {
        Map<String, Double> weights = GameTestShards.weights(List.of("a", "b", "c", "new", "new"), Map.of("a", 1.0, "b", 3.0, "c", 50.0));
        assertEquals(Map.of("a", 1.0, "b", 3.0, "c", 50.0, "new", 3.0), weights);
        assertEquals(Map.of("x", 1.0), GameTestShards.weights(List.of("x"), Map.of()));
    }

    /** Как у настоящего GameTest: много коротких партий и несколько долгих. */
    private static Map<String, Double> randomWeights(Random random, int count) {
        Map<String, Double> out = new TreeMap<>();
        for (int i = 0; i < count; i++) out.put("batch_" + i, random.nextInt(10) == 0 ? 10 + random.nextDouble() * 40 : 0.5 + random.nextDouble());
        return out;
    }
}
