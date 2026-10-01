package ua.zentix.airstrike.client.far;

/** Снаряды вдали (сущности у клиента нет): корпус точкой, факел, шлейф — по путям {@code FlightTracks}. */
public final class FarFlightView {
    private FarFlightView() {}

    static void tick() {}

    static void collect(FarView view, FarSprites out) {}

    static boolean isEmpty() {
        return true;
    }

    static void reset() {}
}
