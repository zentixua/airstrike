package ua.zentix.incoming.client.screen;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.map.MapPlayers;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.client.nuclear.NukeArming;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.item.DesignatorItem;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.TargetPicker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Экран пульта (вместо меню в чате): оружие, количество, разброс и цель — куда смотрю, вокруг меня,
 * игрок из списка, аппарат рядом или место на карте ({@link MapScreen}); у ядерной — мощность и подрыв, пуск через 3 с взведения.
 * Настройки сохраняются в пульте; пуск проверяет сервер.
 */
public class RemoteScreen extends Screen {
    private static final int W = 320;
    /** Строка текста: высота шрифта Minecraft. */
    private static final int LINE = 9;
    /**
     * Высота окна без строк описания оружия и подсказки цели. Тексты переносятся по ширине окна, а не уходят за его края:
     * описанию — столько строк, сколько у самого длинного из меню (окно не прыгает при смене оружия), подсказке — две.
     */
    private static final int BASE_H = 204, HINT_LINES = 2;
    private static final int PAGE = 8;
    /** Настройки для тех, у кого нет пульта в руке (экран из команды). */
    private static Loadout detached = Loadout.DEFAULT;

    @Nullable
    private final InteractionHand hand;
    private Loadout loadout;
    @Nullable
    private UUID aircraft;
    private int page;
    private final List<Choice> choices = new ArrayList<>();
    /** Строк описания оружия и высота окна: считаются в {@link #init()} по шрифту. */
    private int descLines = 1, h = BASE_H + (1 + HINT_LINES) * LINE;

    private record Choice(Component label, String player, @Nullable UUID aircraft) {}

    /** Игроков спрашивать у сервера раз в столько тиков, пока открыт режим «игрок». */
    private static final int PLAYERS_PERIOD = 10;
    private int ticks;
    /** Игроки, которые сейчас в списке. */
    private List<String> shownPlayers = List.of();

    public RemoteScreen() {
        super(Component.translatable("incoming.remote.title"));
        LocalPlayer p = Minecraft.getInstance().player;
        InteractionHand h = null;
        if (p != null) {
            for (InteractionHand x : InteractionHand.values()) {
                if (p.getItemInHand(x).getItem() instanceof DesignatorItem) {
                    h = x;
                    break;
                }
            }
        }
        this.hand = h;
        this.loadout = h != null && p != null ? DesignatorItem.loadout(p.getItemInHand(h)) : detached;
    }

    @Override
    protected void init() {
        List<WeaponType> menu = WeaponType.menu();
        descLines = Math.max(1, menu.stream().mapToInt(w -> font.split(w.description(), W - 8).size()).max().orElse(1));
        h = BASE_H + (descLines + HINT_LINES) * LINE;
        int x0 = (width - W) / 2, y0 = (height - h) / 2;
        int y = y0 + 22;

        // оружие
        List<Cell> weapons = new ArrayList<>();
        for (WeaponType w : menu) {
            weapons.add(new Cell(Button.builder(label(w.displayName(), loadout.weapon() == w), btn -> set(loadout.withWeapon(w)))
                    .bounds(0, y, 0, 20).tooltip(Tooltip.create(w.description())).build()));
        }
        row(weapons, x0);
        // под кнопками — описание выбранного оружия (render)
        y += 22 + descLines * LINE + 3;

        // количество и разброс; у ядерной — мощность и подрыв
        int sw = (W - 12) / 2;
        Loadout.Nuke n = loadout.nuke();
        if (loadout.nuclear()) {
            // МБР: мощность и подрыв; ракета и B-2 с ядерной БЧ: переключатель БЧ, мощность, у ракеты — подрыв
            WeaponSpec.Warhead warhead = loadout.weapon().spec().warhead();
            List<Cell> nuke = new ArrayList<>();
            if (!warhead.always()) nuke.add(new Cell(warheadButton(n, 0, y, 0)));
            List<Component> yields = new ArrayList<>(List.of(yieldLabel(n.yieldKt())));
            for (Yield v : Yield.values()) yields.add(yieldLabel((int) v.kt()));
            nuke.add(new Cell(Button.builder(yields.getFirst(),
                    b -> set(loadout.withNuke(new Loadout.Nuke(nextYield(n.yieldKt()), n.airBurst(), n.onCarrier())))).bounds(0, y, 0, 20).build(), yields));
            if (warhead.chooseBurst()) {
                Component air = Component.translatable("incoming.remote.burst.air"), ground = Component.translatable("incoming.remote.burst.ground");
                nuke.add(new Cell(Button.builder(n.airBurst() ? air : ground,
                        b -> set(loadout.withNuke(new Loadout.Nuke(n.yieldKt(), !n.airBurst(), n.onCarrier())))).bounds(0, y, 0, 20)
                        .tooltip(Tooltip.create(Component.translatable("incoming.remote.burst.tooltip"))).build(), List.of(air, ground)));
            }
            row(nuke, x0);
        } else if (Loadout.carriesNuke(loadout.weapon())) {
            int cw = (W - 16) / 3;
            addRenderableWidget(new IntSlider(x0 + 4, y, cw, 20, "incoming.remote.count", 1, 30, loadout.count(), v -> commit(loadout.withCount(v))));
            addRenderableWidget(new IntSlider(x0 + 8 + cw, y, cw, 20, "incoming.remote.spread", 0, 150, loadout.spread(), v -> commit(loadout.withSpread(v))));
            addRenderableWidget(warheadButton(n, x0 + 12 + 2 * cw, y, cw));
        } else {
            addRenderableWidget(new IntSlider(x0 + 4, y, sw, 20, "incoming.remote.count", 1, 30, loadout.count(), v -> commit(loadout.withCount(v))));
            addRenderableWidget(new IntSlider(x0 + 8 + sw, y, sw, 20, "incoming.remote.spread", 0, 150, loadout.spread(), v -> commit(loadout.withSpread(v))));
        }
        y += 26;

        // режим цели
        List<Cell> modes = new ArrayList<>();
        for (TargetMode m : TargetMode.values()) {
            modes.add(new Cell(Button.builder(label(m.displayName(), loadout.mode() == m), b -> {
                page = 0;
                set(loadout.withMode(m));
            }).bounds(0, y, 0, 20).build()));
        }
        row(modes, x0);
        y += 24;

        // место на карте: открыть карту наведения
        if (loadout.mode() == TargetMode.MAP) {
            addRenderableWidget(Button.builder(Component.translatable("incoming.remote.open_map").withStyle(ChatFormatting.YELLOW), b -> openMap())
                    .bounds(x0 + 4, y, W - 8, 20).build());
        }

        // список: игроки или аппараты рядом
        choices.clear();
        if (loadout.mode() == TargetMode.PLAYER) collectPlayers();
        if (loadout.mode() == TargetMode.AIRCRAFT) collectAircraft();
        int cw = (W - 12) / 2;
        int pages = Math.max(1, (choices.size() + PAGE - 1) / PAGE);
        page = Mth.clamp(page, 0, pages - 1);
        for (int i = 0; i < PAGE && page * PAGE + i < choices.size(); i++) {
            Choice c = choices.get(page * PAGE + i);
            boolean on = c.aircraft != null ? c.aircraft.equals(aircraft) : c.player.equals(loadout.player());
            int x = x0 + 4 + (i % 2) * (cw + 4);
            addRenderableWidget(Button.builder(label(c.label, on), b -> {
                if (c.aircraft != null) aircraft = c.aircraft;
                set(c.aircraft != null ? loadout : loadout.withPlayer(c.player));
            }).bounds(x, y + (i / 2) * 20, cw, 18).build());
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
                page = (page + pages - 1) % pages;
                rebuildWidgets();
            }).bounds(x0 + W - 48, y0 + 4, 20, 14).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(x0 + W - 24, y0 + 4, 20, 14).build());
        }

        // огонь, отбой, настройки
        int by = y0 + h - 26;
        // пульт с привязанными пусковыми ставит им задачу, а не пускает сам (launcher.LauncherLinks)
        int linked = hand != null && minecraft.player != null ? LauncherLinks.of(minecraft.player.getItemInHand(hand)).size() : 0;
        Component fireLabel = linked > 0 ? Component.translatable("incoming.remote.assign", linked) : Component.translatable("incoming.remote.fire");
        addRenderableWidget(Button.builder(fireLabel.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                b -> fire(loadout.mode() == TargetMode.MAP ? MapTarget.waypoints(minecraft.level) : Waypoints.NONE)).bounds(x0 + 4, by, 128, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("incoming.remote.clear"), b -> {
            PacketDistributor.sendToServer(new C2S.Clear());
            onClose();
        }).bounds(x0 + 136, by, 70, 22).tooltip(Tooltip.create(Component.translatable("incoming.remote.clear.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("incoming.remote.settings"), b -> openConfig())
                .bounds(x0 + 210, by, 106, 22).build());
    }

    /** Чужие игроки, которых видит или недавно видела своя сторона ({@link MapPlayers#targets}): по другим пульт не бьёт. */
    private void collectPlayers() {
        shownPlayers = MapPlayers.targets(Minecraft.getInstance().level);
        for (String n : shownPlayers) choices.add(new Choice(Component.literal(n), n, null));
    }

    /** В режиме «игрок» список обновляется по ответам сервера, пока экран открыт. */
    @Override
    public void tick() {
        if (loadout.mode() != TargetMode.PLAYER) return;
        if (ticks++ % PLAYERS_PERIOD == 0) MapPlayers.request();
        if (!MapPlayers.targets(Minecraft.getInstance().level).equals(shownPlayers)) rebuildWidgets();
    }

    private void collectAircraft() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        List<SubLevelAccess> near = SubLevels.near(mc.level, mc.player.position(), 600);
        near.sort(Comparator.comparingDouble(s -> SubLevels.center(s).distanceTo(mc.player.position())));
        for (SubLevelAccess s : near) {
            int d = (int) SubLevels.center(s).distanceTo(mc.player.position());
            choices.add(new Choice(Component.translatable("incoming.remote.aircraft_entry", SubLevels.describe(s), d), "", s.getUniqueId()));
        }
    }

    /** Обычная или ядерная БЧ на крылатой ракете и B-2 (сервер проверит права и carrier_nukes). */
    private Button warheadButton(Loadout.Nuke n, int x, int y, int w) {
        Component text = n.onCarrier()
                ? Component.translatable("incoming.remote.warhead.nuclear").withStyle(ChatFormatting.GOLD)
                : Component.translatable("incoming.remote.warhead.conventional");
        return Button.builder(text, b -> set(loadout.withNuke(n.withOnCarrier(!n.onCarrier())))).bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.translatable("incoming.remote.warhead.tooltip"))).build();
    }

    /** Следующий пресет мощности (1 кт → 15 → 1 кт); сервер урежет до max_yield. */
    private static int nextYield(int kt) {
        for (Yield y : Yield.values()) {
            if (y.kt() > kt) return (int) y.kt();
        }
        return (int) Yield.values()[0].kt();
    }

    /** «15 кт (Хиросима)»; у мощности не из пресетов — без имени. */
    private static Component yieldLabel(int kt) {
        for (Yield y : Yield.values()) {
            if ((int) y.kt() == kt) return Component.translatable("incoming.remote.yield", kt, Component.translatable(y.key()));
        }
        return Component.translatable("incoming.remote.yield", kt, Component.empty());
    }

    /** Выбранное — жёлтым с чертой, без жирного: жирный шире, и выбор раздвигал бы ряд {@link #row}. */
    private static Component label(Component text, boolean selected) {
        return selected ? text.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE) : text.copy().withStyle(ChatFormatting.GRAY);
    }

    /** Кнопка ряда {@link #row} и все надписи, какие она показывает: ширина — по самой длинной, ряд не прыгает при переключении. */
    private record Cell(Button button, List<Component> texts) {
        Cell(Button button) {
            this(button, List.of(button.getMessage()));
        }
    }

    /**
     * Ряд кнопок во всю ширину окна: каждой — ширина её надписи с полями и поровну остатка, иначе длинная надпись
     * («Куда смотрю», «Подрыв: воздушный») прокручивалась бы в кнопке равной ширины. Не помещаются и так — поровну.
     */
    private void row(List<Cell> cells, int x0) {
        int n = cells.size(), free = W - 8 - 4 * (n - 1);
        int[] need = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) sum += need[i] = cells.get(i).texts().stream().mapToInt(font::width).max().orElse(0) + 8;
        int spare = free - sum, x = x0 + 4;
        for (int i = 0; i < n; i++) {
            // остаток делится нарастающим итогом: ряд кончается ровно у края окна
            int w = spare >= 0 ? need[i] + spare * (i + 1) / n - spare * i / n : free * (i + 1) / n - free * i / n;
            Button b = cells.get(i).button();
            b.setX(x);
            b.setWidth(w);
            addRenderableWidget(b);
            x += w + 4;
        }
    }

    private void set(Loadout l) {
        loadout = l;
        save();
        rebuildWidgets();
    }

    /** Ползунок меняет только своё число: сохранить, не перестраивая экран (фокус остаётся на ползунке). */
    private void commit(Loadout l) {
        loadout = l;
        save();
    }

    private void save() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (hand != null && p != null) {
            ItemStack stack = p.getItemInHand(hand);
            if (stack.getItem() instanceof DesignatorItem) {
                stack.set(ModDataComponents.LOADOUT.get(), loadout);
                PacketDistributor.sendToServer(new C2S.SetLoadout(hand, loadout));
            }
        } else {
            detached = loadout;
        }
    }

    /** Настройки пульта, с которыми пойдёт пуск. */
    Loadout loadout() {
        return loadout;
    }

    private void openMap() {
        Minecraft.getInstance().setScreen(new MapScreen(this));
    }

    /** На карте выбрано место: цель пульта — «На карте» (само место помнит {@link MapTarget}). */
    void aimAtPlace() {
        if (loadout.mode() == TargetMode.MAP) return;
        loadout = loadout.withMode(TargetMode.MAP);
        save();
    }

    /** На карте выбран игрок: цель пульта — он (удар по правилам сервера: {@code ServerActions.sighted}). */
    void aimAtPlayer(String name) {
        loadout = loadout.withMode(TargetMode.PLAYER).withPlayer(name);
        save();
    }

    /**
     * Огонь по цели пульта; с карты наведения — по выбранному там месту или игроку ({@link #aimAtPlace},
     * {@link #aimAtPlayer}) и по точкам маршрута {@code via}, если оружие по ним летает. Карта открывается и клавишей,
     * без показа пульта, поэтому здесь — {@code Minecraft.getInstance()}, а не поле экрана.
     */
    void fire(Waypoints via) {
        if (loadout.mode() == TargetMode.AIRCRAFT && aircraft == null) return;
        if (loadout.mode() == TargetMode.PLAYER && loadout.player().isEmpty()) return;
        if (!loadout.weapon().spec().route().waypoints()) via = Waypoints.NONE;
        Optional<C2S.AimHint> aim;
        if (loadout.mode() == TargetMode.MAP) {
            Optional<MapTarget.Place> place = MapTarget.get(Minecraft.getInstance().level);
            LocalPlayer p = Minecraft.getInstance().player;
            // маршрут длиннее дальности оружия: пуска нет — карта покажет, что не так
            if (place.isEmpty() || p == null || !via.isEmpty() && !via.within(loadout.weapon(), p.position(), new Vec3(place.get().x(), 0, place.get().z()))) {
                openMap();
                return;
            }
            // верх земли там по карте — оценка сервера, пока чанк места у него не готов
            aim = place.map(t -> C2S.AimHint.ground(t.x(), t.z(), TerrainTiles.height(Mth.floor(t.x()), Mth.floor(t.z()))));
        } else {
            aim = nukePoint();
        }
        C2S.Fire packet = new C2S.Fire(loadout, aim, Optional.ofNullable(aircraft), via);
        Minecraft.getInstance().setScreen(null);
        if (loadout.nuclear()) {
            NukeArming.toggle(() -> PacketDistributor.sendToServer(packet));
        } else {
            PacketDistributor.sendToServer(packet);
        }
    }

    /**
     * МБР бьёт по точке, выбранной в момент нажатия: за 3 с взведения игрок может отвернуться. Для режимов
     * «куда смотрю» и «вокруг меня» точка фиксируется сразу; игрок и аппарат — цели, их позицию возьмёт сервер.
     */
    private Optional<C2S.AimHint> nukePoint() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!loadout.weapon().spec().warhead().always() || p == null || Minecraft.getInstance().level == null) return Optional.empty();
        return switch (loadout.mode()) {
            case LOOK -> {
                var pick = TargetPicker.pick(Minecraft.getInstance().level, p, p.getEyePosition(), p.getLookAngle(), Designator.RANGE);
                yield pick == null ? Optional.empty() : Optional.of(new C2S.AimHint(C2S.AimHint.POINT, pick.point(), 0, Vec3.ZERO));
            }
            case AROUND_ME -> Optional.of(new C2S.AimHint(C2S.AimHint.POINT, p.position(), 0, Vec3.ZERO));
            default -> Optional.empty();
        };
    }

    private void openConfig() {
        ModList.get().getModContainerById(Incoming.MOD_ID).ifPresent(c -> minecraft.setScreen(new ConfigurationScreen(c, this)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y0 = (height - h) / 2;
        g.drawCenteredString(font, Component.translatable("incoming.remote.title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), width / 2, y0 + 7, 0xFFFFFFFF);
        lines(g, loadout.weapon().description().copy().withStyle(ChatFormatting.GRAY), y0 + 44, false);
        Component hint = switch (loadout.mode()) {
            case LOOK -> Component.translatable("incoming.remote.hint.look");
            case AROUND_ME -> Component.translatable("incoming.remote.hint.around_me");
            case PLAYER -> choices.isEmpty() ? Component.translatable("incoming.remote.hint.no_players") : movingHint("player");
            case AIRCRAFT -> choices.isEmpty() ? Component.translatable("incoming.remote.hint.no_aircraft") : movingHint("aircraft");
            case MAP -> {
                int points = loadout.weapon().spec().route().waypoints() ? MapTarget.route(minecraft.level).size() : 0;
                yield MapTarget.get(minecraft.level)
                        .<Component>map(p -> points > 0 ? Component.translatable("incoming.remote.hint.map_route", Mth.floor(p.x()), Mth.floor(p.z()), points)
                                : Component.translatable("incoming.remote.hint.map", Mth.floor(p.x()), Mth.floor(p.z())))
                        .orElse(Component.translatable("incoming.remote.hint.no_map"));
            }
        };
        // подсказка — над кнопками пуска: последняя строка внизу, лишние растут вверх
        lines(g, hint.copy().withStyle(ChatFormatting.DARK_GRAY), y0 + h - 28, true);
    }

    /** Текст по середине окна, перенесённый по его ширине: от {@code y} вниз или, если {@code up}, вверх до {@code y}. */
    private void lines(GuiGraphics g, Component text, int y, boolean up) {
        List<FormattedCharSequence> split = font.split(text, W - 8);
        int top = up ? y - split.size() * LINE : y;
        for (int i = 0; i < split.size(); i++) {
            g.drawCenteredString(font, split.get(i), width / 2, top + i * LINE, 0xFFFFFFFF);
        }
    }

    /** Подсказка для движущейся цели ({@code player}, {@code aircraft}): идёт ли выбранное оружие за ней ({@link WeaponSpec.Tracking}). */
    private Component movingHint(String what) {
        return loadout.weapon().spec().tracking() == WeaponSpec.Tracking.CAMERA
                ? Component.translatable("incoming.remote.hint." + what + ".camera", Component.keybind(Incoming.CAMERA_KEY))
                : Component.translatable("incoming.remote.hint." + what + ".point");
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x0 = (width - W) / 2, y0 = (height - h) / 2;
        g.fill(x0, y0, x0 + W, y0 + h, 0xE0101014);
        g.renderOutline(x0, y0, W, h, 0xFF8A1A1A);
        g.fill(x0 + 1, y0 + 18, x0 + W - 1, y0 + 19, 0xFF8A1A1A);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Ползунок с целым значением и подписью «Количество: 5». Значение уходит в пульт, когда его отпустили мышью
     * или сдвинули стрелками; пока тянут мышью — только подпись.
     */
    private static final class IntSlider extends AbstractSliderButton {
        private final String key;
        private final int min, max;
        private final IntConsumer onCommit;
        private int committed;

        IntSlider(int x, int y, int w, int h, String key, int min, int max, int value, IntConsumer onCommit) {
            super(x, y, w, h, Component.empty(), (double) (Mth.clamp(value, min, max) - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.onCommit = onCommit;
            this.committed = intValue();
            updateMessage();
        }

        int intValue() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, intValue()));
        }

        @Override
        protected void applyValue() {}

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            commit();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (handled) commit();
            return handled;
        }

        private void commit() {
            int v = intValue();
            if (v == committed) return;
            committed = v;
            onCommit.accept(v);
        }
    }
}
