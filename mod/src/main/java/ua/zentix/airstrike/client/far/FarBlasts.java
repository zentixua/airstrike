package ua.zentix.airstrike.client.far;

import ua.zentix.airstrike.net.S2C;

/** Взрывы вдали: вспышка, огненный шар, столб дыма и раскат с задержкой по скорости звука. */
public final class FarBlasts {
    private FarBlasts() {}

    /** Пакет взрыва пришёл (свет — сразу, звук — когда дойдёт). */
    public static void add(S2C.Blast p) {}

    static void tick() {}

    static void collect(FarView view, FarSprites out) {}

    static boolean isEmpty() {
        return true;
    }

    static void reset() {}
}
