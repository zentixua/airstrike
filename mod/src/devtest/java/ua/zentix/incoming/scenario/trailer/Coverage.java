package ua.zentix.incoming.scenario.trailer;

import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Что снимает каждый план монтажа из повторов: какой повтор, что в кадре ({@link Rig.Subject}), какими камерами и
 * объективами пробовать, не мельче какой доли кадра объект, должен ли быть слышен его мотор. Имя плана — как в монтаже
 * ({@code tools/trailer/edit.py}: «p13», «p10a»…); один повтор снимается несколькими планами (общий, погоня). Камеры здесь
 * не ставятся руками: место выбирают {@link Rig} по журналу и {@link FrameQa} по каждому кадру вывода.
 */
final class Coverage {
    /**
     * План: {@code replay} — имя повтора (файлы {@code replays/<replay>.zip} и {@code .events.jsonl}), окно — как его снял
     * сценарий (отметки «start»…«end» журнала; там же камера сценария), а удар объекта — в нём с запасом
     * {@link #HIT_MARGIN}: сценарий кончает план по времени, а снаряд входит в цель, когда долетит. Повтор идёт вживую
     * {@code warm} тиков до начала плана: дым, огонь и столбы прежних ударов — в кадре с первого кадра.
     */
    record Spec(String shot, String replay, Function<EventLog.Data, Rig.Subject> subject, List<Rig.Kind> kinds, double minShare, Need need,
                int warm) {
        Spec(String shot, String replay, Function<EventLog.Data, Rig.Subject> subject, List<Rig.Kind> kinds, double minShare, Need need) {
            this(shot, replay, subject, kinds, minShare, need, 100);
        }
    }

    /**
     * Окно плана держит удар объекта с секундой до него и после: проверка удара — 12 тиков после него, монтаж кладёт удар
     * на 0,6 склейки ({@code tools/trailer/edit.py}, склейки до двух с половиной секунд).
     */
    static final int HIT_MARGIN = 20;

    /**
     * Что план обязан показать (доли кадров, {@link FrameQa#verdict}): объект до удара в кадре и виден ({@code approach}),
     * то же за последнюю секунду до удара ({@code near}), удар в кадре и виден в первые 12 тиков ({@code key}), мотор
     * объекта слышен до удара у камеры рядом с ним ({@code engine}, 0 — не нужен); кадров, где больше половины кадра
     * за дымом, до удара не больше доли {@code smoky}: дым прежних ударов не должен закрывать то, что план показывает.
     */
    record Need(double approach, double near, double key, double engine, double smoky) {
        /** Кадров в дыму — не больше четверти плана. */
        static final double SMOKY = 0.25;

        Need(double approach, double near, double key, double engine) {
            this(approach, near, key, engine, SMOKY);
        }

        /** Дым и пыль во весь кадр — и есть то, что план показывает (ядерный подрыв, стена волны). */
        Need smokeIsSubject() {
            return new Need(approach, near, key, engine, 1);
        }

        /** Снаряд ведётся весь план и входит в цель. */
        static final Need STRIKE = new Need(0.6, 0.75, 0.7, 0.6);
        /** Снаряд мелькает (проходит над камерой, из-за спины): главное — удар, перед ним снаряд хоть мельком. */
        static final Need GLIMPSE = new Need(0, 0.3, 0.7, 0);
    }

    private static final Map<String, Spec> ALL = new LinkedHashMap<>();

    private static void add(Spec s) {
        ALL.put(s.shot(), s);
    }

    static Map<String, Spec> all() {
        return ALL;
    }

    /** Ничего не обязано быть в кадре: проверяется мир (построен) — пролёт, цех, улица. */
    static final Need SCENE = new Need(0, 0, 0, 0);
    /** Пуск: снаряды в кадре хотя бы половину плана. */
    static final Need LAUNCH = new Need(0.5, 0, 0, 0);
    /** Проходят над камерой (из-за спины, над головой): в кадре хотя бы четверть плана. */
    static final Need OVERHEAD = new Need(0.25, 0, 0, 0);
    /** Удар — главное: в кадре и виден. */
    static final Need HIT = new Need(0, 0, 0.6, 0);
    /** Погоня за снарядом: в кадре почти всё время, мотор слышен. */
    static final Need FOLLOW = new Need(0.75, 0, 0, 0.6);

    /** План сценария как есть (окно и камера — из записи) с проверкой кадров; {@code more} — запасные камеры. */
    private static void own(String shot, Function<EventLog.Data, Rig.Subject> subject, boolean close, Need need, Rig.Kind... more) {
        List<Rig.Kind> kinds = new java.util.ArrayList<>();
        kinds.add(Rig.own(close));
        kinds.addAll(List.of(more));
        add(new Spec(shot, shot, subject, kinds, 0.004, need));
    }

    private static Rig.Kind tripod() {
        return Rig.tripod(new double[]{120, 200, 300}, 20, new double[]{-5, 5, 15, 30}, 0.25);
    }

    // объективы по типу камеры: дальний штатив 85–135 мм, штатив и кран 35–50, погоня 24–35; камеры сценария — со своими
    static {
        Vec3 battery = War.BATTERY;
        // I · подготовка
        own("p03", Rig.scene(), false, SCENE);
        own("p04", Rig.scene(), false, SCENE);
        own("p05", Rig.scene(), false, SCENE);
        own("p05b", Rig.scene(), false, SCENE);
        own("p05c", Rig.scene(), false, SCENE);
        own("p06", Rig.scene(), false, SCENE);
        // пуски — длинным фокусом (85–100 мм); теряет снаряды — тот же путь шире, потом штатив по кругу
        own("p10a", Rig.flight("missile"), false, LAUNCH, Rig.own(false, 45), tripod());
        own("p10b", Rig.flight("rocket"), false, LAUNCH, Rig.own(false, 50), tripod());
        own("p10c", Rig.flight("drone"), false, LAUNCH, Rig.own(false, 45), tripod());
        own("p11", Rig.flight("drone"), true, FOLLOW, Rig.chase(28, 8, 2, -2, 0));
        // II · первый удар
        own("p12", Rig.flight("drone"), false, OVERHEAD);
        // p13: ракета над крышей уходит в башню — камера сценария за спиной жителя; запасные — штатив по кругу и погоня
        own("p13", Rig.impact("missile", War.P13_HIT, 0), false, Need.GLIMPSE, tripod(), Rig.chase(35, 16, 3, 3, 30));
        own("p15", Rig.scene(), false, SCENE);
        own("p16", Rig.scene(), false, SCENE);
        // удар за стеклом окна: лучи по блокам стекло считают стеной — проверяется мир, вспышку несёт свет
        own("p17", Rig.impact("missile", new Vec3(3062.5, 0, 338.5), 0), false, SCENE);
        own("p18", Rig.impact("rocket", new Vec3(3330.5, 0, 300.5), 0), true, new Need(0, 0, 0.4, 0));
        own("p19a", Rig.impact("missile", null, 0), false, HIT, Rig.own(false, 30));
        own("p19b", Rig.flight("missile"), false, OVERHEAD, Rig.own(false, 30));
        // III · война
        own("p20", Rig.flight("rocket"), false, LAUNCH);
        // p21: с хребта — рой шахедов над камерой уходит к горящему городу и входит в дома; объект — первый удар роя
        // (шахеды проходят над камерой выше кадра и входят в него сверху, по пути к домам)
        add(new Spec("p21", "p21", Rig.impact("drone", null, 0), List.of(Rig.own(false), tripod()), 0.004, new Need(0.4, 0, 0.6, 0), 300));
        own("p01c", Rig.flight("rocket"), false, LAUNCH);
        own("p22a", Rig.flight("drone"), false, OVERHEAD);
        // b: перехваты и прорыв — удар шахеда, который ЗРК не сбили, в кадре
        own("p22b", Rig.framedImpact("drone"), false, new Need(0.25, 0, 0.6, 0), tripod());
        own("p23", Rig.impact("missile", null, 0), true, HIT);
        own("p23b", Rig.impact("missile", null, 0), false, HIT);
        own("p24", Rig.scene(), false, SCENE);
        own("p24b", Rig.scene(), false, SCENE);
        own("p24c", Rig.scene(), false, SCENE);
        own("p25", Rig.flight("drone"), false, new Need(0.5, 0, 0, 0));
        own("p01d", Rig.flight("drone"), false, OVERHEAD);
        own("p01a", Rig.impact("missile", War.T2, 0), true, new Need(0.75, 0.75, 0.6, 0.6), Rig.chase(28, 9, 1.8, 2.4, 20));
        own("p01b", Rig.impact("missile", War.T3, 0), false, new Need(0.5, 0.6, 0.7, 0), tripod());
        own("p26", Rig.impact("rocket", War.SQUARE.add(-8, 0, 14), 0), true, new Need(0, 0, 0.4, 0));
        own("p28", Rig.impact("rocket", new Vec3(3090.5, 0, 331.5), 0), true, new Need(0, 0, 0.4, 0));
        own("p29", Rig.impact("drone", new Vec3(3175.5, 120, 353.5), 0), false, new Need(0.3, 0.5, 0.7, 0));
        own("p30", Rig.impact("drone", new Vec3(3150.5, 0, 300.5), 0), false, new Need(0.3, 0.6, 0, 0));
        own("p01e", Rig.impact("rocket", new Vec3(3175.5, 0, 252.5), 0), false, HIT);
        own("p31", Rig.impact("drone", War.T4, 0), true, new Need(0.75, 0.75, 0.6, 0.6), Rig.chase(26, 6, 1.2, -1.6, 12));
        own("p32", Rig.impact("rocket", new Vec3(3010.5, 0, 640.5), 0), true, new Need(0, 0, 0.5, 0));
        own("p35", Rig.flight("missile"), false, OVERHEAD);
        own("p36", Rig.impact("drone", battery.add(10, 0, 0), 0), false, new Need(0.5, 0.6, 0.7, 0), Rig.own(false, 45), tripod());
        own("p37", Rig.impact("bunker", new Vec3(3360.5, 0, 470.5), 0), false, new Need(0.3, 0, 0.5, 0));
        own("p38", Rig.flight("drone"), false, OVERHEAD);
        own("p39a", Rig.impact("rocket", new Vec3(2930.5, 0, 260.5), 0), false, HIT);
        own("p39b", Rig.impact("drone", new Vec3(3110.5, 0, 410.5), 0), false, HIT);
        own("p39c", Rig.impact("missile", War.T1.add(0, -40, 0), 0), false, HIT);
        own("p39d", Rig.impact("rocket", new Vec3(3200.5, 0, 260.5), 0), false, HIT);
        // IV · конец света
        own("p40b", Rig.flight("rocket"), false, LAUNCH);
        own("p40c", Rig.flight("missile"), false, LAUNCH);
        own("p41", Rig.flight("nuke"), false, LAUNCH);
        own("p42", Rig.flight("nuke"), false, new Need(0.7, 0, 0, 0));
        own("p44", Rig.scene(), false, SCENE);
        own("p45", Rig.nuke(300), false, SCENE.smokeIsSubject());
        own("p46", Rig.nuke(300), false, SCENE.smokeIsSubject(), Rig.own(false, 21));
        own("p47", Rig.scene(), false, SCENE.smokeIsSubject());
        own("p48", Rig.scene(), false, SCENE.smokeIsSubject());
        own("p49", Rig.scene(), false, SCENE.smokeIsSubject());
        own("p50", Rig.scene(), false, SCENE.smokeIsSubject());
    }

    private Coverage() {}
}
