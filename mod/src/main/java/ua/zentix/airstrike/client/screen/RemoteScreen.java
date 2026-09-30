package ua.zentix.airstrike.client.screen;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.aim.Designator;
import ua.zentix.airstrike.client.map.MapTarget;
import ua.zentix.airstrike.client.map.TerrainTiles;
import ua.zentix.airstrike.client.nuclear.NukeArming;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.TargetPicker;

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
    private static final int W = 320, H = 236;
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

    private record Choice(Component label, String player, @Nullable UUID aircraft) {}

    public RemoteScreen() {
        super(Component.translatable("airstrike.remote.title"));
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
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        int y = y0 + 22;

        // оружие
        List<WeaponType> menu = WeaponType.menu();
        int nw = menu.size();
        int bw = (W - 8 - 4 * (nw - 1)) / nw;
        for (WeaponType w : menu) {
            int x = x0 + 4 + menu.indexOf(w) * (bw + 4);
            Button b = Button.builder(label(w.displayName(), loadout.weapon() == w), btn -> set(loadout.withWeapon(w)))
                    .bounds(x, y, bw, 20).tooltip(Tooltip.create(w.description())).build();
            addRenderableWidget(b);
        }
        y += 38;

        // количество и разброс; у ядерной — мощность и подрыв
        int sw = (W - 12) / 2;
        Loadout.Nuke n = loadout.nuke();
        if (loadout.nuclear()) {
            // МБР: мощность и подрыв; ракета и B-2 с ядерной БЧ: переключатель БЧ, мощность, у ракеты — подрыв
            boolean carrier = loadout.weapon() != WeaponType.NUKE;
            boolean burst = loadout.weapon() != WeaponType.BUNKER;
            int cols = (carrier ? 1 : 0) + 1 + (burst ? 1 : 0);
            int cw = (W - 8 - 4 * (cols - 1)) / cols;
            int x = x0 + 4;
            if (carrier) {
                addRenderableWidget(warheadButton(n, x, y, cw));
                x += cw + 4;
            }
            addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.yield", n.yieldKt(), yieldName(n.yieldKt())),
                    b -> set(loadout.withNuke(new Loadout.Nuke(nextYield(n.yieldKt()), n.airBurst(), n.onCarrier())))).bounds(x, y, cw, 20).build());
            x += cw + 4;
            if (burst) {
                addRenderableWidget(Button.builder(Component.translatable(n.airBurst() ? "airstrike.remote.burst.air" : "airstrike.remote.burst.ground"),
                        b -> set(loadout.withNuke(new Loadout.Nuke(n.yieldKt(), !n.airBurst(), n.onCarrier())))).bounds(x, y, cw, 20)
                        .tooltip(Tooltip.create(Component.translatable("airstrike.remote.burst.tooltip"))).build());
            }
        } else if (Loadout.carriesNuke(loadout.weapon())) {
            int cw = (W - 16) / 3;
            addRenderableWidget(new IntSlider(x0 + 4, y, cw, 20, "airstrike.remote.count", 1, 30, loadout.count(), v -> commit(loadout.withCount(v))));
            addRenderableWidget(new IntSlider(x0 + 8 + cw, y, cw, 20, "airstrike.remote.spread", 0, 150, loadout.spread(), v -> commit(loadout.withSpread(v))));
            addRenderableWidget(warheadButton(n, x0 + 12 + 2 * cw, y, cw));
        } else {
            addRenderableWidget(new IntSlider(x0 + 4, y, sw, 20, "airstrike.remote.count", 1, 30, loadout.count(), v -> commit(loadout.withCount(v))));
            addRenderableWidget(new IntSlider(x0 + 8 + sw, y, sw, 20, "airstrike.remote.spread", 0, 150, loadout.spread(), v -> commit(loadout.withSpread(v))));
        }
        y += 26;

        // режим цели
        int modes = TargetMode.values().length;
        int mw = (W - 8 - 4 * (modes - 1)) / modes;
        for (TargetMode m : TargetMode.values()) {
            int x = x0 + 4 + m.ordinal() * (mw + 4);
            addRenderableWidget(Button.builder(label(m.displayName(), loadout.mode() == m), b -> {
                page = 0;
                set(loadout.withMode(m));
            }).bounds(x, y, mw, 20).build());
        }
        y += 24;

        // место на карте: открыть карту наведения
        if (loadout.mode() == TargetMode.MAP) {
            addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.open_map").withStyle(ChatFormatting.YELLOW), b -> openMap())
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
        int by = y0 + H - 26;
        addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.fire").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), b -> fire())
                .bounds(x0 + 4, by, 128, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.clear"), b -> {
            PacketDistributor.sendToServer(new C2S.Clear());
            onClose();
        }).bounds(x0 + 136, by, 70, 22).tooltip(Tooltip.create(Component.translatable("airstrike.remote.clear.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("airstrike.remote.settings"), b -> openConfig())
                .bounds(x0 + 210, by, 106, 22).build());
    }

    private void collectPlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return;
        List<String> names = new ArrayList<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String n = info.getProfile().getName();
            if (!n.equals(mc.player.getGameProfile().getName())) names.add(n);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String n : names) choices.add(new Choice(Component.literal(n), n, null));
    }

    private void collectAircraft() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        List<SubLevelAccess> near = SubLevels.near(mc.level, mc.player.position(), 600);
        near.sort(Comparator.comparingDouble(s -> SubLevels.center(s).distanceTo(mc.player.position())));
        for (SubLevelAccess s : near) {
            int d = (int) SubLevels.center(s).distanceTo(mc.player.position());
            choices.add(new Choice(Component.translatable("airstrike.remote.aircraft_entry", SubLevels.describe(s), d), "", s.getUniqueId()));
        }
    }

    /** Обычная или ядерная БЧ на крылатой ракете и B-2 (сервер проверит права и carrier_nukes). */
    private Button warheadButton(Loadout.Nuke n, int x, int y, int w) {
        Component text = n.onCarrier()
                ? Component.translatable("airstrike.remote.warhead.nuclear").withStyle(ChatFormatting.GOLD)
                : Component.translatable("airstrike.remote.warhead.conventional");
        return Button.builder(text, b -> set(loadout.withNuke(n.withOnCarrier(!n.onCarrier())))).bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.translatable("airstrike.remote.warhead.tooltip"))).build();
    }

    /** Следующий пресет мощности (1 кт → 15 → 100 → 300 → 1 Мт → 10 Мт → 1 кт); сервер урежет до max_yield. */
    private static int nextYield(int kt) {
        for (Yield y : Yield.values()) {
            if (y.kt() > kt) return (int) y.kt();
        }
        return (int) Yield.values()[0].kt();
    }

    private static Component yieldName(int kt) {
        for (Yield y : Yield.values()) {
            if ((int) y.kt() == kt) return Component.translatable(y.key());
        }
        return Component.empty();
    }

    private static Component label(Component text, boolean selected) {
        return selected ? text.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD, ChatFormatting.UNDERLINE) : text.copy().withStyle(ChatFormatting.GRAY);
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

    /**
     * Огонь с карты наведения: по выбранному там месту (режим цели пульта становится «На карте»). Карта открывается
     * и клавишей, без показа пульта, поэтому здесь и в {@link #fire} — {@code Minecraft.getInstance()}, а не поле экрана.
     */
    void fireOnMap() {
        if (loadout.mode() != TargetMode.MAP) {
            loadout = loadout.withMode(TargetMode.MAP);
            save();
        }
        fire();
    }

    private void fire() {
        if (loadout.mode() == TargetMode.AIRCRAFT && aircraft == null) return;
        if (loadout.mode() == TargetMode.PLAYER && loadout.player().isEmpty()) return;
        Optional<C2S.AimHint> aim;
        if (loadout.mode() == TargetMode.MAP) {
            Optional<MapTarget.Place> place = MapTarget.get(Minecraft.getInstance().level);
            if (place.isEmpty()) {
                openMap();
                return;
            }
            // верх земли там по карте — оценка сервера, пока чанк места у него не готов
            aim = place.map(p -> C2S.AimHint.ground(p.x(), p.z(), TerrainTiles.height(Mth.floor(p.x()), Mth.floor(p.z()))));
        } else {
            aim = nukePoint();
        }
        C2S.Fire packet = new C2S.Fire(loadout, aim, Optional.ofNullable(aircraft));
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
        if (loadout.weapon() != WeaponType.NUKE || p == null || Minecraft.getInstance().level == null) return Optional.empty();
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
        ModList.get().getModContainerById(Airstrike.MOD_ID).ifPresent(c -> minecraft.setScreen(new ConfigurationScreen(c, this)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        g.drawCenteredString(font, Component.translatable("airstrike.remote.title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), width / 2, y0 + 7, 0xFFFFFFFF);
        g.drawCenteredString(font, loadout.weapon().description().copy().withStyle(ChatFormatting.GRAY), width / 2, y0 + 46, 0xFFFFFFFF);
        Component hint = switch (loadout.mode()) {
            case LOOK -> Component.translatable("airstrike.remote.hint.look");
            case AROUND_ME -> Component.translatable("airstrike.remote.hint.around_me");
            case PLAYER -> choices.isEmpty() ? Component.translatable("airstrike.remote.hint.no_players") : Component.translatable("airstrike.remote.hint.player");
            case AIRCRAFT -> choices.isEmpty() ? Component.translatable("airstrike.remote.hint.no_aircraft") : Component.translatable("airstrike.remote.hint.aircraft");
            case MAP -> MapTarget.get(minecraft.level)
                    .<Component>map(p -> Component.translatable("airstrike.remote.hint.map", Mth.floor(p.x()), Mth.floor(p.z())))
                    .orElse(Component.translatable("airstrike.remote.hint.no_map"));
        };
        g.drawCenteredString(font, hint.copy().withStyle(ChatFormatting.DARK_GRAY), width / 2, y0 + H - 40, 0xFFFFFFFF);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        g.fill(x0, y0, x0 + W, y0 + H, 0xE0101014);
        g.renderOutline(x0, y0, W, H, 0xFF8A1A1A);
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
