package ua.zentix.airstrike.strike;

import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;

/**
 * Докуда слышно снаряд. Срезы громкости слоёв его звука (клиент, {@code EngineSound}: дальше среза громкость плавно
 * гаснет за {@link #FADE} блоков) и то, кому сервер шлёт путь снаряда, которого нет у клиента ({@link FlightSounds}), —
 * одни и те же числа, поэтому они здесь, а не в клиентском коде.
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
     * Свист крылатой ракеты, которая идёт на цель. На 0,67 скорости звука ракета догоняет собственный звук: его
     * слышно лишь на d·(1/v − 1/c) раньше, чем она придёт, — с 3000 блоков это ~86 тиков (4,3 с), с 1500 — ~2 с.
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

    /** Дальше этого расстояния снаряд сейчас не слышно ни в одном слое (0 — не слышно вовсе). */
    public static double range(StrikeProjectile p) {
        FlightPhase ph = p.flightPhase();
        boolean launching = ph.onLauncher() || ph.boosterLit();
        double cutoff = switch (p.weapon()) {
            case DRONE -> launching ? BOOSTER : ENGINE;
            case MISSILE -> launching ? BOOSTER : WHISTLE;
            case BUNKER -> p instanceof BomberEntity ? JET : ENGINE;
            case NUKE -> ph.boosterLit() ? ICBM : 0;
            case ROCKET -> launching ? ROCKET_LAUNCH : ROCKET_AIR;
            case LOITER -> ph.onLauncher() ? ENGINE : LOITER_DIVE;
        };
        return cutoff > 0 ? cutoff + FADE : 0;
    }
}
