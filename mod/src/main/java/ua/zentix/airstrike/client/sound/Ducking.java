package ua.zentix.airstrike.client.sound;

/**
 * Моторы снарядов на миг уходят вниз, когда до уха доходит громкий взрыв, и возвращаются за секунду с небольшим
 * (без Minecraft, проверяется юнит-тестом). Так сводят звук в играх с «окном громкости» (HDR-звук): громкое на миг
 * задаёт окно, тихое под ним приглушается — взрыв не тонет в моторах соседних снарядов залпа. Громче файла
 * Minecraft звук не играет, поэтому место взрыву освобождают моторы, а не взрыв пережимается.
 */
final class Ducking {
    /** Взрыв у уха тише этого (LUFS) моторы не трогает, с {@link #FULL} — приглушает на всю {@link #DEPTH}. */
    static final double FROM = -24, FULL = -15;
    /** Глубина, дБ. */
    static final double DEPTH = 8;
    /** Столько тиков приглушение держится, потом отпускает на {@link #RELEASE} дБ за тик. */
    static final int HOLD = 4;
    static final double RELEASE = 0.3;

    private double db;
    private int hold;

    /** До уха дошёл взрыв с громкостью прямого звука lufs. */
    void blast(double lufs) {
        double t = Math.max(0, Math.min(1, (lufs - FROM) / (FULL - FROM)));
        double depth = DEPTH * t * t * (3 - 2 * t);
        if (depth > db) {
            db = depth;
            hold = HOLD;
        }
    }

    /** Раз в тик. */
    void tick() {
        if (hold > 0) hold--;
        else db = Math.max(0, db - RELEASE);
    }

    /** Множитель громкости моторов. */
    float factor() {
        return (float) Math.pow(10, -db / 20);
    }

    void reset() {
        db = 0;
        hold = 0;
    }
}
