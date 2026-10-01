package ua.zentix.airstrike.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор сценария commands (свойство {@code airstrike.commands}, шаги через «;») в шаги по тикам клиента — без
 * Minecraft, чтобы проверять юнит-тестом ({@code CommandPlanTest}). Что значат шаги — {@code ClientScenario.planCommands}.
 *
 * @param steps    шаги по порядку
 * @param nukeGate тик {@code wait:nuke} (с него шаги ждут пакета подрыва), −1 — нет
 * @param end      тик «SCENARIO done»
 * @param warnings пропущенные шаги — в лог
 */
record CommandPlan(List<Step> steps, int nukeGate, int end, List<String> warnings) {
    /** Самое долгое {@code wait:N} — час игры, тиков. */
    static final int MAX_WAIT = 72_000;
    /** С какого тика начинаются шаги и сколько после последнего ждать до «SCENARIO done». */
    static final int START = 100, TAIL = 100;

    enum Kind { COMMAND, SHOT, HUD_OFF, HUD_ON }

    record Step(int tick, Kind kind, String arg) {}

    static CommandPlan parse(String spec) {
        List<Step> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int t = START, gate = -1;
        for (String c : ScenarioCommands.split(spec)) {
            // «wait:nuke» — раньше общего «wait:N»: иначе он разбирался как число и пропускался (ноутбук 30.09)
            if (c.equals("wait:nuke")) {
                if (gate >= 0) warnings.add("второй «wait:nuke» пропущен — ждать подрыва можно один раз");
                else gate = t;
            } else if (c.startsWith("wait:")) {
                int ticks;
                try {
                    ticks = Integer.parseInt(c.substring("wait:".length()).strip());
                } catch (NumberFormatException e) {
                    ticks = -1;
                }
                if (ticks < 0 || ticks > MAX_WAIT) warnings.add("«" + c + "» пропущено — нужно целое число тиков от 0 до " + MAX_WAIT + " или nuke");
                else t += ticks;
            } else if (c.startsWith("shot:")) {
                steps.add(new Step(t, Kind.SHOT, c.substring("shot:".length()).strip()));
                t += 20;
            } else if (c.equals("hud:off") || c.equals("hud:on")) {
                steps.add(new Step(t, c.equals("hud:off") ? Kind.HUD_OFF : Kind.HUD_ON, ""));
                t += 1;
            } else {
                steps.add(new Step(t, Kind.COMMAND, c));
                t += 40;
            }
        }
        return new CommandPlan(steps, gate, t + TAIL, warnings);
    }
}
