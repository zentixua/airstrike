package ua.zentix.airstrike.client.hud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrikesHudTest {
    /** Номера снарядов у крестика цели: подряд идущие — диапазоном, пара — через запятую. */
    @Test
    void numbersCollapseIntoRanges() {
        assertEquals("№3", StrikesHud.numbers(List.of(3)));
        assertEquals("№1, 2", StrikesHud.numbers(List.of(2, 1)));
        assertEquals("№1–4, 7", StrikesHud.numbers(List.of(4, 7, 1, 3, 2)));
        assertEquals("№1, 3–5, 8, 9", StrikesHud.numbers(List.of(9, 5, 1, 8, 4, 3)));
    }

    @Test
    void overlappingLabelsStackDown() {
        List<int[]> placed = new java.util.ArrayList<>();
        placed.add(new int[] {100, 50, 80});
        assertEquals(60, StrikesHud.labelY(placed, 150, 60, 50), "наезжает — строкой ниже");
        assertEquals(50, StrikesHud.labelY(placed, 300, 60, 50), "в стороне — на своём месте");
        placed.add(new int[] {150, 60, 60});
        assertEquals(72, StrikesHud.labelY(placed, 120, 40, 52), "ниже обеих");
    }
}
