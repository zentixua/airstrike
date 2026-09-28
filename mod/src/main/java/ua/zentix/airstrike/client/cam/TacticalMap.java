package ua.zentix.airstrike.client.cam;

import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.hud.HudDraw;
import ua.zentix.airstrike.client.hud.StrikesHud;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.registry.ModEntities;

import java.util.Locale;

/**
 * Карта оператора — что показывает камера, пока снаряда нет на клиенте. Сервер присылает игроку сущность, только
 * если она ближе {@code min(clientTrackingRange, дальность прорисовки)} по горизонтали и её чанк отслеживается игроком
 * ({@code ChunkMap.TrackedEntity.updatePlayer}); снаряд вне мира ({@code VirtualFlights}) не присылается вовсе.
 * Смотреть издалека в ванили можно только камерой наблюдателя, которая переносит к цели самого игрока, — поэтому
 * вместо картинки здесь то, что есть: телеметрия {@code FlightStatus} (где снаряд, куда, фаза, время до удара).
 * Как у наземной станции управления БПЛА без видеоканала: карта «север вверх», путь, курс, цель, оператор и граница,
 * за которой появится видео. Масштаб и центр плавно следуют за снарядом, целью и оператором.
 */
final class TacticalMap {
    /** Сетка — не мельче этого, пикселей. */
    private static final int GRID_MIN_PX = 40;
    private static final double[] GRID_STEPS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};
    /** Доля перехода к новому масштабу и центру за тик. */
    private static final double FOLLOW = 0.15;

    private static final int BG = 0xF00A1410, GRID = 0x2860FF90, GRID_TEXT = 0x9060FF90, INK = 0xFFD8F0E0;
    private static final int TRAIL = 0xB060C8FF, ROUTE = 0x90FF6040, TARGET = 0xFFFF3030, CRAFT = 0xFFFFD040, OTHER = 0xA0A0B0A8;
    private static final int OPERATOR = 0xFFFFFFFF, VIDEO_RING = 0x8080FFA0;

    /** Вид карты: центр (x, z мира) и пикселей на блок — сейчас и тиком раньше (для плавности между тиками). */
    private static double cx, cz, scale, cxO, czO, scaleO;
    private static boolean placed;

    private TacticalMap() {}

    /** Карта снова открывается — без наезда из старого вида. */
    static void reset() {
        placed = false;
    }

    /** Раз в тик: куда смотреть карте — чтобы в кадре были снаряд, цель и оператор. */
    static void tick(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 craft = f.position(0), target = f.target(), me = mc.player.position();
        double minX = Math.min(craft.x, Math.min(target.x, me.x)), maxX = Math.max(craft.x, Math.max(target.x, me.x));
        double minZ = Math.min(craft.z, Math.min(target.z, me.z)), maxZ = Math.max(craft.z, Math.max(target.z, me.z));
        double w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        // поля: сверху телеметрия, снизу подсказки
        double wantScale = Math.min((w - 80) / Math.max(60, maxX - minX), (h - 120) / Math.max(60, maxZ - minZ));
        double wantX = (minX + maxX) / 2, wantZ = (minZ + maxZ) / 2;
        cxO = cx;
        czO = cz;
        scaleO = scale;
        if (!placed) {
            cx = cxO = wantX;
            cz = czO = wantZ;
            scale = scaleO = wantScale;
            placed = true;
            return;
        }
        cx += (wantX - cx) * FOLLOW;
        cz += (wantZ - cz) * FOLLOW;
        // масштаб — в логарифме: приближение и отдаление одинаково плавные
        scale = Math.exp(Mth.lerp(FOLLOW, Math.log(scale), Math.log(wantScale)));
    }

    /**
     * Дальность видео, блоков: сервер присылает сущность не дальше дальности прорисовки игрока и дальности
     * отслеживания её типа. На встроенном сервере последняя умножается на «Дальность прорисовки сущностей»
     * ({@code IntegratedServer.getScaledTrackingDistance}); у выделенного сервера — как есть.
     */
    static int videoRange(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        double tracking = ModEntities.of(f.weapon()).clientTrackingRange() * 16;
        if (mc.hasSingleplayerServer()) tracking *= mc.options.entityDistanceScaling().get();
        return (int) Math.min(tracking, mc.options.getEffectiveRenderDistance() * 16);
    }

    static void render(GuiGraphics g, Font font, ClientFlights.Tracked f, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !placed) return;
        int w = g.guiWidth(), h = g.guiHeight();
        double viewX = Mth.lerp(pt, cxO, cx), viewZ = Mth.lerp(pt, czO, cz);
        double k = Math.exp(Mth.lerp(pt, Math.log(scaleO), Math.log(scale)));
        Projection map = new Projection(w / 2.0, h / 2.0 + 10, viewX, viewZ, k);

        g.fill(0, 0, w, h, BG);
        grid(g, font, map, w, h);

        Vec3 me = mc.player.getPosition(pt);
        int range = videoRange(f);
        HudDraw.dottedCircle(g, map.x(me.x), map.y(me.z), range * k, VIDEO_RING);
        int[] op = map.at(me);
        g.fill(op[0] - 2, op[1] - 2, op[0] + 3, op[1] + 3, OPERATOR);
        label(g, font, Component.translatable("airstrike.map.you"), op[0], op[1] + 5, OPERATOR);

        for (ClientFlights.Tracked o : ClientFlights.all()) {
            if (o == f || o.phase() == FlightPhase.READY) continue;
            int[] p = map.at(o.position(pt));
            g.fill(p[0] - 1, p[1] - 1, p[0] + 2, p[1] + 2, OTHER);
        }

        Vec3 craft = f.position(pt);
        int[] c = map.at(craft);
        int[] t = map.at(f.target());
        int[] prev = null;
        for (Vec3 p : f.trail()) {
            int[] q = map.at(p);
            if (prev != null) HudDraw.dotted(g, prev[0], prev[1], q[0], q[1], TRAIL);
            g.fill(q[0], q[1], q[0] + 1, q[1] + 1, TRAIL);
            prev = q;
        }
        if (prev != null) HudDraw.dotted(g, prev[0], prev[1], c[0], c[1], TRAIL);
        HudDraw.dotted(g, c[0], c[1], t[0], t[1], ROUTE);

        HudDraw.box(g, t[0], t[1], 6, f.targetLost() ? OTHER : TARGET);
        g.fill(t[0], t[1], t[0] + 1, t[1] + 1, TARGET);
        Component who = f.targetLabel();
        if (who != null) label(g, font, who, t[0], t[1] + 9, TARGET);

        Vec3 v = f.velocity();
        aircraft(g, c[0], c[1], (float) Math.toDegrees(Math.atan2(-v.x, v.z)), CRAFT);
        label(g, font, Component.literal("№" + f.number + (f.nuclear() ? " ☢" : "")), c[0], c[1] - 16, CRAFT);

        telemetry(g, font, f, craft, v, pt);
        status(g, font, f, craft, me, range, w, h);
    }

    /** Сетка с шагом не мельче {@link #GRID_MIN_PX} и подпись шага. */
    private static void grid(GuiGraphics g, Font font, Projection map, int w, int h) {
        double step = GRID_STEPS[GRID_STEPS.length - 1];
        for (double s : GRID_STEPS) {
            if (s * map.k >= GRID_MIN_PX) {
                step = s;
                break;
            }
        }
        double left = map.worldX(0), right = map.worldX(w), top = map.worldZ(0), bottom = map.worldZ(h);
        for (double x = Math.floor(left / step) * step; x <= right; x += step) {
            int px = map.x(x);
            g.fill(px, 0, px + 1, h, GRID);
        }
        for (double z = Math.floor(top / step) * step; z <= bottom; z += step) {
            int py = map.y(z);
            g.fill(0, py, w, py + 1, GRID);
        }
        Component legend = Component.translatable("airstrike.map.grid", String.format(Locale.ROOT, "%.0f", step));
        g.drawString(font, legend, 8, h - 14, GRID_TEXT);
        g.drawString(font, "N ↑", w / 2 - font.width("N ↑") / 2, 26, GRID_TEXT);
    }

    /** Слева сверху: что это за экран и снаряд, фаза, высота, скорость, дальность до цели, время до удара. */
    private static void telemetry(GuiGraphics g, Font font, ClientFlights.Tracked f, Vec3 craft, Vec3 v, float pt) {
        int x = 10, y = 8;
        g.drawString(font, Component.translatable("airstrike.camera.no_link").withStyle(ChatFormatting.BOLD), x, y, 0xFFFFC040);
        y += 14;
        Component name = Component.literal(f.weapon().displayName().getString().toUpperCase(Locale.ROOT) + " №" + f.number);
        g.drawString(font, name, x, y, INK);
        y += 11;
        g.drawString(font, Component.translatable("airstrike.phase." + f.phase().getSerializedName()).getString().toUpperCase(Locale.ROOT),
                x, y, f.phase() == FlightPhase.TERMINAL ? 0xFFFF4040 : INK);
        y += 14;
        double kmh = v.length() * 20 * 3.6;
        Component[] lines = {
                Component.translatable("airstrike.map.height", String.format(Locale.ROOT, "%.0f", craft.y)),
                Component.translatable("airstrike.camera.speed", String.format(Locale.ROOT, "%.0f", kmh)),
                Component.translatable("airstrike.camera.range", String.format(Locale.ROOT, "%.0f", craft.distanceTo(f.target()))),
                Component.literal("T-" + StrikesHud.clock(f.etaSeconds(pt)))
        };
        for (int i = 0; i < lines.length; i++) {
            g.drawString(font, lines[i], x, y, i == lines.length - 1 ? 0xFFFFC040 : INK);
            y += 11;
        }
    }

    /** Внизу: когда будет видео. */
    private static void status(GuiGraphics g, Font font, ClientFlights.Tracked f, Vec3 craft, Vec3 me, int range, int w, int h) {
        double dx = craft.x - me.x, dz = craft.z - me.z;
        int away = (int) Math.sqrt(dx * dx + dz * dz);
        Component line = away > range
                ? Component.translatable("airstrike.map.video_at", range, away)
                : Component.translatable("airstrike.map.connecting");
        g.drawString(font, line.copy().withStyle(ChatFormatting.GRAY), w / 2 - font.width(line) / 2, h - 48, INK);
    }

    /** Значок снаряда: стрелка по курсу (0° — на север, по часовой). */
    private static void aircraft(GuiGraphics g, int x, int y, float yaw, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        // курс Minecraft: 0° — на +Z (вниз по карте), по часовой — к −X; на экране поворот по часовой — положительный
        g.pose().mulPose(Axis.ZP.rotationDegrees(yaw + 180));
        g.fill(-1, -7, 1, 6, color);
        g.fill(-6, -1, 6, 1, color);
        g.fill(-3, 4, 3, 6, color);
        g.pose().popPose();
    }

    private static void label(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }

    /** Мир (x, z) → экран: север (−Z) вверх, восток (+X) вправо. */
    private record Projection(double ox, double oy, double cx, double cz, double k) {
        int x(double worldX) {
            return (int) Math.round(ox + (worldX - cx) * k);
        }

        int y(double worldZ) {
            return (int) Math.round(oy + (worldZ - cz) * k);
        }

        int[] at(Vec3 p) {
            return new int[] {x(p.x), y(p.z)};
        }

        double worldX(int screenX) {
            return cx + (screenX - ox) / k;
        }

        double worldZ(int screenY) {
            return cz + (screenY - oy) / k;
        }
    }
}
