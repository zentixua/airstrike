package ua.zentix.incoming.scenario.trailer;

import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Что снимает каждый план монтажа из повторов: какой повтор, что в кадре ({@link Rig.Subject}), сколько до главного
 * события и после, какими камерами и объективами пробовать, не мельче какой доли кадра объект, должен ли быть слышен его
 * мотор. Имя плана — как в монтаже ({@code tools/trailer/edit.py}: «p13», «p10a»…); один повтор снимается несколькими
 * планами (общий, погоня). Камеры здесь не ставятся руками: место выбирают {@link Rig} по журналу и {@link FrameQa} по
 * каждому кадру вывода.
 */
final class Coverage {
    /**
     * План: {@code replay} — имя повтора (файлы {@code replays/<replay>.zip} и {@code .events.jsonl}), окно — от
     * {@code pre} тиков до опоры до {@code post} после; опора — удар, без удара — появление снаряда (пуск), у места —
     * отметка «start» журнала (момент плана в сценарии).
     */
    record Spec(String shot, String replay, Function<EventLog.Data, Rig.Subject> subject, int pre, int post, List<Rig.Kind> kinds,
                double minShare, boolean engine, int warm) {
        /** Повтор идёт вживую {@code warm} тиков до начала плана: дым, огонь и столбы прежних ударов — в кадре с первого кадра. */
        Spec(String shot, String replay, Function<EventLog.Data, Rig.Subject> subject, int pre, int post, List<Rig.Kind> kinds,
             double minShare, boolean engine) {
            this(shot, replay, subject, pre, post, kinds, minShare, engine, 100);
        }
    }

    private static final Map<String, Spec> ALL = new LinkedHashMap<>();

    private static void add(Spec s) {
        ALL.put(s.shot(), s);
    }

    static Map<String, Spec> all() {
        return ALL;
    }

    // объективы по типу камеры: дальний штатив 85–135 мм, штатив и кран 35–50, погоня 24–35
    static {
        // p13: ракета входит в башню T5 — дальний штатив сбоку от подлёта, ближний штатив, погоня с отпуском
        Vec3 t5 = War.T5.add(0, -60, 0);
        add(new Spec("p13", "p13", Rig.impact("missile", t5, 0), 110, 50, List.of(
                Rig.tripod(new double[]{120, 200, 300}, 20, new double[]{-5, 5, 15, 30}, 0.25),
                Rig.chase(35, 16, 3, 3, 30)), 0.015, true));
    }

    private Coverage() {}
}
