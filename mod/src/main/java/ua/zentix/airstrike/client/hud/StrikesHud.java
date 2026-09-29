package ua.zentix.airstrike.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.Keys;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.client.render.ScreenProjection;
import ua.zentix.airstrike.entity.FlightPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Свои снаряды в полёте — у того, кто пустил: справа сверху список (что, фаза полёта, время до удара), а в мире —
 * метки: ромб на снаряде с номером и временем и крестик на его цели. Время знает сервер (маршрут в обход и полёт
 * вне загруженного мира), клиент лишь досчитывает его между пакетами.
 */
public final class StrikesHud {
    private static final int MAX_LINES = 6;

    private StrikesHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null || mc.player == null || !AirstrikeConfig.CLIENT.hud.get()) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) return;
        // в камере снаряда справа вверху — её телеметрия (и время до удара): список поверх неё слипался в кашу
        if (ProjectileCamera.isActive()) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        markers(g, mc.font, flights, pt);

        Font font = mc.font;
        int x = g.guiWidth() - 6, y = 6;
        Component title = Component.translatable("airstrike.hud.in_flight", flights.size()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        g.drawString(font, title, x - font.width(title), y, 0xFFFFFFFF);
        y += 11;
        for (int i = 0; i < Math.min(MAX_LINES, flights.size()); i++) {
            Component t = line(flights.get(i), pt);
            g.drawString(font, t, x - font.width(t), y, 0xFFFFFFFF);
            y += 10;
        }
        if (flights.size() > MAX_LINES) {
            Component more = Component.translatable("airstrike.hud.more", flights.size() - MAX_LINES).withStyle(ChatFormatting.GRAY);
            g.drawString(font, more, x - font.width(more), y, 0xFFFFFFFF);
            y += 10;
        }
        Component hint = Component.translatable("airstrike.hud.camera_hint", Keys.CAMERA.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.DARK_GRAY);
        g.drawString(font, hint, x - font.width(hint), y + 2, 0xFFFFFFFF);
    }

    /** «Шахед №2 · маршрут · 0:41». */
    static Component line(ClientFlights.Tracked f, float pt) {
        MutableComponent name = Component.translatable("airstrike.hud.flight", f.weapon().displayName(), f.number);
        if (f.nuclear()) name = Component.literal("☢ ").withStyle(ChatFormatting.YELLOW).append(name);
        Component phase = Component.translatable("airstrike.phase." + f.phase().getSerializedName());
        ChatFormatting color = switch (f.phase()) {
            case READY, IGNITION, BOOST -> ChatFormatting.GOLD;
            case TERMINAL, POP_UP -> ChatFormatting.RED;
            default -> ChatFormatting.YELLOW;
        };
        MutableComponent line = Component.translatable("airstrike.hud.flight_line", name, phase, clock(f.etaSeconds(pt))).withStyle(color);
        Component target = targetText(f);
        if (target != null) line.append(Component.literal(" → ").append(target));
        return line;
    }

    /** Подпись цели: кто или что, «цель потеряна», если она пропала. Для точки — null. */
    static Component targetText(ClientFlights.Tracked f) {
        if (f.targetLost()) return Component.translatable("airstrike.hud.target_lost").withStyle(ChatFormatting.GRAY);
        return f.targetLabel();
    }

    public static String clock(double seconds) {
        int s = (int) Math.ceil(seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * Метки в мире: ромб на снаряде (номер и время) и пунктир от него к его цели; на цели — крестик с номерами
     * снарядов, которые на неё идут, и подписью, кто это. Цели ближе 4 блоков друг к другу — один крестик (залп по
     * игроку не рисует десять одинаковых). Потерянная цель — серый крестик. За спиной — не рисуются.
     */
    private static void markers(GuiGraphics g, Font font, List<ClientFlights.Tracked> flights, float pt) {
        int w = g.guiWidth(), h = g.guiHeight();
        List<TargetMark> marks = new ArrayList<>();
        for (ClientFlights.Tracked f : flights) {
            if (f.phase() == FlightPhase.READY) continue;
            TargetMark mark = null;
            for (TargetMark m : marks) {
                if (m.lost == f.targetLost() && m.pos.distanceToSqr(f.target()) < 16) {
                    mark = m;
                    break;
                }
            }
            if (mark == null) marks.add(mark = new TargetMark(f.target(), f.targetLost(), targetText(f)));
            mark.numbers.add(f.number);

            float[] s = ScreenProjection.project(f.position(pt));
            float[] t = ScreenProjection.project(f.target());
            if (s == null) continue;
            int sx = (int) (s[0] * w), sy = (int) (s[1] * h);
            if (sx <= -20 || sx >= w + 20 || sy <= -20 || sy >= h + 20) continue;
            int c = f.phase() == FlightPhase.TERMINAL ? 0xFFFF4030 : 0xFFFFC040;
            if (t != null) HudDraw.dotted(g, sx, sy, (int) (t[0] * w), (int) (t[1] * h), (c & 0x00FFFFFF) | 0x70000000);
            HudDraw.diamond(g, sx, sy, 4, c);
            String label = "№" + f.number + " " + clock(f.etaSeconds(pt));
            g.drawString(font, label, sx - font.width(label) / 2, sy - 14, c);
        }
        List<int[]> placed = new ArrayList<>();
        for (TargetMark m : marks) {
            float[] t = ScreenProjection.project(m.pos);
            if (t == null) continue;
            m.tx = (int) (t[0] * w);
            m.ty = (int) (t[1] * h);
        }
        // сверху вниз: подпись, которая наехала бы на уже поставленную, опускается на строку
        marks.sort(Comparator.comparingInt((TargetMark m) -> m.ty).thenComparingInt(m -> m.tx));
        for (TargetMark m : marks) {
            int tx = m.tx, ty = m.ty;
            if (tx <= 0 || tx >= w || ty <= 0 || ty >= h) continue;
            int c = m.lost ? 0xC0A0A0A0 : 0xC0FF3030;
            for (int i = -4; i <= 4; i++) {
                g.fill(tx + i, ty + i, tx + i + 1, ty + i + 1, c);
                g.fill(tx + i, ty - i, tx + i + 1, ty - i + 1, c);
            }
            MutableComponent text = Component.literal(numbers(m.numbers));
            if (m.label != null) text.append(" · ").append(m.label);
            int tw = font.width(text), x = tx - tw / 2, y = labelY(placed, x, tw, ty + 7);
            placed.add(new int[] {x, y, tw});
            g.drawString(font, text, x, y, c | 0xFF000000);
        }
    }

    /** Строка подписи шириной {@code width} от {@code x}: первая с {@code y} вниз, где она ни на что не наезжает. */
    static int labelY(List<int[]> placed, int x, int width, int y) {
        for (int n = 0; n < 32; n++) {
            boolean free = true;
            for (int[] r : placed) {
                if (x < r[0] + r[2] + 4 && r[0] < x + width + 4 && Math.abs(y - r[1]) < 10) {
                    free = false;
                    break;
                }
            }
            if (free) return y;
            y += 10;
        }
        return y;
    }

    /** Крестик цели: точка, потеряна ли она, подпись и номера снарядов. */
    private static final class TargetMark {
        final Vec3 pos;
        final boolean lost;
        @Nullable
        final Component label;
        final List<Integer> numbers = new ArrayList<>();
        /** Место на экране (−1 — за спиной). */
        int tx = -1, ty = -1;

        TargetMark(Vec3 pos, boolean lost, @Nullable Component label) {
            this.pos = pos;
            this.lost = lost;
            this.label = label;
        }
    }

    /** «№1–3, 5»: номера по возрастанию, подряд идущие — диапазоном. */
    static String numbers(List<Integer> list) {
        List<Integer> n = new ArrayList<>(list);
        Collections.sort(n);
        StringBuilder sb = new StringBuilder("№");
        for (int i = 0; i < n.size(); ) {
            int j = i;
            while (j + 1 < n.size() && n.get(j + 1) == n.get(j) + 1) j++;
            if (i > 0) sb.append(", ");
            sb.append(n.get(i));
            if (j > i) sb.append(j == i + 1 ? ", " : "–").append(n.get(j));
            i = j + 1;
        }
        return sb.toString();
    }
}
