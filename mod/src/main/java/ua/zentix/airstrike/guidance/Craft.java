package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.FlightPhase;

/**
 * Управляемый летательный аппарат, каким его видит автопилот ({@link Autopilot}): датчики (место, скорость, фаза,
 * рельеф впереди) и органы управления (ориентация, скорость, фаза). Без мира Minecraft: у снаряда — сама сущность,
 * в юнит-тестах — модель на модели рельефа.
 */
public interface Craft {
    Vec3 position();

    FlightController flight();

    AltitudeHold altitude();

    /** Скорость, блоков/тик. */
    double speed();

    void setSpeed(double speed);

    FlightPhase phase();

    /** Сколько тиков идёт текущая фаза. */
    int phaseAge();

    void setPhase(FlightPhase phase);

    /**
     * Наибольшая высота рельефа на этих расстояниях впереди по курсу, по горизонтали. Где рельеф не читается (полёт вне
     * мира, неготовый чанк), — низ мира.
     */
    double reliefAhead(double... distances);

    /**
     * Прямая до точки {@code to} свободна от блоков, кроме последних {@code margin} блоков у самой точки (там цель
     * достаёт взрыватель). Где мир не читается (полёт вне мира, неготовый чанк на пути), — свободна: датчик молчит.
     */
    boolean lineClear(Vec3 to, double margin);

    /** Держать высоту {@code desired} ({@link AltitudeHold}). */
    default void holdAltitude(double desired, double gain, double maxRate, double maxAccel) {
        altitude().hold(flight(), position().y, desired, gain, maxRate, maxAccel);
    }
}
