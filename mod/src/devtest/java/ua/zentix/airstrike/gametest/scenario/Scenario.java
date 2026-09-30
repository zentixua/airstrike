package ua.zentix.airstrike.gametest.scenario;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Сценарий полёта: как пущен снаряд × какая цель × что с ней случилось в полёте × зерно. Всё, что сценарий выбирает
 * случайно (направление захода, дальность, длина маршрута, миг и величина возмущения, холм), — из зерна
 * ({@link #params}), поэтому один и тот же сценарий в любом прогоне летит одинаково: на этом стоит эталон траекторий.
 */
public record Scenario(Launch launch, TargetKind target, Disturbance disturbance, int seed) {
    /** Как пущен снаряд. {@code rail} — с пусковой в мире (у стреляющего), иначе заход издалека, вне мира. */
    public enum Launch {
        DRONE_FAR(WeaponType.DRONE, false),
        DRONE_RAIL(WeaponType.DRONE, true),
        MISSILE_FAR(WeaponType.MISSILE, false),
        MISSILE_RAIL(WeaponType.MISSILE, true),
        LOITER_FAR(WeaponType.LOITER, false),
        LOITER_RAIL(WeaponType.LOITER, true),
        ROCKET_FAR(WeaponType.ROCKET, false),
        ROCKET_TUBE(WeaponType.ROCKET, true),
        BOMBER(WeaponType.BUNKER, false),
        ICBM(WeaponType.NUKE, true);

        public final WeaponType weapon;
        public final boolean rail;

        Launch(WeaponType weapon, boolean rail) {
            this.weapon = weapon;
            this.rail = rail;
        }

        /** Управляемый: держит цель, следит за ней и принимает перенацеливание. */
        boolean guided() {
            return weapon == WeaponType.DRONE || weapon == WeaponType.MISSILE || weapon == WeaponType.LOITER;
        }
    }

    /** Цель. */
    public enum TargetKind {
        /** Точка на земле. */
        POINT,
        /** Моб без ИИ, которого сценарий водит по рельсу туда-обратно шагом. */
        MOB,
        /**
         * Игрок в полёте: заглушка с UUID и позицией (стойка), которую сценарий водит по кругу, как игрока на элитрах.
         * Настоящего игрока GameTest не даёт: мок-игрок ломается о пакеты Create (CLAUDE.md).
         */
        PLAYER,
        /** Аппарат Sable: собран из досок над целью (только когда Sable в сборке). */
        CRAFT;

        boolean entity() {
            return this == MOB || this == PLAYER;
        }
    }

    /** Что случилось с целью в полёте (или что стоит на пути). */
    public enum Disturbance {
        NONE,
        /** Цель пропала (умерла, вышла из игры). */
        TARGET_GONE,
        /** Цель телепортировалась на 300 блоков. */
        TELEPORT_NEAR,
        /** Цель телепортировалась на 3000 блоков. */
        TELEPORT_FAR,
        /** Оператор перенацелил снаряд из камеры на точку в сотнях блоков. */
        RETARGET,
        /** Холм на курсе последних сотен блоков (в мире, где снаряд следует рельефу). */
        HILL,
        /** Цель на дне котлована: рельеф вокруг выше неё. */
        QUARRY,
        /**
         * Оператор на подлёте перенацелил снаряд на точку сбоку от него, внутри круга разворота: прямо её не достать,
         * снаряд должен уйти и зайти снова, а не кружить вокруг неё.
         */
        BESIDE;

        /** Возмущение в полёте (а не рельеф, построенный заранее). */
        boolean inFlight() {
            return this == TARGET_GONE || this == TELEPORT_NEAR || this == TELEPORT_FAR || this == RETARGET || this == BESIDE;
        }
    }

    /** Имя теста: «оружие.цель.возмущение.зерно». */
    public String id() {
        return (launch + "." + target + "." + disturbance + "." + seed).toLowerCase(Locale.ROOT);
    }

    /**
     * Сценарий в эталоне траекторий: зерно 0 (набор обычного прогона) и не аппарат — физику Sable мод не ведёт,
     * а она решает, где аппарат окажется к подлёту.
     */
    boolean baselined() {
        return seed == 0 && target != TargetKind.CRAFT && !knownIssue();
    }

    /**
     * Известный изъян мода, который чинит открытый PR: такой сценарий — необязательный тест (падение видно в логе, но
     * прогон не красный) и не в эталоне. Шахед в пике не уходит на второй заход, когда цель оказалась в круге разворота,
     * и бьёт в землю в ~60 блоках от неё — чинит #148 («Дроны после потери цели»); после его слияния — убрать отсюда
     * и записать эталон заново.
     */
    boolean knownIssue() {
        return launch.weapon == WeaponType.DRONE && disturbance == Disturbance.BESIDE;
    }

    /**
     * Место сценария в полном пространстве: своя площадка в мире ({@link ScenarioSite}) и зерно параметров не зависят
     * от набора прогона. Возмущение — старший разряд: новое (в конец перечисления) не сдвигает прежние сценарии, и эталон
     * остаётся верным; новое оружие или вид цели сдвигают — эталон записать заново.
     */
    int cell() {
        int combo = (disturbance.ordinal() * Launch.values().length + launch.ordinal()) * TargetKind.values().length + target.ordinal();
        return combo * MAX_SEEDS + seed;
    }

    /** Больше зёрен на сочетание не бывает: площадки сценариев считаются от этого числа. */
    static final int MAX_SEEDS = 16;

    /**
     * Сочетание имеет смысл. Неуправляемые (РСЗО, B-2) бьют по точке и за целью не следят; МБР — только старт
     * (подрывает запланированный удар, не она); аппарат — только когда Sable в сборке.
     */
    static boolean valid(Launch launch, TargetKind target, Disturbance d, boolean sable) {
        if (target == TargetKind.CRAFT && !sable) return false;
        if (launch == Launch.ICBM) return target == TargetKind.POINT && d == Disturbance.NONE;
        if (launch.weapon == WeaponType.ROCKET) {
            return target == TargetKind.POINT && (d == Disturbance.NONE || d == Disturbance.HILL || d == Disturbance.QUARRY)
                    || target == TargetKind.MOB && d == Disturbance.NONE;
        }
        if (launch == Launch.BOMBER) {
            return target == TargetKind.POINT && (d == Disturbance.NONE || d == Disturbance.RETARGET || d == Disturbance.HILL || d == Disturbance.QUARRY);
        }
        return switch (d) {
            case NONE, RETARGET -> true;
            case TARGET_GONE, TELEPORT_NEAR, TELEPORT_FAR -> target.entity();
            case HILL, QUARRY -> target == TargetKind.POINT || target == TargetKind.MOB;
            case BESIDE -> target == TargetKind.POINT;
        };
    }

    /** Все сценарии с зёрнами {@code 0..seeds-1}. */
    static List<Scenario> all(int seeds, boolean sable) {
        List<Scenario> out = new ArrayList<>();
        for (Launch l : Launch.values())
            for (TargetKind t : TargetKind.values())
                for (Disturbance d : Disturbance.values())
                    if (valid(l, t, d, sable)) for (int s = 0; s < seeds; s++) out.add(new Scenario(l, t, d, s));
        return out;
    }

    /**
     * Параметры из зерна. Длины — как у боевого пуска ({@code StrikeService}): время полёта в пределах настроек,
     * последний прямой участок, дальность пусковой от цели.
     *
     * @param approach   направление последнего участка (куда летит снаряд у цели), единичный горизонтальный
     * @param flightTime время полёта по плану, с (шахед, ракета, B-2)
     * @param entry      длина последнего прямого участка, блоков
     * @param side       обход слева (+1) или справа (−1)
     * @param standoff   дальность места пуска от цели, блоков
     * @param readyTicks сколько снаряд стоит на пусковой до поджига
     * @param when       доля плана полёта, на которой случается возмущение
     * @param shift      на сколько блоков уводит цель перенацеливание или телепорт
     * @param shiftDir   куда (единичный горизонтальный)
     * @param size       высота холма или глубина котлована, блоков
     * @param place      холм: сколько блоков от цели до гребня; котлован: радиус дна
     * @param mobSpeed   шаг моба, блоков/тик; игрока — скорость полёта
     * @param orbit      радиус круга, по которому летает «игрок»
     * @param altitude   высота «игрока» над землёй
     * @param scatter    рассеивание РСЗО (единичное нормальное: × 1% дальности)
     * @param random     зерно своей случайности снаряда ({@link ua.zentix.airstrike.entity.StrikeProjectile#seed})
     */
    record Params(Vec3 approach, double flightTime, double entry, double side, double standoff, int readyTicks,
                  double when, double shift, Vec3 shiftDir, double size, double place, double mobSpeed, double orbit,
                  double altitude, Vec3 scatter, long random) {}

    Params params() {
        // своё зерно у каждой ячейки: соседние сценарии не повторяют друг друга со сдвигом
        SplittableRandom r = new SplittableRandom(0x5CE7A410L * 31 + cell());
        double a = r.nextDouble() * Math.PI * 2, s = r.nextDouble() * Math.PI * 2;
        Vec3 approach = new Vec3(Math.cos(a), 0, Math.sin(a));
        Vec3 shiftDir = new Vec3(Math.cos(s), 0, Math.sin(s));
        double flightTime = switch (launch.weapon) {
            case DRONE -> 15 + r.nextDouble() * 35;
            case MISSILE -> 8 + r.nextDouble() * 22;
            default -> 10 + r.nextDouble() * 30;
        };
        double entry = launch.weapon == WeaponType.DRONE ? 300 + r.nextDouble() * 200 : 500 + r.nextDouble() * 200;
        double side = r.nextBoolean() ? 1 : -1;
        double standoff = switch (launch.weapon) {
            case ROCKET -> launch.rail ? 150 + r.nextDouble() * 550 : 400 + r.nextDouble() * 500;
            case LOITER -> launch.rail ? 120 + r.nextDouble() * 380 : 300 + r.nextDouble() * 400;
            default -> 150 + r.nextDouble() * 450;
        };
        int ready = 12 + r.nextInt(13);
        double when = 0.3 + r.nextDouble() * 0.55;
        double shift = switch (disturbance) {
            case TELEPORT_NEAR -> 300;
            case TELEPORT_FAR -> 3000;
            default -> 100 + r.nextDouble() * 300;
        };
        double size = disturbance == Disturbance.HILL ? 10 + r.nextDouble() * 30 : 8 + r.nextDouble() * 10;
        double place = disturbance == Disturbance.HILL ? 110 + r.nextDouble() * 110 : 14 + r.nextDouble() * 14;
        double mobSpeed = target == TargetKind.PLAYER ? 0.3 + r.nextDouble() * 1.1 : 0.05 + r.nextDouble() * 0.2;
        double orbit = 20 + r.nextDouble() * 40;
        double altitude = 5 + r.nextDouble() * 35;
        Vec3 scatter = new Vec3(r.nextGaussian(), 0, r.nextGaussian());
        long random = r.nextLong();
        return new Params(approach, flightTime, entry, side, standoff, ready, when, shift, shiftDir, size, place, mobSpeed,
                orbit, altitude, scatter, random);
    }
}
