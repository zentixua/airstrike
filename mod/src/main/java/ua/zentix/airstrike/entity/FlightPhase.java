package ua.zentix.airstrike.entity;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Фаза полёта снаряда — общая для всех носителей и синхронизированная с клиентом: по ней клиент рисует факел,
 * дым и конденсацию и выбирает звук (рёв ускорителя, раскрутка мотора, свист в пике).
 * <p>
 * Типичный путь: шахед и ракета — READY → IGNITION → BOOST → CLIMB → CRUISE → (POP_UP) → TERMINAL;
 * B-2 — CRUISE → EGRESS, его бомба — TERMINAL → DRILL; МБР — IGNITION → BOOST.
 */
public enum FlightPhase {
    /** Стоит на пусковой, ускоритель ещё не горит. */
    READY,
    /** Ускоритель зажжён, снаряд ещё на направляющей: облако дыма и пламя у пусковой. */
    IGNITION,
    /** Ускоритель горит: сход с направляющей, разгон и подъём. */
    BOOST,
    /** Ускоритель сброшен; маршевый двигатель (винт шахеда, турбина ракеты) набирает обороты, набор высоты и доворот на курс. */
    CLIMB,
    /** Маршевый полёт по маршруту. */
    CRUISE,
    /** Горка перед пикированием (крылатая ракета). */
    POP_UP,
    /** Конечный участок: пикирование на цель. */
    TERMINAL,
    /** Бетонобойная бомба бурит грунт. */
    DRILL,
    /** Бомбардировщик после сброса уходит от цели. */
    EGRESS,
    /** Барражирование над районом цели: круги в ожидании цели. */
    LOITER;

    private static final FlightPhase[] VALUES = values();

    public static FlightPhase byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : CRUISE;
    }

    public static FlightPhase byName(String name) {
        for (FlightPhase p : VALUES) {
            if (p.getSerializedName().equals(name)) return p;
        }
        return CRUISE;
    }

    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Название фазы для игрока (HUD, камера снаряда, карта оператора). */
    public Component displayName() {
        return Component.translatable("airstrike.phase." + getSerializedName());
    }

    /** Горит стартовый ускоритель (пламя, густой дым, рёв). */
    public boolean boosterLit() {
        return this == IGNITION || this == BOOST;
    }

    /** Ещё на пусковой. */
    public boolean onLauncher() {
        return this == READY || this == IGNITION;
    }

    /** Стартовый участок на ускорителе: столкновения с блоками не считаются (сход с направляющей сквозь листву). */
    public boolean launching() {
        return ordinal() <= BOOST.ordinal();
    }
}
