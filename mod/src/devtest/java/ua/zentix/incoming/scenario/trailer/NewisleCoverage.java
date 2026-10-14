package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.scenario.trailer.Coverage.Need;
import ua.zentix.incoming.scenario.trailer.Coverage.Spec;
import ua.zentix.incoming.scenario.trailer.NewisleData.Frame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Планы монтажа Newisle из её дублей ({@code cover} у записи или дубля, {@code newisle-takes.json}): повтор — запись (первый
 * план записи), окно — с её «start» со сдвигом плана; камера первого плана — та, что сценарий вёл при записи
 * ({@link Rig#own}), у остальных планов той же записи — их поза разведки от начала их окна. Живые планы (глазами игрока,
 * с интерфейсом) идут в монтаж из записи как есть и сюда не входят.
 */
final class NewisleCoverage {
    private NewisleCoverage() {}

    private static Map<String, Spec> cached;

    static synchronized Map<String, Spec> all() {
        if (cached == null) {
            try {
                cached = build(NewisleData.load(NewisleData.scenes(Minecraft.getInstance().gameDirectory.toPath())));
            } catch (java.io.IOException | RuntimeException e) {
                Incoming.LOG.error("SCENARIO film newisle: таблица планов не собрана", e);
                cached = Map.of();
            }
        }
        return cached;
    }

    /** Таблица планов по данным (без игры — юнит-тест). */
    static Map<String, Spec> build(NewisleData data) {
        NewisleData.Heights flat = NewisleData.FLAT;
        Map<String, Spec> out = new LinkedHashMap<>();
        for (JsonObject take : data.takeList()) {
            List<String> recorded = new ArrayList<>();
            for (JsonElement e : take.getAsJsonArray("shots")) {
                JsonObject shot = e.getAsJsonObject();
                String replay = shot.getAsJsonArray("plans").get(0).getAsString();
                recorded.add(replay);
                if (!shot.has("cover")) continue;
                for (var c : shot.getAsJsonObject("cover").entrySet()) {
                    String plan = c.getKey();
                    int offset = (int) Math.round(NewisleData.offset(shot, plan) * 20);
                    boolean first = plan.equals(replay);
                    out.put(plan, spec(data, plan, replay, offset, first, c.getValue().getAsJsonObject(), flat));
                }
            }
            // план дубля целиком: запись ставит сцена дубля (n18 — сцена дирижабля пишет повтор под именем плана)
            if (take.has("cover")) {
                for (var c : take.getAsJsonObject("cover").entrySet()) {
                    String plan = c.getKey();
                    String replay = recorded.isEmpty() ? plan : recorded.get(0);
                    out.put(plan, spec(data, plan, replay, 0, replay.equals(plan), c.getValue().getAsJsonObject(), flat));
                }
            }
        }
        return out;
    }

    private static Spec spec(NewisleData data, String plan, String replay, int offset, boolean first, JsonObject c, NewisleData.Heights flat) {
        String subjectKind = c.get("subject").getAsString();
        String weapon = c.has("weapon") ? c.get("weapon").getAsString() : null;
        Function<EventLog.Data, Rig.Subject> subject = switch (subjectKind) {
            case "scene" -> Rig.scene();
            case "flight" -> Rig.flight(weapon);
            case "impact" -> Rig.impact(weapon, c.has("near") ? data.point(c.get("near"), 0, flat) : null, 0);
            case "first" -> Rig.firstImpact(weapon);
            case "framed" -> Rig.framedImpact(weapon);
            case "launch" -> Rig.launch(weapon, data.point(c.get("at"), 0, flat), c.get("r").getAsDouble());
            case "nuke" -> Rig.nuke(300);
            default -> throw new IllegalArgumentException(plan + ": объект " + subjectKind);
        };
        String needName = c.has("need") ? c.get("need").getAsString() : "SCENE";
        Need need = need(needName);
        if (c.has("smoky")) need = new Need(need.approach(), need.near(), need.key(), need.engine(), c.get("smoky").getAsDouble());
        List<Rig.Kind> kinds = new ArrayList<>();
        boolean close = needName.equals("FPV");
        if (first) kinds.add(Rig.own(close));
        else kinds.add(posed(plan, data.poses.get(plan), close));
        if (c.has("spare")) for (JsonElement s : c.getAsJsonArray("spare")) kinds.add(spare(s.getAsString()));
        return new Spec(plan, replay, subject, kinds, 0.004, need, 100, 0, offset);
    }

    static Need need(String name) {
        return switch (name) {
            case "SCENE" -> Coverage.SCENE;
            case "OVERHEAD" -> Coverage.OVERHEAD;
            case "LAUNCH" -> Coverage.LAUNCH;
            case "HIT" -> Coverage.HIT;
            case "FOLLOW" -> Coverage.FOLLOW;
            case "STRIKE" -> Need.STRIKE;
            case "GLIMPSE" -> Need.GLIMPSE;
            case "FPV" -> new Need(0, 0, 0.4, 0);
            case "FLIGHT70" -> new Need(0.7, 0, 0, 0);
            case "NUKE" -> Coverage.SCENE.smokeIsSubject();
            default -> throw new IllegalArgumentException("нет нормы " + name);
        };
    }

    /** Запасные камеры по имени: {@code ownNN} — камера плана другим объективом, штатив, следящая пуска, погоня. */
    static Rig.Kind spare(String name) {
        if (name.startsWith("own")) return Rig.own(false, Double.parseDouble(name.substring(3)));
        return switch (name) {
            case "tripod" -> Coverage.tripod();
            case "tracker" -> Coverage.tracker();
            case "chase" -> Rig.chase(35, 16, 3, 3, 30);
            default -> throw new IllegalArgumentException("нет камеры " + name);
        };
    }

    /**
     * Камера разведки плана, который делит запись с другими: поза от начала его окна (тик {@code a}), как у сценария, —
     * её камера записи вела первый план. Позы нет — кандидатов нет (у плана останутся запасные).
     */
    static Rig.Kind posed(String plan, List<Frame> frames, boolean close) {
        return (s, a, b, w, d) -> {
            if (frames == null || frames.isEmpty()) return List.of();
            double mm = 12 / Math.tan(Math.toRadians(frames.get(0).fov() / 2));
            CineCamera.Path path = t -> NewisleData.poseAt(frames, t - a);
            return List.of(new Rig.Candidate(String.format(Locale.ROOT, "scout-%.0fmm", mm), path, mm, close, true));
        };
    }
}
