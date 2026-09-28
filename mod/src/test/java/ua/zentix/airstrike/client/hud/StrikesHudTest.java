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
}
