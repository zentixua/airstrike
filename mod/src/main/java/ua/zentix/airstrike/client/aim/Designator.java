package ua.zentix.airstrike.client.aim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.nuclear.NukeArming;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.util.Locale;
import java.util.Optional;

/**
 * Бинокль-целеуказатель: пока держишь ПКМ с пультом — зум, прицельная марка, дальность и что под прицелом
 * (блок, моб, игрок, аппарат Create — в рамке), ЛКМ — пуск, колесо — оружие. Что видишь, туда и летит:
 * клиент отправляет свою цель, сервер находит её у себя и проверяет.
 */
public final class Designator {
    /** Дальность прицела на клиенте; сервер всё равно ограничит своей (aim_range). */
    public static final double RANGE = 400;

    @Nullable
    private static TargetPicker.Pick pick;
    private static Object lastLocked;

    private Designator() {}

    @Nullable
    public static InteractionHand scopingHand() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !p.isUsingItem() || !(p.getUseItem().getItem() instanceof DesignatorItem)) return null;
        return p.getUsedItemHand();
    }

    public static boolean isScoping() {
        return scopingHand() != null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !isScoping()) {
            reset();
            return;
        }
        pick = TargetPicker.pick(mc.level, p, p.getEyePosition(), p.getLookAngle(), RANGE);
        // захват цели: короткий сигнал, когда под прицелом появилась сущность или аппарат
        Object locked = pick == null ? null : pick.entity() != null ? pick.entity() : pick.kind() == TargetPicker.Kind.AIRCRAFT ? pick.label().getString() : null;
        if (locked != null && !locked.equals(lastLocked)) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.DESIGNATOR_LOCK.get(), 1.0f, 0.6f));
        }
        lastLocked = locked;
    }

    /** Забыть цель: держит сущность текущего мира, после выхода из него её держать нельзя. */
    public static void reset() {
        pick = null;
        lastLocked = null;
    }

    private static Loadout loadout() {
        InteractionHand hand = scopingHand();
        LocalPlayer p = Minecraft.getInstance().player;
        return hand == null || p == null ? Loadout.DEFAULT : DesignatorItem.loadout(p.getItemInHand(hand));
    }

    /** ЛКМ в бинокле: пуск по тому, что под прицелом. */
    public static void fire() {
        TargetPicker.Pick pk = pick;
        if (pk == null || !isScoping()) return;
        C2S.AimHint hint;
        if (pk.entity() != null) {
            hint = new C2S.AimHint(C2S.AimHint.ENTITY, pk.point(), pk.entity().getId(), Vec3.ZERO);
        } else if (pk.target() instanceof Target.OfSubLevel s) {
            hint = new C2S.AimHint(C2S.AimHint.AIRCRAFT, pk.point(), 0, s.plotPos());
        } else {
            hint = new C2S.AimHint(C2S.AimHint.POINT, pk.point(), 0, Vec3.ZERO);
        }
        Loadout l = loadout().withMode(TargetMode.LOOK);
        C2S.Fire packet = new C2S.Fire(l, Optional.of(hint), Optional.empty());
        if (l.weapon() == WeaponType.NUKE) {
            NukeArming.toggle(() -> PacketDistributor.sendToServer(packet));
        } else {
            PacketDistributor.sendToServer(packet);
        }
    }

    /** Колесо в бинокле: следующее/предыдущее оружие (сохраняется в пульте). */
    public static void scroll(double delta) {
        InteractionHand hand = scopingHand();
        LocalPlayer p = Minecraft.getInstance().player;
        if (hand == null || p == null) return;
        ItemStack stack = p.getItemInHand(hand);
        Loadout l = DesignatorItem.loadout(stack);
        Loadout next = l.withWeapon(delta < 0 ? l.weapon().next() : l.weapon().previous());
        stack.set(ModDataComponents.LOADOUT.get(), next);
        PacketDistributor.sendToServer(new C2S.SetLoadout(hand, next));
        p.displayClientMessage(Component.translatable("airstrike.weapon.selected", next.weapon().displayName()).withStyle(ChatFormatting.GOLD), true);
    }

    // ---------------------------------------------------------------- зум и чувствительность

    public static void fov(ComputeFovModifierEvent e) {
        if (isScoping()) e.setNewFovModifier((float) (1.0 / AirstrikeConfig.CLIENT.zoom.get()));
    }

    /** С зумом мышь медленнее во столько же раз, как у подзорной трубы. */
    public static void turn(CalculatePlayerTurnEvent e) {
        if (!isScoping()) return;
        double s = e.getMouseSensitivity() * 0.6 + 0.2;
        double scaled = s / Math.cbrt(AirstrikeConfig.CLIENT.zoom.get());
        e.setMouseSensitivity(Math.max(0, (scaled - 0.2) / 0.6));
    }

    /** В бинокль руку с пультом не видно. */
    public static void hideHand(RenderHandEvent e) {
        if (isScoping()) e.setCanceled(true);
    }

    // ---------------------------------------------------------------- рамка вокруг цели в мире

    public static void renderWorld(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || pick == null || !isScoping()) return;
        AABB box = null;
        if (pick.entity() != null) {
            box = pick.entity().getBoundingBox().inflate(0.25);
        } else if (pick.kind() == TargetPicker.Kind.AIRCRAFT) {
            SubLevelAccess sub = SubLevels.containing(Minecraft.getInstance().level, ((Target.OfSubLevel) pick.target()).plotPos());
            if (sub != null) box = sub.boundingBox().toMojang().inflate(0.5);
        }
        if (box == null) return;
        Vec3 cam = e.getCamera().getPosition();
        PoseStack pose = e.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, box, 1.0f, 0.25f, 0.2f, 1.0f);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    // ---------------------------------------------------------------- прицельная марка

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!isScoping() || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        Font font = mc.font;
        int r = Math.min(w, h) / 2 - 10;

        // затемнение по краям — «окуляр»
        g.fill(0, 0, w, cy - r, 0xB0000000);
        g.fill(0, cy + r, w, h, 0xB0000000);
        g.fill(0, cy - r, cx - r, cy + r, 0xB0000000);
        g.fill(cx + r, cy - r, w, cy + r, 0xB0000000);

        int red = pick != null && (pick.entity() != null || pick.kind() == TargetPicker.Kind.AIRCRAFT) ? 0xFFFF4030 : 0xFFE8E8E8;
        // перекрестие с разрывом и засечки дальномера
        g.fill(cx - 30, cy, cx - 4, cy + 1, red);
        g.fill(cx + 4, cy, cx + 30, cy + 1, red);
        g.fill(cx, cy - 30, cx + 1, cy - 4, red);
        g.fill(cx, cy + 4, cx + 1, cy + 30, red);
        for (int i = 1; i <= 4; i++) g.fill(cx - 3, cy + 6 * i + 4, cx + 4, cy + 6 * i + 5, 0x80E8E8E8);
        g.fill(cx, cy, cx + 1, cy + 1, red);

        Loadout l = loadout();
        LocalPlayer p = mc.player;
        String dist = pick == null || p == null ? "—" : String.format(Locale.ROOT, "%.0f", pick.point().distanceTo(p.getEyePosition()));
        Component what = pick == null ? Component.translatable("airstrike.target_not_found") : pick.label();
        Component top = Component.translatable("airstrike.hud.target", what, dist).withStyle(ChatFormatting.WHITE);
        g.drawString(font, top, cx - font.width(top) / 2, cy + 40, 0xFFFFFFFF);
        Component weapon = l.count() > 1 || l.spread() > 0
                ? Component.translatable("airstrike.hud.loadout.salvo", l.weapon().displayName(), l.count(), l.spread())
                : Component.translatable("airstrike.hud.loadout", l.weapon().displayName());
        g.drawString(font, weapon.copy().withStyle(ChatFormatting.GOLD), cx - font.width(weapon) / 2, cy + 52, 0xFFFFFFFF);
        Component hint = Component.translatable("airstrike.hud.scope_hint").withStyle(ChatFormatting.GRAY);
        g.drawString(font, hint, cx - font.width(hint) / 2, cy - r + 6, 0xFFFFFFFF);
    }
}
