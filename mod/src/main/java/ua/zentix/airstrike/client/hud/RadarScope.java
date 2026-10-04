package ua.zentix.airstrike.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.net.S2C;

/**
 * Экран радара ЗРК в левом верхнем углу — у своих рядом с ЗРК ({@link S2C.RadarScope}, раз в осмотр радара): круг
 * дальности радара «север вверх», внутри — круг дальности огня, бегущий луч развёртки, цели: чужие — красные (за
 * которыми идёт ракета — оранжевые), свои — зелёные; под кругом — ракеты на направляющих и в запасе. Пакеты от двух
 * ЗРК сразу — экран ближнего. Пакетов нет {@link #STALE} тиков (отошёл, ЗРК сломан или выгружен) — экран гаснет.
 */
public final class RadarScope {
    /** Без пакета столько тиков — экрана нет. */
    static final int STALE = 40;
    private static final int RADIUS = 34, MARGIN = 8;
    private static final int GREEN = 0xFF46F07A, DIM = 0x6046F07A, HOSTILE = 0xFFFF4030, ENGAGED = 0xFFFFA020;
    /** Луч развёртки — оборот за столько тиков. */
    private static final float SWEEP = 40;

    @Nullable
    private static S2C.RadarScope scope;
    private static long received, ticks;

    private RadarScope() {}

    /** Пакет радара: ЗРК дальше того, чей экран сейчас показан, его не перебивает. */
    public static void received(S2C.RadarScope p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 me = mc.player.position();
        if (scope != null && ticks - received < STALE && p.radar().distanceToSqr(me) > scope.radar().distanceToSqr(me) + 1) return;
        scope = p;
        received = ticks;
    }

    /** Раз в тик клиента (не на паузе). */
    public static void tick() {
        ticks++;
        if (scope != null && ticks - received > STALE) scope = null;
    }

    public static void reset() {
        scope = null;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        S2C.RadarScope s = scope;
        if (s == null || mc.options.hideGui || mc.level == null || !AirstrikeConfig.CLIENT.hud.get() || ProjectileCamera.isActive()) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        int cx = MARGIN + RADIUS, cy = MARGIN + RADIUS;
        HudDraw.disc(g, cx, cy, RADIUS, 0xA0061A0E);
        HudDraw.line(g, cx - RADIUS, cy, cx + RADIUS, cy, 1, DIM);
        HudDraw.line(g, cx, cy - RADIUS, cx, cy + RADIUS, 1, DIM);
        float scale = (float) RADIUS / Math.max(1, s.range());
        HudDraw.ring(g, cx, cy, s.engageRange() * scale, 1, DIM);
        HudDraw.ring(g, cx, cy, RADIUS, 1, GREEN);
        float a = (ticks + pt) / SWEEP * Mth.TWO_PI;
        HudDraw.line(g, cx, cy, cx + Mth.sin(a) * RADIUS, cy - Mth.cos(a) * RADIUS, 1, 0xB046F07A);
        Font font = mc.font;
        g.drawString(font, "N", cx - font.width("N") / 2, cy - RADIUS + 2, DIM, false);
        int hostile = 0;
        for (S2C.Blip b : s.blips()) {
            float x = cx + b.dx() * scale, y = cy + b.dz() * scale;
            if (b.is(S2C.Blip.HOSTILE)) hostile++;
            int color = !b.is(S2C.Blip.HOSTILE) ? GREEN : b.is(S2C.Blip.ENGAGED) ? ENGAGED : HOSTILE;
            // хвост — откуда летит (курс Minecraft: 0° — на +Z, вниз по экрану)
            float yaw = b.yaw() * Mth.DEG_TO_RAD;
            HudDraw.line(g, x, y, x + Mth.sin(yaw) * 4, y - Mth.cos(yaw) * 4, 1, color & 0x80FFFFFF);
            HudDraw.disc(g, x, y, b.is(S2C.Blip.ENGAGEABLE) ? 2.2f : 1.6f, color);
        }
        Component line = Component.translatable("airstrike.sam.scope", String.valueOf(s.ready()), String.valueOf(s.stock()), String.valueOf(hostile))
                .withStyle(hostile > 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
        g.drawString(font, line, MARGIN, cy + RADIUS + 4, 0xFFFFFFFF);
    }
}
