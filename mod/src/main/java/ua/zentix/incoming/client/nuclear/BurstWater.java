package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.Locale;

/**
 * Вода под подрывом ({@link NukeWater#resolve}): есть ли она под эпицентром и насколько подрыв «водяной»
 * ({@link WaterModel#wetBurst}), пока не решено — заново каждый тик, потом не меняется: столб воды не появляется и не
 * пропадает посреди подрыва. Столб с короной — в объёме гриба ({@link MushroomField}), базисная волна — в пелене
 * ({@link PallField#water}).
 */
final class BurstWater {
    /** Вода под эпицентром 0..1. */
    private double under;
    /** Насколько подрыв «водяной» 0..1 ({@link WaterModel#wetBurst} × вода под эпицентром). */
    private double wet;
    /** Откуда известна вода под эпицентром; null — ещё решается. */
    private NukeWater.Source source;

    void set(double under, double wet) {
        this.under = under;
        this.wet = wet;
    }

    double under() {
        return under;
    }

    double wet() {
        return wet;
    }

    /** Вода под эпицентром решена: дальше не меняется. */
    void settle(NukeWater.Source source) {
        this.source = source;
    }

    boolean settled() {
        return source != null;
    }

    /** Откуда известна вода под эпицентром (для строки сценария); «?» — ещё решается. */
    String source() {
        return source == null ? "?" : source.name().toLowerCase(Locale.ROOT);
    }
}
