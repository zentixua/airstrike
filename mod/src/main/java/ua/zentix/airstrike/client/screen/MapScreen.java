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
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.hud.HudDraw;
import ua.zentix.airstrike.client.map.MapPlayers;
import ua.zentix.airstrike.client.map.MapProjection;
import ua.zentix.airstrike.client.map.MapTarget;
import ua.zentix.airstrike.client.map.TerrainTiles;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.Waypoints;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Карта наведения: как у оператора на планшете — карта «север вверх» с рельефом ({@link TerrainTiles}: Distant
 * Horizons на километры, иначе загруженные чанки), сетка, где ты и куда смотришь, другие игроки ({@link MapPlayers}),
 * снаряды в полёте. ЛКМ — место удара (координаты, дальность и азимут от тебя) или игрок (бить по нему сервер даёт,
 * только когда его видно, а идёт за ним лишь оружие с камерой, пока он в кадре, — {@code ServerActions.sighted}),
 * тянуть — сдвиг, колесо — масштаб у курсора, Enter — огонь, пробел — к себе. Огонь — с настройками пульта;
 * дальность и права проверяет сервер ({@code map_range}).
 * <p>
 * Маршрут ({@link Waypoints}, помнит {@link MapTarget}): Shift+ЛКМ — точка в конец маршрута (до {@link Waypoints#MAX}),
 * точку тянут мышью, ПКМ по ней убирает её, кнопка — все. Линия идёт от тебя через точки к цели; под картой — длина
 * и время полёта, а длиннее дальности оружия по маршруту (паспорт, {@link WeaponSpec.Route#reach}) линия красная
 * и огня нет. Оружие без полёта по точкам (баллистика, РСЗО) маршрут не берёт — линия тусклая.
 * <p>
 * Знаки поверх рельефа — толстые, с тёмной обводкой ({@link HudDraw#HALO}): на пёстром городе тонкий пунктир терялся.
 */
public class MapScreen extends Screen {
    /** Пикселей на блок: от 32 блоков на пиксель до 8 пикселей на блок. */
    private static final double MIN_SCALE = 1.0 / 32, MAX_SCALE = 8;
    private static final double ZOOM_STEP = 1.25;
    /** Сдвиг мыши больше этого (пикселей) — перетаскивание карты, а не выбор места. */
    private static final double DRAG_THRESHOLD = 3;
    /** Метки снарядов и игроков за краем карты — на столько пикселей внутрь от края. */
    private static final int EDGE_MARGIN = 12;
    /** Клик ближе этого (пикселей) к значку игрока или точки маршрута — он, а не место. */
    private static final int PICK_RADIUS = 9;
    /** Игроков спрашивать у сервера раз в столько тиков, пока карта открыта. */
    private static final int PLAYERS_PERIOD = 10;
    /** Сверху — заголовок и курсор; снизу — строка цели (своя, чтобы не лечь на подпись сетки) и кнопки. */
    private static final int TOP = 22, BOTTOM = 56;

    private static final int BG = 0xFF0A1410, GRID = 0x3060FF90, GRID_TEXT = 0xC060FF90, INK = 0xFFD8F0E0, DIM = 0xFF90A898;
    /** Рельеф чуть приглушён: знаки поверх него читаются, а город и дороги видны. */
    private static final int TERRAIN_DIM = 0x480A1410;
    private static final int PANEL = 0xD0000000, TARGET = 0xFFFF3030, SPREAD = 0xF0FF5040, SPREAD_FILL = 0x38FF3020;
    private static final int CRAFT = 0xFFFFD040, ROUTE = 0xFFFF8040, AIM_LINE = 0xD0FFFFFF, PLAYER = 0xFF50DCFF, OPERATOR = 0xFFFFFFFF;
    /** Маршрут оператора: годится — зелёный, длиннее дальности оружия — красный ({@link #TARGET}), оружию не нужен — {@link #DIM}. */
    private static final int WAYPOINTS = 0xFF7CFF8C;

    /** Вид карты помнится между открытиями, пока игрок в том же мире и измерении. */
    private static double viewX, viewZ, scale = 1;
    /** Измерение, где поставлен вид (как место у {@link MapTarget}); null — ещё не ставился. */
    @Nullable
    private static ResourceKey<Level> placedIn;

    private final RemoteScreen remote;
    private Button fireButton, clearRouteButton;
    private double pressX, pressY;
    /** ЛКМ нажата на карте (а не на кнопке) и ещё не отпущена; тянут — сдвиг карты или точки маршрута. */
    private boolean pressing, dragging;
    /** Точка маршрута, на которой нажата ЛКМ (её тянут), или −1. */
    private int grabbed = -1;
    private int ticks;
    /** Для лога: когда карта впервые нарисована, сколько плиток было готово сразу, записано ли время готовности. */
    private long openedNs;
    private TerrainTiles.Progress atOpen = new TerrainTiles.Progress(0, 0, "");
    private boolean readyLogged;
    /** Другие игроки в этом кадре ({@link MapPlayers#marks}): картинка, клик и строка цели берут одно и то же. */
    private List<MapPlayers.Mark> marks = List.of();

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
        TerrainTiles.opened();
        LocalPlayer p = mc.player;
        // в другом измерении прежний вид — чужие координаты: карта открывается на выбранном месте или на игроке
        if (p != null && mc.level != null && !mc.level.dimension().equals(placedIn)) {
            MapTarget.Place at = MapTarget.get(mc.level).orElse(new MapTarget.Place(p.getX(), p.getZ()));
            viewX = at.x();
            viewZ = at.z();
            placedIn = mc.level.dimension();
        }
        int by = height - 24, bx = width / 2 - 206;
        fireButton = addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.fire").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                b -> fire()).bounds(bx, by, 100, 20).build());
        clearRouteButton = addRenderableWidget(Button.builder(Component.translatable("airstrike.map.route.clear"), b -> MapTarget.clearRoute())
                .bounds(bx + 104, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("airstrike.map.center"), b -> centerOnPlayer())
                .bounds(bx + 208, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("airstrike.map.back"), b -> onClose())
                .bounds(bx + 312, by, 100, 20).build());
        updateButtons();
        // карта открыта с уже выбранным местом — его район тоже грузится заранее (тот же район сервер только продлит)
        if (!ceiling() && selectedPlayer().isEmpty()) selected().ifPresent(MapScreen::preload);
    }

    @Override
    public void tick() {
        if (ticks++ % PLAYERS_PERIOD == 0) MapPlayers.request();
    }

    /** Район места начинает грузиться на сервере уже сейчас: к приказу он чаще всего готов. */
    private static void preload(MapTarget.Place place) {
        PacketDistributor.sendToServer(new C2S.Pick(place.x(), place.z()));
    }

    /** Выбранное место (когда цель пульта — не игрок). */
    private Optional<MapTarget.Place> selected() {
        return MapTarget.get(Minecraft.getInstance().level);
    }

    /** Выбранный игрок: цель пульта — игрок (выбран на карте или в пульте). */
    private Optional<String> selectedPlayer() {
        Loadout l = remote.loadout();
        return l.mode() == TargetMode.PLAYER && !l.player().isEmpty() ? Optional.of(l.player()) : Optional.empty();
    }

    /**
     * Огонь есть по чему: по игроку — в любом мире, по месту — там, где у карты нет потолка; и маршрут, если оружие
     * по нему летит, не длиннее его дальности (игрок не на карте — маршрут проверит сервер).
     */
    private boolean canFire() {
        return (selectedPlayer().isPresent() || selected().isPresent() && !ceiling()) && routeLength().map(len -> len <= reach()).orElse(true);
    }

    private void updateButtons() {
        fireButton.active = canFire();
        clearRouteButton.active = !route().isEmpty();
    }

    /** Точки маршрута в этом измерении. */
    private static List<MapTarget.Place> route() {
        return MapTarget.route(Minecraft.getInstance().level);
    }

    /** Оружие пульта летает по точкам маршрута. */
    private boolean routed() {
        return remote.loadout().weapon().spec().route().waypoints();
    }

    /** Дальность оружия по маршруту, блоков. */
    private double reach() {
        return remote.loadout().weapon().spec().route().reach();
    }

    /** Куда идёт удар, по горизонтали: выбранное место или выбранный игрок, если он на карте. */
    private Optional<Vec3> aimPoint() {
        Optional<String> player = selectedPlayer();
        if (player.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.name().equalsIgnoreCase(player.get())) return Optional.of(new Vec3(m.x(), 0, m.z()));
            }
            return Optional.empty();
        }
        return selected().map(t -> new Vec3(t.x(), 0, t.z()));
    }

    /**
     * Длина пути от игрока через точки маршрута до цели — та же, что проверит сервер ({@code ServerActions.routeProblem}).
     * Пусто — маршрута нет, оружие по нему не летает или цель не известна.
     */
    private Optional<Double> routeLength() {
        Minecraft mc = Minecraft.getInstance();
        Waypoints via = MapTarget.waypoints(mc.level);
        if (via.isEmpty() || !routed() || mc.player == null) return Optional.empty();
        return aimPoint().map(aim -> via.length(mc.player.position(), aim));
    }

    private MapProjection projection() {
        return new MapProjection(width / 2.0, TOP + (height - TOP - BOTTOM) / 2.0, viewX, viewZ, scale);
    }

    private boolean onMap(double x, double y) {
        return y >= TOP && y < height - BOTTOM;
    }

    /** Огонь по выбранному: игрок — цель пульта уже он; иначе место (пульт мог стоять на другом режиме цели). */
    private void fire() {
        if (!canFire()) return;
        if (selectedPlayer().isEmpty()) remote.aimAtPlace();
        remote.fire(MapTarget.waypoints(Minecraft.getInstance().level));
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

    /**
     * Клик по карте: игрок под курсором — он цель; иначе место (x, z), высоту земли там найдёт сервер (верх по карте —
     * его оценка до загрузки места).
     */
    private void select(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        MapPlayers.Mark hit = playerAt(screenX, screenY);
        if (hit != null) {
            remote.aimAtPlayer(hit.name());
        } else {
            if (ceiling()) return;
            MapProjection map = projection();
            MapTarget.Place place = new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY));
            MapTarget.set(mc.level, place);
            remote.aimAtPlace();
            preload(place);
        }
        updateButtons();
    }

    /** Shift+клик: точка в конец маршрута — у оружия, которое летает по точкам, и там, где место можно выбрать. */
    private void addWaypoint(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ceiling() || !routed()) return;
        MapProjection map = projection();
        MapTarget.add(mc.level, new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY)));
        updateButtons();
    }

    /** Точка маршрута, чей значок ближе {@link #PICK_RADIUS} к точке экрана, или −1. */
    private int waypointAt(double screenX, double screenY) {
        MapProjection map = projection();
        List<MapTarget.Place> route = route();
        int best = -1;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (int i = 0; i < route.size(); i++) {
            int[] at = map.at(route.get(i).x(), route.get(i).z());
            double d = Mth.square(at[0] - screenX) + Mth.square(at[1] - screenY);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Игрок, чей значок (на карте или у края) ближе {@link #PICK_RADIUS} к точке экрана. */
    @Nullable
    private MapPlayers.Mark playerAt(double screenX, double screenY) {
        MapProjection map = projection();
        MapPlayers.Mark best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (MapPlayers.Mark m : marks) {
            int[] at = map.at(m.x(), m.z());
            int[] edge = edge(at[0], at[1], height - BOTTOM);
            if (edge != null) at = edge;
            double d = Mth.square(at[0] - screenX) + Mth.square(at[1] - screenY);
            if (d <= bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- мышь и клавиши

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!onMap(mouseX, mouseY)) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // ПКМ по точке маршрута — убрать её
            int i = waypointAt(mouseX, mouseY);
            if (i < 0) return false;
            MapTarget.remove(Minecraft.getInstance().level, i);
            updateButtons();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        pressX = mouseX;
        pressY = mouseY;
        pressing = true;
        dragging = false;
        grabbed = waypointAt(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (!pressing) return false;
        if (!dragging && Math.hypot(mouseX - pressX, mouseY - pressY) < DRAG_THRESHOLD) return true;
        dragging = true;
        if (grabbed >= 0) {
            // тянут точку маршрута — она под курсором (и за край нижней панели не уходит)
            MapProjection map = projection();
            double y = Mth.clamp(mouseY, TOP, height - BOTTOM - 1);
            MapTarget.move(Minecraft.getInstance().level, grabbed, new MapTarget.Place(map.worldX(mouseX), map.worldZ(y)));
            return true;
        }
        viewX -= dragX / scale;
        viewZ -= dragY / scale;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        boolean click = pressing && !dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && onMap(mouseX, mouseY);
        boolean onPoint = grabbed >= 0;
        pressing = dragging = false;
        grabbed = -1;
        // клик по точке маршрута ничего не выбирает: её тянут или убирают
        if (click && !onPoint) {
            if (hasShiftDown()) addWaypoint(mouseX, mouseY);
            else select(mouseX, mouseY);
        }
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

    /** Карта закрыта раньше, чем рельеф вида построился целиком, — тоже в лог: сколько успело. */
    @Override
    public void removed() {
        if (openedNs == 0 || readyLogged) return;
        TerrainTiles.Progress p = TerrainTiles.progress();
        Airstrike.LOG.info("Карта наведения закрыта через {} мс, рельеф вида готов не весь: {} из {} плиток ({}), сразу из памяти {} ({}); {}",
                (System.nanoTime() - openedNs) / 1_000_000, p.ready(), p.visible(), p.layers(), atOpen.ready(), atOpen.layers(), TerrainTiles.stats());
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
        marks = MapPlayers.marks(mc.level, partialTick);
        MapProjection map = projection();
        int bottom = height - BOTTOM;
        TerrainTiles.render(g, map, 0, TOP, width, bottom);
        logReady();
        g.enableScissor(0, TOP, width, bottom);
        g.fill(0, TOP, width, bottom, TERRAIN_DIM);
        map.drawGrid(g, font, 0, TOP, width, bottom, GRID, GRID_TEXT);

        Vec3 me = p.getPosition(partialTick);
        int[] op = map.at(me);
        Loadout l = remote.loadout();
        Optional<String> aimedPlayer = selectedPlayer();

        // цель: район разброса залпа (заливка и граница), перекрестие, линия от оператора
        int[] aim = null;
        if (aimedPlayer.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.name().equalsIgnoreCase(aimedPlayer.get())) aim = map.at(m.x(), m.z());
            }
        } else {
            aim = selected().map(t -> map.at(t.x(), t.z())).orElse(null);
        }
        List<MapTarget.Place> route = route();
        boolean routed = routed() && !route.isEmpty();
        if (aim != null && !routed) HudDraw.dashed(g, op[0], op[1], aim[0], aim[1], 1, AIM_LINE);
        if (!route.isEmpty()) drawRoute(g, map, op, aim, route, mouseX, mouseY);
        if (aim != null) {
            if (l.spread() > 0) {
                float r = (float) (l.spread() * scale);
                HudDraw.disc(g, aim[0], aim[1], r, SPREAD_FILL);
                HudDraw.ring(g, aim[0], aim[1], r, 2, SPREAD);
                Component spread = Component.translatable("airstrike.map.spread", l.spread());
                if (r > 14) label(g, spread, aim[0], Math.round(aim[1] - r) - 11, SPREAD);
            }
        }

        // снаряды — по телеметрии сервера (и вне загруженного мира): штриховой путь к цели, у цели — прицел;
        // кто за краем карты — стрелкой у края в его сторону
        for (ClientFlights.Tracked f : ClientFlights.all()) {
            if (f.phase() == FlightPhase.READY) continue;
            int[] c = map.at(f.position(partialTick)), t = map.at(f.target());
            HudDraw.dashed(g, c[0], c[1], t[0], t[1], 2, ROUTE);
            reticle(g, t[0], t[1], 5, ROUTE);
            Component number = Component.literal("№" + f.number);
            int[] edge = edge(c[0], c[1], bottom);
            if (edge == null) {
                Vec3 v = f.velocity();
                HudDraw.heading(g, c[0], c[1], (float) Math.toDegrees(Math.atan2(-v.x, v.z)), CRAFT);
                label(g, number, c[0], c[1] - 17, CRAFT);
            } else {
                edgeMark(g, edge, c, bottom, number, CRAFT);
            }
        }

        // игроки: значок и имя; за краем — стрелкой; под курсором — рамка (клик — цель)
        MapPlayers.Mark hover = onMap(mouseX, mouseY) ? playerAt(mouseX, mouseY) : null;
        for (MapPlayers.Mark m : marks) {
            int[] at = map.at(m.x(), m.z());
            Component name = Component.literal(m.name());
            boolean aimed = aimedPlayer.isPresent() && m.name().equalsIgnoreCase(aimedPlayer.get());
            int[] edge = edge(at[0], at[1], bottom);
            int[] mark = edge == null ? at : edge;
            if (edge == null) {
                g.fill(at[0] - 4, at[1] - 4, at[0] + 5, at[1] + 5, HudDraw.HALO);
                g.fill(at[0] - 3, at[1] - 3, at[0] + 4, at[1] + 4, aimed ? TARGET : PLAYER);
                label(g, name, at[0], at[1] + 7, aimed ? TARGET : PLAYER);
            } else {
                edgeMark(g, edge, at, bottom, name, aimed ? TARGET : PLAYER);
            }
            if (aimed) reticle(g, mark[0], mark[1], 9, TARGET);
            else if (m == hover) HudDraw.ring(g, mark[0], mark[1], 8, 1, OPERATOR);
        }

        // выбранное место — перекрестие поверх всего
        if (aimedPlayer.isEmpty() && aim != null) crosshair(g, aim[0], aim[1]);

        HudDraw.heading(g, op[0], op[1], p.getViewYRot(partialTick), OPERATOR);
        label(g, Component.translatable("airstrike.map.you"), op[0], op[1] + 10, OPERATOR);
        g.disableScissor();
    }

    /**
     * Время, за которое рельеф вида построился целиком с открытия карты, — один раз в лог (замер на железе игрока).
     * Отсчёт — с первого кадра, где видны плитки: мельче самых крупных плиток карта их не строит.
     */
    private void logReady() {
        TerrainTiles.Progress p = TerrainTiles.progress();
        if (p.visible() == 0) return;
        if (openedNs == 0) {
            openedNs = System.nanoTime();
            atOpen = p;
        }
        if (readyLogged || !p.done()) return;
        readyLogged = true;
        Airstrike.LOG.info("Карта наведения: рельеф вида готов за {} мс — плиток {}, сразу из памяти {} ({}); {}",
                (System.nanoTime() - openedNs) / 1_000_000, p.visible(), atOpen.ready(), atOpen.layers(), TerrainTiles.stats());
    }

    /**
     * Маршрут: линия от игрока через точки к цели и номера точек; под курсором — рамка (тянуть — сдвиг, ПКМ — убрать).
     * Цвет — что с ним будет при пуске: {@link #WAYPOINTS}, {@link #TARGET} (длиннее дальности), {@link #DIM} (оружие
     * летит без точек).
     */
    private void drawRoute(GuiGraphics g, MapProjection map, int[] op, @Nullable int[] aim, List<MapTarget.Place> route, int mouseX, int mouseY) {
        int color = !routed() ? DIM : routeLength().map(len -> len <= reach()).orElse(true) ? WAYPOINTS : TARGET;
        int[] prev = op;
        int[][] at = new int[route.size()][];
        for (int i = 0; i < route.size(); i++) {
            at[i] = map.at(route.get(i).x(), route.get(i).z());
            HudDraw.haloLine(g, prev[0], prev[1], at[i][0], at[i][1], 2, color);
            prev = at[i];
        }
        if (aim != null) HudDraw.haloLine(g, prev[0], prev[1], aim[0], aim[1], 2, color);
        int hover = grabbed >= 0 ? grabbed : onMap(mouseX, mouseY) ? waypointAt(mouseX, mouseY) : -1;
        for (int i = 0; i < route.size(); i++) {
            HudDraw.disc(g, at[i][0] + 0.5f, at[i][1] + 0.5f, 7, HudDraw.HALO);
            HudDraw.disc(g, at[i][0] + 0.5f, at[i][1] + 0.5f, 6, color);
            Component n = Component.literal(Integer.toString(i + 1));
            g.drawString(font, n, at[i][0] - font.width(n) / 2 + 1, at[i][1] - 3, 0xFF000000, false);
            if (i == hover) HudDraw.ring(g, at[i][0] + 0.5f, at[i][1] + 0.5f, 9, 1, OPERATOR);
        }
    }

    /** Прицел у цели: кольцо с точкой. */
    private static void reticle(GuiGraphics g, int x, int y, int r, int color) {
        HudDraw.ring(g, x, y, r, 2, color);
        g.fill(x - 1, y - 1, x + 2, y + 2, HudDraw.HALO);
        g.fill(x, y, x + 1, y + 1, color);
    }

    /** Перекрестие выбранного места: четыре штриха с обводкой вокруг рамки. */
    private static void crosshair(GuiGraphics g, int x, int y) {
        HudDraw.haloLine(g, x - 11, y + 0.5f, x - 3, y + 0.5f, 2, TARGET);
        HudDraw.haloLine(g, x + 4, y + 0.5f, x + 12, y + 0.5f, 2, TARGET);
        HudDraw.haloLine(g, x + 0.5f, y - 11, x + 0.5f, y - 3, 2, TARGET);
        HudDraw.haloLine(g, x + 0.5f, y + 4, x + 0.5f, y + 12, 2, TARGET);
        HudDraw.box(g, x, y, 5, HudDraw.HALO);
        HudDraw.box(g, x, y, 4, TARGET);
    }

    /** Метка у края карты для точки {@code at} за краем: стрелка в её сторону и подпись. */
    private void edgeMark(GuiGraphics g, int[] edge, int[] at, int bottom, Component text, int color) {
        int cx = width / 2, cy = (TOP + bottom) / 2;
        HudDraw.heading(g, edge[0], edge[1], (float) Math.toDegrees(Math.atan2(-(at[0] - cx), at[1] - cy)), color);
        label(g, text, edge[0], edge[1] + (edge[1] < cy ? 10 : -18), color);
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
        updateButtons();
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

        // над кнопками, в нижней панели: выбранная цель или подсказка
        g.drawCenteredString(font, targetLine(p.position()), width / 2, height - BOTTOM + 5, canFire() ? 0xFFFF6050 : DIM);
        routeLine(g);
        String note = ceiling() ? "airstrike.map.no_ceiling"
                : !TerrainTiles.farTerrain() ? "airstrike.map.near_only" : TerrainTiles.farPending() ? "airstrike.map.far_pending" : null;
        if (note != null) g.drawString(font, Component.translatable(note).withStyle(ChatFormatting.ITALIC), 8, TOP + 6, DIM);
    }

    /** «Цель: игрок · где он», «Цель: место» или подсказка. */
    private Component targetLine(Vec3 me) {
        Optional<String> player = selectedPlayer();
        if (player.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.name().equalsIgnoreCase(player.get())) {
                    return Component.translatable("airstrike.map.selected_player", m.name(), place(m.x(), m.z(), me));
                }
            }
            return Component.translatable("airstrike.map.selected_player_away", player.get());
        }
        Optional<MapTarget.Place> t = selected();
        return t.isPresent()
                ? Component.translatable("airstrike.map.selected", place(t.get().x(), t.get().z(), me))
                : Component.translatable("airstrike.map.hint");
    }

    /**
     * Строка маршрута под строкой цели: подсказка, если точек нет; «3 точки · 12.40 км · ≈ 4:55 полёта»; длиннее
     * дальности — сколько и докуда можно; оружие без полёта по точкам — что маршрут ему не нужен.
     */
    private void routeLine(GuiGraphics g) {
        int points = route().size();
        Component line;
        int color;
        if (!routed()) {
            line = points == 0 ? Component.translatable("airstrike.map.route.weapon", remote.loadout().weapon().displayName())
                    : Component.translatable("airstrike.map.route.unused", remote.loadout().weapon().displayName());
            color = DIM;
        } else if (points == 0) {
            line = Component.translatable("airstrike.map.route.hint", Waypoints.MAX);
            color = DIM;
        } else {
            Optional<Double> length = routeLength();
            if (length.isEmpty()) {
                line = Component.translatable("airstrike.map.route.no_target", points, Waypoints.MAX);
                color = WAYPOINTS;
            } else if (length.get() > reach()) {
                line = Component.translatable("airstrike.map.route.too_long", distance(length.get()), distance(reach()));
                color = TARGET;
            } else {
                // время — по маршевой скорости: разгон, набор и пике его почти не меняют
                int seconds = (int) Math.round(length.get() / remote.loadout().weapon().spec().airframe().cruiseSpeed() / 20);
                line = Component.translatable("airstrike.map.route", points, Waypoints.MAX, distance(length.get()),
                        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
                color = WAYPOINTS;
            }
        }
        g.drawCenteredString(font, line, width / 2, height - BOTTOM + 17, color);
    }

    /** «X 1200  Z −340 · 2.40 км · азимут 135°» — дальность и азимут от игрока. */
    private static Component place(double x, double z, Vec3 from) {
        double dx = x - from.x, dz = z - from.z;
        // азимут — от севера (−Z) по часовой
        int azimuth = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(dx, -dz))), 360);
        return Component.translatable("airstrike.map.place", Mth.floor(x), Mth.floor(z), distance(Math.sqrt(dx * dx + dz * dz)), azimuth);
    }

    /** «2.40 км» или «340 м». */
    private static Component distance(double blocks) {
        return blocks >= 1000 ? Component.translatable("airstrike.map.km", String.format(Locale.ROOT, "%.2f", blocks / 1000))
                : Component.translatable("airstrike.map.m", String.format(Locale.ROOT, "%.0f", blocks));
    }

    private void label(GuiGraphics g, Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }
}
