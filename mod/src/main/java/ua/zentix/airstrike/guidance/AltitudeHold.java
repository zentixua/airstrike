package ua.zentix.airstrike.guidance;

/**
 * Удержание высоты: плавный фильтр заданной высоты (как в датапаке: 1/5 разницы за тик) и тангаж, пропорциональный
 * ошибке (1.2° на блок, от 15° вверх до 12° вниз), с ограничением угловой скорости и ускорения ({@link FlightController}).
 */
public final class AltitudeHold {
    /** Наибольший угол набора, °. */
    public static final double MAX_CLIMB = 15;
    /** Наибольший угол снижения, °. */
    private static final double MAX_DESCENT = 12;

    /** Сглаженная заданная высота. */
    private double filter;

    /** Сглаженная заданная высота (сохраняется со снарядом). */
    public double filter() {
        return filter;
    }

    /** Начать с высоты {@code y} (старт, возврат в мир, загрузка сохранения). */
    public void reset(double y) {
        filter = y;
    }

    /** Держать высоту {@code desired}, находясь на {@code y}. */
    public void hold(FlightController flight, double y, double desired, double gain, double maxRate, double maxAccel) {
        filter += (desired - filter) / 5;
        double climb = Math.max(-MAX_DESCENT, Math.min(MAX_CLIMB, (filter - y) * 1.2));
        flight.holdPitch(-climb, gain, maxRate, maxAccel);
    }
}
