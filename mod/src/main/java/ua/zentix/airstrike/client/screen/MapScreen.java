package ua.zentix.airstrike.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.hud.HudDraw;
import ua.zentix.airstrike.client.map.MapProjection;
import ua.zentix.airstrike.client.map.MapTarget;
import ua.zentix.airstrike.client.map.TerrainTiles;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.Loadout;

import java.util.Locale;
import java.util.Optional;

/**
 * Карта наведения: как у оператора на планшете — карта «север вверх» с рельефом ({@link TerrainTiles}: Distant
 * Horizons на километры, иначе загруженные чанки), сетка, где ты и куда смотришь, снаряды в полёте. ЛКМ — место
 * удара (координаты, дальность и азимут от тебя), тянуть — сдвиг, колесо — масштаб у курсора, Enter — огонь,
 * пробел — к себе. Огонь — с настройками пульта; дальность и права проверяет сервер ({@code map_range}).
 */
public class MapScreen extends Screen {
    /** Пикселей на блок: от 32 блоков на пиксель до 8 пикселей на блок. */
    private static final double MIN_SCALE = 1.0 / 32, MAX_SCALE = 8;
    private static final double ZOOM_STEP = 1.25;
    /** Сдвиг мыши больше этого (пикселей) — перетаскивание карты, а не выбор места. */
    private static final double DRAG_THRESHOLD = 3;
    /** Метки снарядов за краем карты — на столько пикселей внутрь от края. */
    private static final int EDGE_MARGIN = 12;
    /** Сверху — заголовок и курсор; снизу — строка цели (своя, чтобы не лечь на подпись сетки) и кнопки. */
    private static final int TOP = 22, BOTTOM = 44;

    private static final int BG = 0xFF0A1410, GRID = 0x3060FF90, GRID_TEXT = 0xC060FF90, INK = 0xFFD8F0E0, DIM = 0xFF90A898;
    private static final int PANEL = 0xD0000000, TARGET = 0xFFFF3030, SPREAD = 0xC0FF6040, CRAFT = 0xFFFFD040, ROUTE = 0x90FF6040;

    /** Вид карты помнится между открытиями, пока игрок в том же мире и измерении. */
    private static double viewX, viewZ, scale = 1;
    /** Измерение, где поставлен вид (как место у {@link MapTarget}); null — ещё не ставился. */
    @Nullable
    private static ResourceKey<Level> placedIn;

    private final RemoteScreen remote;
    private Button fireButton;
    private double pressX, pressY;
    /** ЛКМ нажата на карте (а не на кнопке) и ещё не отпущена; тянут — сдвиг карты. */
    private boolean pressing, dragging;

    public MapScreen(RemoteScreen remote) {
        super(Component.translatable("airstrike.map.title"));
        this.remote = remote;
    }

    /** Мир сменился: карта снова откроется на игроке. */
    public static void reset() {
        placedIn = null;
        scale = 1;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        // в другом измерении прежний вид — чужие координаты: карта открывается на выбранном месте или на игроке
        if (p != null && mc.level != null && !mc.level.dimension().equals(placedIn)) {
            MapTarget.Place at = MapTarget.get(mc.level).orElse(new MapTarget.Place(p.getX(), p.getZ()));
            viewX = at.x();
            viewZ = at.z();
            placedIn = mc.level.dimension();
        }
        int by = height - 24;
        fireButton = addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.fire").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                b -> fire()).bounds(width / 2 - 154, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("airstrike.map.center"), b -> centerOnPlayer())
                .bounds(width / 2 - 50, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("airstrike.map.back"), b -> onClose())
                .bounds(width / 2 + 54, by, 100, 20).build());
        fireButton.active = selected().isPresent() && !ceiling();
    }

    private Optional<MapTarget.Place> selected() {
        return MapTarget.get(Minecraft.getInstance().level);
    }

    private MapProjection projection() {
        return new MapProjection(width / 2.0, TOP + (height - TOP - BOTTOM) / 2.0, viewX, viewZ, scale);
    }

    private boolean onMap(double x, double y) {
        return y >= TOP && y < height - BOTTOM;
    }

    private void fire() {
        if (selected().isPresent() && !ceiling()) remote.fireOnMap();
    }

    private void centerOnPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        viewX = p.getX();
        viewZ = p.getZ();
    }

    /** У мира с потолком (Незер) карта видит только крышу: место не выбирается, сервер такой удар не примет. */
    private static boolean ceiling() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimensionType().hasCeiling();
    }

    /** Выбрать место (x, z); высоту земли там найдёт сервер. */
    private void select(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ceiling()) return;
        MapProjection map = projection();
        MapTarget.set(mc.level, new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY)));
        fireButton.active = true;
    }

    // ---------------------------------------------------------------- мышь и клавиши

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!onMap(mouseX, mouseY)) return false;
        pressX = mouseX;
        pressY = mouseY;
        pressing = true;
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (!pressing) return false;
        if (!dragging && Math.hypot(mouseX - pressX, mouseY - pressY) < DRAG_THRESHOLD) return true;
        dragging = true;
        viewX -= dragX / scale;
        viewZ -= dragY / scale;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        boolean click = pressing && !dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && onMap(mouseX, mouseY);
        pressing = dragging = false;
        if (click) select(mouseX, mouseY);
        return click || handled;
    }

    /** Колесо — масштаб; точка мира под курсором остаётся под курсором. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || !onMap(mouseX, mouseY)) return false;
        MapProjection before = projection();
        double wx = before.worldX(mouseX), wz = before.worldZ(mouseY);
        scale = Mth.clamp(scale * Math.pow(ZOOM_STEP, scrollY), MIN_SCALE, MAX_SCALE);
        MapProjection after = projection();
        viewX += wx - after.worldX(mouseX);
        viewZ += wz - after.worldZ(mouseY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            fire();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            centerOnPlayer();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Esc — назад к пульту. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(remote);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- картинка

    /** Карта — вместо фона экрана: кнопки рисуются поверх неё. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        g.fill(0, 0, width, height, BG);
        if (p == null) return;
        MapProjection map = projection();
        int bottom = height - BOTTOM;
        TerrainTiles.render(g, map, 0, TOP, width, bottom);
        g.enableScissor(0, TOP, width, bottom);
        map.drawGrid(g, font, 0, TOP, width, bottom, GRID, GRID_TEXT);

        // снаряды — по телеметрии сервера (и вне загруженного мира); кто за краем карты — стрелкой у края в его сторону
        for (ClientFlights.Tracked f : ClientFlights.all()) {
            if (f.phase() == FlightPhase.READY) continue;
            int[] c = map.at(f.position(partialTick)), t = map.at(f.target());
            HudDraw.dotted(g, c[0], c[1], t[0], t[1], ROUTE);
            Component number = Component.literal("№" + f.number);
            int[] edge = edge(c[0], c[1], bottom);
            if (edge == null) {
                Vec3 v = f.velocity();
                HudDraw.heading(g, c[0], c[1], (float) Math.toDegrees(Math.atan2(-v.x, v.z)), CRAFT);
                label(g, number, c[0], c[1] - 16, CRAFT);
            } else {
                int cx = width / 2, cy = (TOP + bottom) / 2;
                HudDraw.heading(g, edge[0], edge[1], (float) Math.toDegrees(Math.atan2(-(c[0] - cx), c[1] - cy)), CRAFT);
                label(g, number, edge[0], edge[1] + (edge[1] < cy ? 10 : -18), CRAFT);
            }
        }

        Vec3 me = p.getPosition(partialTick);
        int[] op = map.at(me);
        HudDraw.heading(g, op[0], op[1], p.getViewYRot(partialTick), 0xFFFFFFFF);
        label(g, Component.translatable("airstrike.map.you"), op[0], op[1] + 9, 0xFFFFFFFF);

        selected().ifPresent(t -> {
            int[] s = map.at(t.x(), t.z());
            Loadout l = remote.loadout();
            if (l.spread() > 0) HudDraw.dottedCircle(g, s[0], s[1], l.spread() * scale, SPREAD);
            g.fill(s[0] - 8, s[1], s[0] - 2, s[1] + 1, TARGET);
            g.fill(s[0] + 3, s[1], s[0] + 9, s[1] + 1, TARGET);
            g.fill(s[0], s[1] - 8, s[0] + 1, s[1] - 2, TARGET);
            g.fill(s[0], s[1] + 3, s[0] + 1, s[1] + 9, TARGET);
            HudDraw.box(g, s[0], s[1], 4, TARGET);
            HudDraw.dotted(g, op[0], op[1], s[0], s[1], 0x80FFFFFF);
        });
        g.disableScissor();
    }

    /** Точка за краем карты — место её метки у края, по лучу из середины карты; на карте — null. */
    @Nullable
    private int[] edge(int x, int y, int bottom) {
        int m = EDGE_MARGIN;
        if (x >= m && x <= width - m && y >= TOP + m && y <= bottom - m) return null;
        double cx = width / 2.0, cy = (TOP + bottom) / 2.0, dx = x - cx, dy = y - cy;
        double f = Math.min(dx == 0 ? Double.MAX_VALUE : (cx - m) / Math.abs(dx), dy == 0 ? Double.MAX_VALUE : (cy - TOP - m) / Math.abs(dy));
        return new int[] {(int) Math.round(cx + dx * f), (int) Math.round(cy + dy * f)};
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        // сверху: что за экран и оружие; справа — где курсор
        g.fill(0, 0, width, TOP, PANEL);
        g.fill(0, height - BOTTOM, width, height, PANEL);
        Loadout l = remote.loadout();
        Component weapon = l.count() > 1 || l.spread() > 0
                ? Component.translatable("airstrike.hud.loadout.salvo", l.weapon().displayName(), l.count(), l.spread())
                : Component.translatable("airstrike.hud.loadout", l.weapon().displayName());
        Component heading = title.copy().withStyle(ChatFormatting.BOLD);
        g.drawString(font, heading, 8, 7, 0xFFFFC040);
        g.drawString(font, weapon.copy().withStyle(ChatFormatting.GOLD), 8 + font.width(heading) + 12, 7, 0xFFFFFFFF);
        if (onMap(mouseX, mouseY)) {
            MapProjection map = projection();
            Component at = place(map.worldX(mouseX), map.worldZ(mouseY), p.position());
            g.drawString(font, at, width - font.width(at) - 8, 7, INK);
        }

        // над кнопками, в нижней панели: выбранное место или подсказка
        Optional<MapTarget.Place> t = selected();
        Component line = t.isPresent()
                ? Component.translatable("airstrike.map.selected", place(t.get().x(), t.get().z(), p.position()))
                : Component.translatable("airstrike.map.hint");
        g.drawCenteredString(font, line, width / 2, height - BOTTOM + 5, t.isPresent() ? 0xFFFF6050 : DIM);
        String note = ceiling() ? "airstrike.map.no_ceiling"
                : !TerrainTiles.farTerrain() ? "airstrike.map.near_only" : TerrainTiles.farPending() ? "airstrike.map.far_pending" : null;
        if (note != null) g.drawString(font, Component.translatable(note).withStyle(ChatFormatting.ITALIC), 8, TOP + 6, DIM);
    }

    /** «X 1200  Z −340 · 2.40 км · азимут 135°» — дальность и азимут от игрока. */
    private static Component place(double x, double z, Vec3 from) {
        double dx = x - from.x, dz = z - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        // азимут — от севера (−Z) по часовой
        int azimuth = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(dx, -dz))), 360);
        Component range = dist >= 1000 ? Component.translatable("airstrike.map.km", String.format(Locale.ROOT, "%.2f", dist / 1000))
                : Component.translatable("airstrike.map.m", String.format(Locale.ROOT, "%.0f", dist));
        return Component.translatable("airstrike.map.place", Mth.floor(x), Mth.floor(z), range, azimuth);
    }

    private void label(GuiGraphics g, Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }
}
