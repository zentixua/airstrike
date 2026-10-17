package ua.zentix.incoming.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.hud.HudDraw;
import ua.zentix.incoming.client.map.MapPlayers;
import ua.zentix.incoming.client.map.MapProjection;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Sightings;

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
 * точку тянут мышью, ПКМ по ней убирает её, кнопка — все. Линия идёт от места пуска через точки к цели; под картой —
 * длина и время полёта, а длиннее дальности оружия по маршруту (паспорт, {@link WeaponSpec.Route#reach}) линия красная
 * и огня нет. Оружие без полёта по точкам (баллистика, РСЗО) маршрут не берёт — линия тусклая.
 * <p>
 * Место пуска (помнит {@link MapTarget}) — только у оператора и хоста ({@link #mayPickLaunchSite}): Ctrl+ЛКМ или кнопка
 * и клик — пусковая встанет там (как у команды {@code from x z}), значок тянут мышью, ПКМ по нему или кнопка — снова
 * пуск у себя. Без него пуск — у игрока; проверяет место сервер ({@code ServerActions.launchSiteProblem}).
 * <p>
 * Знаки поверх рельефа — толстые, с тёмной обводкой ({@link HudDraw#HALO}): на пёстром городе тонкий пунктир терялся бы.
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
    /**
     * Сверху — заголовок и курсор; снизу — строка цели (своя, чтобы не лечь на подпись сетки), строка маршрута и кнопки,
     * у тех, кто выбирает место пуска, — ещё строка места пуска ({@link #LAUNCH_LINE}).
     */
    private static final int TOP = 22, BOTTOM = 56, LAUNCH_LINE = 12;
    /** Кнопки под картой: не шире стольких пикселей, между ними — {@link #BUTTON_GAP}. */
    private static final int BUTTON_W = 100, BUTTON_GAP = 4;

    private static final int BG = 0xFF0A1410, GRID = 0x3060FF90, GRID_TEXT = 0xC060FF90, INK = 0xFFD8F0E0, DIM = 0xFF90A898;
    /** Рельеф чуть приглушён: знаки поверх него читаются, а город и дороги видны. */
    private static final int TERRAIN_DIM = 0x480A1410;
    private static final int PANEL = 0xD0000000, TARGET = 0xFFFF3030, SPREAD = 0xF0FF5040, SPREAD_FILL = 0x38FF3020;
    private static final int CRAFT = 0xFFFFD040, ROUTE = 0xFFFF8040, AIM_LINE = 0xD0FFFFFF, PLAYER = 0xFF50DCFF, OPERATOR = 0xFFFFFFFF;
    /** Маршрут оператора: годится — зелёный, длиннее дальности оружия — красный ({@link #TARGET}), оружию не нужен — {@link #DIM}. */
    private static final int WAYPOINTS = 0xFF7CFF8C;
    /** Свои по команде и замеченные чужие снаряды и прочее (не игроки и не аппараты). */
    private static final int FRIEND = 0xFF60FF80, HOSTILE = 0xFFFF6040;
    /** Место пуска: ни с чем на карте не спутать (игроки голубые, свои зелёные, снаряды жёлтые, цель красная). */
    private static final int LAUNCH = 0xFFC080FF;

    /** Вид карты помнится между открытиями, пока игрок в том же мире и измерении. */
    private static double viewX, viewZ, scale = 1;
    /** Измерение, где поставлен вид (как место у {@link MapTarget}); null — ещё не ставился. */
    @Nullable
    private static ResourceKey<Level> placedIn;

    private final RemoteScreen remote;
    private Button fireButton, clearRouteButton, launchButton;
    /** Игрок выбирает место пуска ({@link #mayPickLaunchSite}; решено при открытии): кнопка, строка и значок. */
    private boolean launchAllowed;
    /** Следующий клик по карте — место пуска (нажата кнопка места пуска). */
    private boolean pickingLaunch;
    private double pressX, pressY;
    /** ЛКМ нажата на карте (а не на кнопке) и ещё не отпущена; тянут — сдвиг карты, точки маршрута или места пуска. */
    private boolean pressing, dragging;
    /** Точка маршрута, на которой нажата ЛКМ (её тянут), или −1. */
    private int grabbed = -1;
    /** ЛКМ нажата на значке места пуска (его тянут). */
    private boolean grabbedLaunch;
    private int ticks;
    /** Для лога: когда карта впервые нарисована, сколько плиток было готово сразу, записано ли время готовности. */
    private long openedNs;
    private TerrainTiles.Progress atOpen = new TerrainTiles.Progress(0, 0, "");
    private boolean readyLogged;
    /** Другие игроки в этом кадре ({@link MapPlayers#marks}): картинка, клик и строка цели берут одно и то же. */
    private List<MapPlayers.Mark> marks = List.of();

    public MapScreen(RemoteScreen remote) {
        super(Component.translatable("incoming.map.title"));
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
        launchAllowed = mayPickLaunchSite();
        if (!launchAllowed) pickingLaunch = false;
        fireButton = Button.builder(Component.translatable("incoming.remote.fire").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), b -> fire()).build();
        clearRouteButton = Button.builder(Component.translatable("incoming.map.route.clear"), b -> MapTarget.clearRoute()).build();
        launchButton = Button.builder(Component.translatable("incoming.map.launch.pick"), b -> launchButton())
                .tooltip(Tooltip.create(Component.translatable("incoming.map.launch.tooltip"))).build();
        Button center = Button.builder(Component.translatable("incoming.map.center"), b -> centerOnPlayer()).build();
        Button back = Button.builder(Component.translatable("incoming.map.back"), b -> onClose()).build();
        List<Button> row = launchAllowed ? List.of(fireButton, clearRouteButton, launchButton, center, back) : List.of(fireButton, clearRouteButton, center, back);
        // ряд по середине, кнопки уже, если окно узкое (крупный интерфейс): пять по 100 не помещаются в 480 пикселей
        int n = row.size(), w = Math.min(BUTTON_W, (width - 8 - BUTTON_GAP * (n - 1)) / n), x = (width - n * w - BUTTON_GAP * (n - 1)) / 2;
        for (Button b : row) {
            b.setRectangle(w, 20, x, height - 24);
            addRenderableWidget(b);
            x += w + BUTTON_GAP;
        }
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
        launchButton.active = !ceiling();
        launchButton.setMessage(pickingLaunch ? Component.translatable("incoming.map.launch.picking").withStyle(ChatFormatting.YELLOW)
                : launchSite().isPresent() ? Component.translatable("incoming.map.launch.reset") : Component.translatable("incoming.map.launch.pick"));
    }

    /**
     * Место пуска на карте выбирает оператор или хост мира — те же, кому его даёт сервер
     * ({@code ServerActions.launchSiteProblem}: {@code NuclearKeys.trusted}); у остальных его на карте нет.
     */
    static boolean mayPickLaunchSite() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.hasPermissions(2) || mc.hasSingleplayerServer());
    }

    /** Место пуска, с которого пойдёт удар; пусто — у игрока (не выбрано или выбирать его игроку нельзя). */
    private Optional<MapTarget.Place> launchSite() {
        return launchAllowed ? MapTarget.launch(Minecraft.getInstance().level) : Optional.empty();
    }

    /** Откуда идёт путь по горизонтали: место пуска, без него — игрок. */
    private Vec3 start(Vec3 me) {
        return launchSite().map(l -> new Vec3(l.x(), 0, l.z())).orElse(me);
    }

    /** Кнопка места пуска: выбрать на карте, передумать или снова пускать у себя. */
    private void launchButton() {
        if (pickingLaunch) pickingLaunch = false;
        else if (launchSite().isPresent()) MapTarget.clearLaunch();
        else pickingLaunch = true;
        updateButtons();
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
                if (m.target() && m.name().equalsIgnoreCase(player.get())) return Optional.of(new Vec3(m.x(), 0, m.z()));
            }
            return Optional.empty();
        }
        return selected().map(t -> new Vec3(t.x(), 0, t.z()));
    }

    /**
     * Длина пути от места пуска (без него — от игрока) через точки маршрута до цели — та же, что проверит сервер
     * ({@code ServerActions.routeProblem}). Пусто — маршрута нет, оружие по нему не летает или цель не известна.
     */
    private Optional<Double> routeLength() {
        Minecraft mc = Minecraft.getInstance();
        Waypoints via = MapTarget.waypoints(mc.level);
        if (via.isEmpty() || !routed() || mc.player == null) return Optional.empty();
        Vec3 start = start(mc.player.position());
        return aimPoint().map(aim -> via.length(start, aim));
    }

    /** Нижняя панель: строки и кнопки; у тех, кто выбирает место пуска, — на строку выше. */
    private int panel() {
        return BOTTOM + (launchAllowed ? LAUNCH_LINE : 0);
    }

    private MapProjection projection() {
        return new MapProjection(width / 2.0, TOP + (height - TOP - panel()) / 2.0, viewX, viewZ, scale);
    }

    private boolean onMap(double x, double y) {
        return y >= TOP && y < height - panel();
    }

    /** Огонь по выбранному: игрок — цель пульта уже он; иначе место (пульт мог стоять на другом режиме цели). */
    private void fire() {
        if (!canFire()) return;
        if (selectedPlayer().isEmpty()) remote.aimAtPlace();
        remote.fire(MapTarget.waypoints(Minecraft.getInstance().level), launchSite().map(l -> new Vec3(l.x(), 0, l.z())));
    }

    private void centerOnPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        viewX = p.getX();
        viewZ = p.getZ();
    }

    /** У мира с потолком (Незер) карта видит только крышу: место не выбирается, сервер такой удар не примет. */
    static boolean ceiling() {
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

    /** Место пуска — там, где на карте точка экрана (Ctrl+клик или клик после кнопки места пуска). */
    private void placeLaunch(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        pickingLaunch = false;
        if (mc.level == null || ceiling() || !launchAllowed) return;
        MapProjection map = projection();
        MapTarget.setLaunch(mc.level, new MapTarget.Place(map.worldX(screenX), map.worldZ(screenY)));
        updateButtons();
    }

    /** Значок места пуска ближе {@link #PICK_RADIUS} к точке экрана (его тянут или убирают). */
    private boolean launchAt(double screenX, double screenY) {
        Optional<MapTarget.Place> site = launchSite();
        if (site.isEmpty()) return false;
        int[] at = projection().at(site.get().x(), site.get().z());
        return Mth.square(at[0] - screenX) + Mth.square(at[1] - screenY) <= PICK_RADIUS * PICK_RADIUS;
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

    /** Чужой игрок, чей значок (на карте или у края) ближе {@link #PICK_RADIUS} к точке экрана. */
    @Nullable
    private MapPlayers.Mark playerAt(double screenX, double screenY) {
        MapProjection map = projection();
        MapPlayers.Mark best = null;
        double bestD = PICK_RADIUS * PICK_RADIUS;
        for (MapPlayers.Mark m : marks) {
            if (!m.target()) continue;
            int[] at = map.at(m.x(), m.z());
            int[] edge = edge(at[0], at[1], height - panel());
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
            // ПКМ по точке маршрута — убрать её, по месту пуска — пуск снова у себя
            int i = waypointAt(mouseX, mouseY);
            if (i >= 0) MapTarget.remove(Minecraft.getInstance().level, i);
            else if (launchAt(mouseX, mouseY)) MapTarget.clearLaunch();
            else return false;
            updateButtons();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        pressX = mouseX;
        pressY = mouseY;
        pressing = true;
        dragging = false;
        grabbed = waypointAt(mouseX, mouseY);
        grabbedLaunch = grabbed < 0 && !pickingLaunch && launchAt(mouseX, mouseY);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (!pressing) return false;
        if (!dragging && Math.hypot(mouseX - pressX, mouseY - pressY) < DRAG_THRESHOLD) return true;
        dragging = true;
        if (grabbed >= 0 || grabbedLaunch) {
            // тянут точку маршрута или место пуска — оно под курсором (и за край нижней панели не уходит)
            MapProjection map = projection();
            MapTarget.Place place = new MapTarget.Place(map.worldX(mouseX), map.worldZ(Mth.clamp(mouseY, TOP, height - panel() - 1)));
            if (grabbedLaunch) MapTarget.setLaunch(Minecraft.getInstance().level, place);
            else MapTarget.move(Minecraft.getInstance().level, grabbed, place);
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
        boolean onPoint = grabbed >= 0 || grabbedLaunch;
        pressing = dragging = grabbedLaunch = false;
        grabbed = -1;
        // клик по точке маршрута или месту пуска ничего не выбирает: их тянут или убирают
        if (click && !onPoint) {
            if (pickingLaunch || launchAllowed && hasControlDown()) placeLaunch(mouseX, mouseY);
            else if (hasShiftDown()) addWaypoint(mouseX, mouseY);
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
        // Esc, пока выбирают место пуска, — передумать, а не закрыть карту
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && pickingLaunch) {
            pickingLaunch = false;
            updateButtons();
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
        Incoming.LOG.info("Targeting map closed after {} ms with view terrain incomplete: {} of {} tiles ({}), from memory at once {} ({}); {}",
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
        int bottom = height - panel();
        TerrainTiles.render(g, map, 0, TOP, width, bottom);
        logReady();
        g.enableScissor(0, TOP, width, bottom);
        g.fill(0, TOP, width, bottom, TERRAIN_DIM);
        map.drawGrid(g, font, 0, TOP, width, bottom, GRID, GRID_TEXT);

        Vec3 me = p.getPosition(partialTick);
        int[] op = map.at(me);
        Optional<MapTarget.Place> site = launchSite();
        // путь к цели — от места пуска, без него — от игрока
        int[] start = site.map(t -> map.at(t.x(), t.z())).orElse(op);
        Loadout l = remote.loadout();
        Optional<String> aimedPlayer = selectedPlayer();

        // цель: район разброса залпа (заливка и граница), перекрестие, линия от оператора
        int[] aim = null;
        if (aimedPlayer.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.target() && m.name().equalsIgnoreCase(aimedPlayer.get())) aim = map.at(m.x(), m.z());
            }
        } else {
            aim = selected().map(t -> map.at(t.x(), t.z())).orElse(null);
        }
        List<MapTarget.Place> route = route();
        boolean routed = routed() && !route.isEmpty();
        if (aim != null && !routed) HudDraw.dashed(g, start[0], start[1], aim[0], aim[1], 1, AIM_LINE);
        if (!route.isEmpty()) drawRoute(g, map, start, aim, route, mouseX, mouseY);
        if (aim != null) {
            if (l.spread() > 0) {
                float r = (float) (l.spread() * scale);
                HudDraw.disc(g, aim[0], aim[1], r, SPREAD_FILL);
                HudDraw.ring(g, aim[0], aim[1], r, 2, SPREAD);
                Component spread = Component.translatable("incoming.map.spread", l.spread());
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
            Component number = Component.translatable("incoming.hud.number", f.number);
            int[] edge = edge(c[0], c[1], bottom);
            if (edge == null) {
                Vec3 v = f.velocity();
                HudDraw.heading(g, c[0], c[1], (float) Math.toDegrees(Math.atan2(-v.x, v.z)), CRAFT);
                label(g, number, c[0], c[1] - 17, CRAFT);
            } else {
                edgeMark(g, edge, c, bottom, number, CRAFT);
            }
        }

        // свои и замеченное: значок и имя (давно виденное — бледнее, с давностью); за краем — стрелкой; под курсором —
        // рамка (клик по чужому игроку — цель)
        MapPlayers.Mark hover = onMap(mouseX, mouseY) ? playerAt(mouseX, mouseY) : null;
        for (MapPlayers.Mark m : marks) {
            int[] at = map.at(m.x(), m.z());
            Component named = m.kind() == Sightings.Kind.AIRCRAFT && m.name().isBlank()
                    ? Component.translatable("incoming.target.aircraft") : Component.literal(m.name());
            Component name = m.age() > 0 ? Component.translatable("incoming.map.last_seen", named, m.age()) : named;
            boolean aimed = m.target() && aimedPlayer.isPresent() && m.name().equalsIgnoreCase(aimedPlayer.get());
            int color = aimed ? TARGET : markColor(m);
            int[] edge = edge(at[0], at[1], bottom);
            int[] mark = edge == null ? at : edge;
            if (edge == null) {
                int r = m.kind() == Sightings.Kind.PROJECTILE ? 2 : 3;
                g.fill(at[0] - r - 1, at[1] - r - 1, at[0] + r + 2, at[1] + r + 2, HudDraw.HALO);
                g.fill(at[0] - r, at[1] - r, at[0] + r + 1, at[1] + r + 1, color);
                label(g, name, at[0], at[1] + 7, color);
            } else {
                edgeMark(g, edge, at, bottom, name, color);
            }
            if (aimed) reticle(g, mark[0], mark[1], 9, TARGET);
            else if (m == hover) HudDraw.ring(g, mark[0], mark[1], 8, 1, OPERATOR);
        }

        // выбранное место — перекрестие поверх всего
        if (aimedPlayer.isEmpty() && aim != null) crosshair(g, aim[0], aim[1]);

        // место пуска: значок с подписью (под курсором — рамка: тянуть — сдвиг, ПКМ — убрать), за краем — стрелкой; пока
        // его выбирают — под курсором
        if (site.isPresent()) {
            Component name = Component.translatable("incoming.map.launch.label");
            int[] edge = edge(start[0], start[1], bottom);
            if (edge == null) {
                launchMark(g, start[0], start[1], LAUNCH);
                label(g, name, start[0], start[1] + 10, LAUNCH);
                if (grabbedLaunch || onMap(mouseX, mouseY) && grabbed < 0 && launchAt(mouseX, mouseY)) HudDraw.ring(g, start[0] + 0.5f, start[1] + 0.5f, 10, 1, OPERATOR);
            } else {
                edgeMark(g, edge, start, bottom, name, LAUNCH);
            }
        }
        if (pickingLaunch && onMap(mouseX, mouseY)) launchMark(g, mouseX, mouseY, (LAUNCH & 0x00FFFFFF) | 0x90000000);

        HudDraw.heading(g, op[0], op[1], p.getViewYRot(partialTick), OPERATOR);
        label(g, Component.translatable("incoming.map.you"), op[0], op[1] + 10, OPERATOR);
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
        Incoming.LOG.info("Targeting map: view terrain ready in {} ms — tiles {}, from memory at once {} ({}); {}",
                (System.nanoTime() - openedNs) / 1_000_000, p.visible(), atOpen.ready(), atOpen.layers(), TerrainTiles.stats());
    }

    /**
     * Маршрут: линия от места пуска ({@code start}: выбранное или игрок) через точки к цели и номера точек; под курсором —
     * рамка (тянуть — сдвиг, ПКМ — убрать). Цвет — что с ним будет при пуске: {@link #WAYPOINTS}, {@link #TARGET}
     * (длиннее дальности), {@link #DIM} (оружие летит без точек).
     */
    private void drawRoute(GuiGraphics g, MapProjection map, int[] start, @Nullable int[] aim, List<MapTarget.Place> route, int mouseX, int mouseY) {
        int color = !routed() ? DIM : routeLength().map(len -> len <= reach()).orElse(true) ? WAYPOINTS : TARGET;
        int[] prev = start;
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

    /** Значок места пуска: ромб с обводкой. */
    private static void launchMark(GuiGraphics g, int x, int y, int color) {
        diamond(g, x, y, 7, HudDraw.HALO);
        diamond(g, x, y, 5, color);
    }

    /** Ромб, залитый целиком, радиусом {@code r}. */
    private static void diamond(GuiGraphics g, int x, int y, int r, int color) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(x - half, y + i, x + half + 1, y + i + 1, color);
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
        g.fill(0, height - panel(), width, height, PANEL);
        Loadout l = remote.loadout();
        Component weapon = l.count() > 1 || l.spread() > 0
                ? Component.translatable("incoming.hud.loadout.salvo", l.weapon().displayName(), l.count(), l.spread())
                : Component.translatable("incoming.hud.loadout", l.weapon().displayName());
        Component heading = title.copy().withStyle(ChatFormatting.BOLD);
        g.drawString(font, heading, 8, 7, 0xFFFFC040);
        g.drawString(font, weapon.copy().withStyle(ChatFormatting.GOLD), 8 + font.width(heading) + 12, 7, 0xFFFFFFFF);
        if (onMap(mouseX, mouseY)) {
            MapProjection map = projection();
            Component at = place(map.worldX(mouseX), map.worldZ(mouseY), p.position());
            g.drawString(font, at, width - font.width(at) - 8, 7, INK);
        }

        // над кнопками, в нижней панели: выбранная цель или подсказка, маршрут, место пуска
        g.drawCenteredString(font, targetLine(p.position()), width / 2, height - panel() + 5, canFire() ? 0xFFFF6050 : DIM);
        routeLine(g);
        if (launchAllowed && !ceiling()) launchLine(g);
        String note = ceiling() ? "incoming.map.no_ceiling"
                : !TerrainTiles.farTerrain() ? "incoming.map.near_only" : TerrainTiles.farPending() ? "incoming.map.far_pending" : null;
        if (note != null) g.drawString(font, Component.translatable(note).withStyle(ChatFormatting.ITALIC), 8, TOP + 6, DIM);
    }

    /** Цвет метки: свой, чужой игрок, аппарат, снаряд; давно виденное — бледнее. */
    private static int markColor(MapPlayers.Mark m) {
        int rgb = m.friendly() ? FRIEND : switch (m.kind()) {
            case PLAYER -> PLAYER;
            case AIRCRAFT -> CRAFT;
            case PROJECTILE, OTHER -> HOSTILE;
        };
        return m.age() > 0 ? (rgb & 0x00FFFFFF) | 0xA0000000 : rgb;
    }

    /** «Цель: игрок · где он», «Цель: место» или подсказка. */
    private Component targetLine(Vec3 me) {
        Optional<String> player = selectedPlayer();
        if (player.isPresent()) {
            for (MapPlayers.Mark m : marks) {
                if (m.target() && m.name().equalsIgnoreCase(player.get())) {
                    return Component.translatable("incoming.map.selected_player", m.name(), place(m.x(), m.z(), me));
                }
            }
            return Component.translatable("incoming.map.selected_player_away", player.get());
        }
        Optional<MapTarget.Place> t = selected();
        return t.isPresent()
                ? Component.translatable("incoming.map.selected", place(t.get().x(), t.get().z(), me))
                : Component.translatable("incoming.map.hint");
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
            line = points == 0 ? Component.translatable("incoming.map.route.weapon", remote.loadout().weapon().displayName())
                    : Component.translatable("incoming.map.route.unused", remote.loadout().weapon().displayName());
            color = DIM;
        } else if (points == 0) {
            line = Component.translatable("incoming.map.route.hint", Waypoints.MAX);
            color = DIM;
        } else {
            Optional<Double> length = routeLength();
            if (length.isEmpty()) {
                line = Component.translatable("incoming.map.route.no_target", points, Waypoints.MAX);
                color = WAYPOINTS;
            } else if (length.get() > reach()) {
                line = Component.translatable("incoming.map.route.too_long", distance(length.get()), distance(reach()));
                color = TARGET;
            } else {
                // время — по маршевой скорости: разгон, набор и пике его почти не меняют
                int seconds = (int) Math.round(length.get() / remote.loadout().weapon().spec().airframe().cruiseSpeed() / 20);
                line = Component.translatable("incoming.map.route", points, Waypoints.MAX, distance(length.get()),
                        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
                color = WAYPOINTS;
            }
        }
        g.drawCenteredString(font, line, width / 2, height - panel() + 17, color);
    }

    /**
     * Строка места пуска под строкой маршрута (у оператора и хоста): как его выбрать; выбранное — где оно и сколько от
     * него до цели, а что с ним сделает оружие — по способу пуска из паспорта ({@link WeaponSpec.Launch}): пусковая
     * встанет на нём, B-2 зайдёт с его стороны, МБР стартует со своей площадки. Пульт с привязанными стационарными
     * пусковыми пускает с них.
     */
    private void launchLine(GuiGraphics g) {
        Optional<MapTarget.Place> site = launchSite();
        Component line;
        if (remote.linked() > 0) {
            line = Component.translatable("incoming.map.launch.linked", remote.linked());
        } else if (pickingLaunch) {
            line = Component.translatable("incoming.map.launch.picking_hint");
        } else if (site.isEmpty()) {
            line = Component.translatable("incoming.map.launch.hint");
        } else {
            WeaponSpec.Launch launch = remote.loadout().weapon().spec().launch();
            Component at = Component.translatable("incoming.map.launch.at", Mth.floor(site.get().x()), Mth.floor(site.get().z()));
            Optional<Vec3> aim = aimPoint();
            if (aim.isPresent()) {
                double dx = aim.get().x - site.get().x(), dz = aim.get().z - site.get().z();
                at = Component.translatable("incoming.map.launch.at_target", at, distance(Math.sqrt(dx * dx + dz * dz)));
            }
            line = switch (launch) {
                case ICBM -> Component.translatable("incoming.map.launch.unused", at, remote.loadout().weapon().displayName());
                case BOMBER -> Component.translatable("incoming.map.launch.approach", at, remote.loadout().weapon().displayName());
                case GUIDED, ROCKET, LOITER -> Component.translatable("incoming.map.launch.set", at);
            };
        }
        int color = site.isPresent() && remote.linked() == 0 || pickingLaunch ? LAUNCH : DIM;
        g.drawCenteredString(font, line, width / 2, height - panel() + 29, color);
    }

    /** «X 1200  Z −340 · 2.40 км · азимут 135°» — дальность и азимут от игрока. */
    private static Component place(double x, double z, Vec3 from) {
        double dx = x - from.x, dz = z - from.z;
        // азимут — от севера (−Z) по часовой
        int azimuth = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(dx, -dz))), 360);
        return Component.translatable("incoming.map.place", Mth.floor(x), Mth.floor(z), distance(Math.sqrt(dx * dx + dz * dz)), azimuth);
    }

    /** «2.40 км» или «340 м». */
    private static Component distance(double blocks) {
        return blocks >= 1000 ? Component.translatable("incoming.map.km", String.format(Locale.ROOT, "%.2f", blocks / 1000))
                : Component.translatable("incoming.map.m", String.format(Locale.ROOT, "%.0f", blocks));
    }

    private void label(GuiGraphics g, Component text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text) / 2, y, color);
    }
}
