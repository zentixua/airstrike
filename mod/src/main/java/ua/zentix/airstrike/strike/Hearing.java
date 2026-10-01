package ua.zentix.airstrike.strike;

import ua.zentix.airstrike.entity.StrikeProjectile;

/**
 * Докуда слышно снаряд. Срезы громкости слоёв его звука (клиент, {@code EngineSound}: дальше среза громкость плавно
 * гаснет за {@link #FADE} блоков) и то, кому сервер шлёт путь снаряда, которого нет у клиента ({@link FarFlights}), —
 * одни и те же числа, поэтому они здесь, а не в клиентском коде. Какой срез у снаряда в какой фазе — в паспорте
 * ({@link WeaponSpec.Airframe#audible}).
 */
public final class Hearing {
    /** За срезом громкость спадает до нуля на этом расстоянии. */
    public static final double FADE = 80;

    /** Мотор шахеда, турбина ракеты, падающая бомба. */
    public static final double ENGINE = 300;
    /** Четыре двигателя B-2 вблизи. */
    public static final double BOMBER = 330;
    /** Дальний гул реактивного двигателя (ракета, B-2). */
    public static final double JET = 1500;
    /**
     * Свист крылатой ракеты, которая идёт на цель. Звук обгоняет ракету (0,23 скорости звука) на d·(1/v − 1/c):
     * с 3000 блоков её слышно за ~575 тиков (29 с) до прихода, с 500 (последний прямой участок) — за ~5 с.
     */
    public static final double WHISTLE = 3000;
    /** Электромотор барражирующего и вой винта в его пике. */
    public static final double LOITER = 200, LOITER_DIVE = 250;
    /** Стартовый ускоритель шахеда и ракеты, его разовый рёв у пусковой. */
    public static final double BOOSTER = 600;
    /** Двигатель снаряда РСЗО, пока горит, и разовый сход каждого снаряда с трубы. */
    public static final double ROCKET_BOOSTER = 500, ROCKET_LAUNCH = 900;
    /** Крылатая ракета мимо слушателя: рассекаемый воздух. */
    public static final double AIRFLOW = 350;
    /**
     * Снаряд РСЗО по инерции: вой воздуха слышно так же далеко, как горевший перед этим двигатель, — после выгорания
     * полёт не замолкает.
     */
    public static final double ROCKET_AIR = ROCKET_BOOSTER;
    /** Ступень МБР. */
    public static final double ICBM = 1500;

    private Hearing() {}

    /** Дальше этого расстояния снаряд сейчас не слышно ни в одном слое (0 — не слышно вовсе): по паспорту его аппарата. */
    public static double range(StrikeProjectile p) {
        double cutoff = p.airframe().audible().applyAsDouble(p.flightPhase());
        return cutoff > 0 ? cutoff + FADE : 0;
    }
}
