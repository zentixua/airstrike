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
     * {@code warm} тиков до начала плана: дым, огонь и столбы прежних ударов — в кадре с первого кадра. {@code span} —
     * сколько тиков окна от начала берёт монтаж, если меньше записанного (0 — всё окно): проверка кадров — по тому, что
     * войдёт в ролик.
     */
    record Spec(String shot, String replay, Function<EventLog.Data, Rig.Subject> subject, List<Rig.Kind> kinds, double minShare, Need need,
                int warm, int span) {
        Spec(String shot, String replay, Function<EventLog.Data, Rig.Subject> subject, List<Rig.Kind> kinds, double minShare, Need need) {
            this(shot, replay, subject, kinds, minShare, need, 100, 0);
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
        own(shot, 0, subject, close, need, more);
    }

    /** То же, монтаж берёт первые {@code span} тиков окна (0 — всё окно). */
    private static void own(String shot, int span, Function<EventLog.Data, Rig.Subject> subject, boolean close, Need need, Rig.Kind... more) {
        List<Rig.Kind> kinds = new java.util.ArrayList<>();
        kinds.add(Rig.own(close));
        kinds.addAll(List.of(more));
        add(new Spec(shot, shot, subject, kinds, 0.004, need, 100, span));
    }

    private static Rig.Kind tripod() {
        return Rig.tripod(new double[]{120, 200, 300}, 20, new double[]{-5, 5, 15, 30}, 0.25);
    }

    /** Следящая камера пуска с земли сбоку от стола (как пуски NASA и SpaceX). */
    private static Rig.Kind tracker() {
        return Rig.launchTracker(new double[]{140, 200, 260}, 45, 0.18);
    }

    // объективы по типу камеры: дальний штатив 85–135 мм, штатив и кран 35–50, погоня 24–35; камеры сценария — со своими
    static {
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
        // монтаж берёт секунду (план 12 — с 0:20 до 0:21): шахеды потока — над камерой в эти 30 тиков
        own("p12", 30, Rig.flight("drone"), false, OVERHEAD);
        // p13: ракета над крышей уходит в башню — камера сценария за спиной жителя; запасные — штатив по кругу и погоня
        own("p13", Rig.impact("missile", War.P13_HIT, 0), false, Need.GLIMPSE, tripod(), Rig.chase(35, 16, 3, 3, 30));
        own("p15", Rig.scene(), false, SCENE);
        own("p16", Rig.scene(), false, SCENE);
        // удар за стеклом окна: лучи по блокам стекло считают стеной — проверяется мир, вспышку несёт свет
        own("p17", Rig.impact("missile", new Vec3(3062.5, 0, 338.5), 0), false, SCENE);
        own("p18", Rig.impact("rocket", new Vec3(3330.5, 0, 300.5), 0), true, new Need(0, 0, 0.4, 0));
        own("p19a", Rig.impact("missile", null, 0), false, HIT, Rig.own(false, 30));
        // половина плана 19 (полторы секунды)
        own("p19b", 40, Rig.flight("missile"), false, OVERHEAD, Rig.own(false, 30), Rig.own(false, 24));
        // III · война
        own("p20", Rig.flight("rocket"), false, LAUNCH);
        // p21: с хребта — рой шахедов над камерой уходит к горящему городу и входит в дома; объект — первый удар роя
        // (шахеды проходят над камерой выше кадра и входят в него сверху, по пути к домам)
        add(new Spec("p21", "p21", Rig.impact("drone", null, 0), List.of(Rig.own(false), tripod()), 0.004, new Need(0.4, 0, 0.6, 0), 300, 0));
        own("p01c", Rig.flight("rocket"), false, LAUNCH);
        own("p22a", Rig.flight("drone"), false, OVERHEAD);
        // b: перехваты и прорыв — удар шахеда, который ЗРК не сбили, в кадре
        own("p22b", Rig.framedImpact("drone"), false, new Need(0.25, 0, 0.6, 0), tripod());
        own("p23", Rig.impact("missile", null, 0), true, HIT);
        own("p23b", Rig.impact("missile", null, 0), false, HIT, tripod());
        own("p24", Rig.scene(), false, SCENE);
        own("p24b", Rig.scene(), false, SCENE);
        own("p24c", Rig.scene(), false, SCENE);
        own("p25", Rig.flight("drone"), false, new Need(0.5, 0, 0, 0), Rig.own(false, 50));
        // секунда холодного начала (план 1, склейка d)
        own("p01d", 30, Rig.flight("drone"), false, OVERHEAD);
        own("p01a", Rig.impact("missile", War.T2, 0), true, new Need(0.75, 0.75, 0.6, 0.6), Rig.chase(28, 9, 1.8, 2.4, 20));
        own("p01b", Rig.impact("missile", War.T3, 0), false, new Need(0.5, 0.6, 0.7, 0), tripod());
        own("p26", Rig.impact("rocket", War.p26Hit(), 0), true, new Need(0, 0, 0.4, 0));
        // объект — удар, который FPV снимает лучше всех: ближайший к точке пакета ложился за камерой (13.10: «удар не виден»)
        own("p28", Rig.framedImpact("rocket"), true, new Need(0, 0, 0.4, 0));
        own("p29", Rig.impact("drone", null, 0), false, new Need(0.3, 0.5, 0.7, 0));
        own("p30", Rig.impact("drone", new Vec3(3150.5, 0, 300.5), 0), false, new Need(0.3, 0.6, 0, 0));
        own("p01e", Rig.firstImpact("rocket"), false, HIT);
        own("p31", Rig.impact("drone", War.T4, 0), true, new Need(0.75, 0.75, 0.6, 0.6), Rig.chase(26, 6, 1.2, -1.6, 12));
        // пакет батареей в улицу впереди FPV: объект — удар, который камера снимает лучше всех
        own("p32", Rig.framedImpact("rocket"), true, new Need(0, 0, 0.5, 0));
        own("p35", Rig.flight("missile"), false, OVERHEAD);
        // камера сценария ведёт шахед, ближайший к батарее, а удар ближе всех к её центру — часто другой шахед залпа (netcup:
        // объект в кадре 0 %): объект — удар того шахеда, которого камера снимает лучше всех
        own("p36", Rig.framedImpact("drone"), false, new Need(0.5, 0.6, 0.7, 0), Rig.own(false, 45), tripod());
        // камера сценария бывала вне мира повтора (13.10) — запасные: погоня за бомбой и штатив по кругу
        own("p37", Rig.impact("bunker", new Vec3(3360.5, 0, 470.5), 0), false, new Need(0.3, 0, 0.5, 0), Rig.chase(35, 20, 6, 8, 12), tripod());
        own("p38", Rig.flight("drone"), false, OVERHEAD, Rig.own(false, 24));
        own("p39a", Rig.impact("rocket", new Vec3(2930.5, 0, 260.5), 0), false, HIT);
        // шахеды батареей по местам, видимым из камеры: объект — удар, который камера снимает лучше всех
        own("p39b", Rig.framedImpact("drone"), false, HIT, tripod());
        own("p39c", Rig.impact("missile", War.T1.add(0, -40, 0), 0), false, HIT, tripod());
        own("p39d", Rig.impact("rocket", new Vec3(3200.5, 0, 260.5), 0), false, HIT);
        // IV · конец света
        // пуск своей пусковой (13.10: «полёт» по всему журналу взял снаряд в тумане у края прорисовки)
        add(new Spec("p40b", "p40b", Rig.launch("rocket", War.BATTERY, 150), List.of(Rig.own(false), Rig.own(false, 35), tripod()), 0.004, LAUNCH));
        own("p40c", Rig.flight("missile"), false, LAUNCH);
        // монтаж берёт первые 4,1 с из пяти; МБР входит в слой облаков (320) своим разгоном на ~2,7 с плана — дальше в кадре
        // её след под облаками, «уходит в ночь»
        // запасные — следящая камера сбоку от стола, длинным фокусом (как пуски NASA), и тот же кран шире
        add(new Spec("p41", "p41", Rig.flight("nuke"), List.of(Rig.own(false), tracker(), Rig.own(false, 20)), 0.004, LAUNCH, 100, 86));
        own("p42", Rig.flight("nuke"), false, new Need(0.7, 0, 0, 0), tracker());
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
