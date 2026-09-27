package ua.zentix.airstrike.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Свои снаряды в полёте и время подлёта — справа сверху, у того, кто пустил. */
public final class StrikesHud {
    private static final int MAX_LINES = 6;

    private StrikesHud() {}

    private record Line(Component text, double eta) {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null || mc.player == null || !AirstrikeConfig.CLIENT.hud.get()) return;
        UUID me = mc.player.getUUID();
        List<Line> lines = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof StrikeProjectile p) || !me.equals(p.ownerId())) continue;
            double v = Math.max(0.1, p.speed());
            double eta = p.isActive() ? p.position().distanceTo(p.aimPoint()) / v / 20.0 : Double.MAX_VALUE;
            String key = p instanceof BomberEntity ? "airstrike.hud.bomber" : p.isActive() ? "airstrike.hud.eta" : "airstrike.hud.waiting";
            lines.add(new Line(Component.translatable(key, p.getType().getDescription(), String.format("%.0f", Math.min(eta, 999))), eta));
        }
        if (lines.isEmpty()) return;
        lines.sort(Comparator.comparingDouble(Line::eta));
        Font font = mc.font;
        int x = g.guiWidth() - 6, y = 6;
        Component title = Component.translatable("airstrike.hud.in_flight", lines.size()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        g.drawString(font, title, x - font.width(title), y, 0xFFFFFFFF);
        y += 11;
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            Component t = lines.get(i).text().copy().withStyle(ChatFormatting.YELLOW);
            g.drawString(font, t, x - font.width(t), y, 0xFFFFFFFF);
            y += 10;
        }
        if (lines.size() > MAX_LINES) {
            Component more = Component.translatable("airstrike.hud.more", lines.size() - MAX_LINES).withStyle(ChatFormatting.GRAY);
            g.drawString(font, more, x - font.width(more), y, 0xFFFFFFFF);
        }
    }
}
