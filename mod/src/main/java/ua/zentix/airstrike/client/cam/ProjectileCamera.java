package ua.zentix.airstrike.client.cam;

import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.hud.StrikesHud;
import ua.zentix.airstrike.client.nuclear.NukeView;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Камера снаряда: картинка с его стабилизированной камеры (как у барражирующих боеприпасов). Клавиша камеры —
 * к ближайшему по времени снаряду, ещё раз — к следующему, после последнего — обратно к себе; Shift — выход.
 * Смотреть можно мышью (камера на подвесе), ЛКМ — перенацелить снаряд на то, что в перекрестии.
 * Пока снаряд вне зоны связи (далеко за прорисовкой, вне загруженного мира) — «НЕТ СВЯЗИ» и время до удара;
 * удар — «СИГНАЛ ПОТЕРЯН» и помехи, затем следующий снаряд залпа или возврат к себе.
 * F5 — вид со стороны (камера кружит вокруг снаряда). Игрок в это время стоит на месте: ходьба и действия отключены.
 */
public final class ProjectileCamera {
    /** Сколько тиков держатся помехи после удара. */
    private static final int LOST_TICKS = 30;
    private static final RandomSource NOISE = RandomSource.create();

    @Nullable
    private static UUID following;
    private static boolean active;
    private static int lostTicks;
    private static float savedYaw, savedPitch;
    private static CameraType savedCamera = CameraType.FIRST_PERSON;
    private static boolean shiftWasDown;
    /** Куда перенацелили (для метки), тиков показа. */
    @Nullable
    private static Vec3 newTarget;
    private static int newTargetTicks;

    private ProjectileCamera() {}

    public static boolean isActive() {
        return active;
    }

    /** Камера сейчас смотрит глазами снаряда (а не ждёт связи). */
    public static boolean isViewing() {
        Minecraft mc = Minecraft.getInstance();
        return active && mc.getCameraEntity() instanceof StrikeProjectile;
    }

    /** Клавиша камеры: к ближайшему снаряду, к следующему, после последнего — к себе. */
    public static void cycle() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) {
            if (active) exit();
            else player.displayClientMessage(Component.translatable("airstrike.camera.none").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int idx = -1;
        if (active && following != null) {
            for (int i = 0; i < flights.size(); i++) {
                if (flights.get(i).id.equals(following)) idx = i;
            }
            if (idx == flights.size() - 1) {
                exit();
                return;
            }
        }
        follow(flights.get(idx + 1));
    }

    private static void follow(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!active) {
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        active = true;
        following = f.id;
        lostTicks = 0;
        newTarget = null;
        StrikeProjectile p = f.entity();
        // взгляд — по курсу снаряда, дальше мышь водит камеру на подвесе
        if (p != null) {
            player.setYRot(p.getYRot());
            player.setXRot(Mth.clamp(p.getXRot() + 8, -89, 89));
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.4f));
    }

    public static void exit() {
        Minecraft mc = Minecraft.getInstance();
        if (!active) return;
        active = false;
        following = null;
        lostTicks = 0;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
        }
        mc.options.setCameraType(savedCamera);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!active) return;
        if (mc.player == null || mc.level == null) {
            active = false;
            following = null;
            return;
        }
        boolean shift = mc.options.keyShift.isDown();
        if (shift && !shiftWasDown) {
            shiftWasDown = true;
            exit();
            return;
        }
        shiftWasDown = shift;
        if (newTargetTicks > 0) newTargetTicks--;

        ClientFlights.Tracked f = following == null ? null : ClientFlights.find(following);
        if (f == null) {
            // долетел: помехи, потом следующий снаряд залпа или к себе
            if (lostTicks == 0) mc.setCameraEntity(mc.player);
            if (++lostTicks >= LOST_TICKS) {
                List<ClientFlights.Tracked> rest = ClientFlights.all();
                if (rest.isEmpty()) exit();
                else follow(rest.get(0));
            }
            return;
        }
        StrikeProjectile p = f.entity();
        if (p != null && p.isActive()) {
            if (mc.getCameraEntity() != p) mc.setCameraEntity(p);
        } else if (mc.getCameraEntity() != mc.player) {
            mc.setCameraEntity(mc.player);
        }
    }

    /** Камера на подвесе: направление — взгляд игрока, крен — половина крена снаряда. */
    public static void angles(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || !(mc.getCameraEntity() instanceof StrikeProjectile p) || mc.player == null) return;
        float pt = (float) e.getPartialTick();
        e.setYaw(mc.player.getViewYRot(pt));
        e.setPitch(mc.player.getViewXRot(pt));
        e.setRoll(p.roll() * 0.5f);
    }

    /** Узкий угол зрения — как у камеры с зумом. */
    public static void fov(ViewportEvent.ComputeFov e) {
        if (isViewing()) e.setFOV(e.getFOV() * 0.75);
    }

    /** Игрок стоит, пока смотрит в камеру. */
    public static void movement(MovementInputUpdateEvent e) {
        if (!active) return;
        var in = e.getInput();
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
    }

    public static void hideHand(RenderHandEvent e) {
        if (active) e.setCanceled(true);
    }

    /** ЛКМ в камере — новая цель; прочие действия руками отключены. */
    public static void click(InputEvent.InteractionKeyMappingTriggered e) {
        if (!active) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (e.isAttack()) retarget();
    }

    private static void retarget() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p) || mc.level == null || mc.player == null) return;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 look = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        TargetPicker.Pick pick = TargetPicker.pick(mc.level, p, eye, look, 1024);
        if (pick == null) {
            mc.player.displayClientMessage(Component.translatable("airstrike.target_not_found").withStyle(ChatFormatting.RED), true);
            return;
        }
        C2S.AimHint hint;
        if (pick.entity() != null) {
            hint = new C2S.AimHint(C2S.AimHint.ENTITY, pick.point(), pick.entity().getId(), Vec3.ZERO);
        } else if (pick.target() instanceof Target.OfSubLevel s) {
            hint = new C2S.AimHint(C2S.AimHint.AIRCRAFT, pick.point(), 0, s.plotPos());
        } else {
            hint = new C2S.AimHint(C2S.AimHint.POINT, pick.point(), 0, Vec3.ZERO);
        }
        PacketDistributor.sendToServer(new C2S.Retarget(p.getUUID(), hint));
        newTarget = pick.point();
        newTargetTicks = 40;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(ua.zentix.airstrike.registry.ModSounds.DESIGNATOR_LOCK.get(), 1.0f, 0.8f));
    }

    public static void reset() {
        if (active) exit();
        active = false;
        following = null;
    }

    // ---------------------------------------------------------------- картинка

    /** Видеоканал: рамка, перекрестие, телеметрия, шум; нет связи — «НЕТ СВЯЗИ», удар — помехи на весь экран. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null) return;
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        Font font = mc.font;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        ClientFlights.Tracked f = following == null ? null : ClientFlights.find(following);

        if (f == null) {
            staticNoise(g, w, h, 1.0f);
            Component lost = Component.translatable("airstrike.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            big(g, font, lost, cx, cy - 6, 2f);
            return;
        }
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p)) {
            g.fill(0, 0, w, h, 0xE0101010);
            staticNoise(g, w, h, 0.35f);
            Component nolink = Component.translatable("airstrike.camera.no_link").withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD);
            big(g, font, nolink, cx, cy - 20, 2f);
            Component eta = Component.translatable("airstrike.camera.eta", f.weapon().displayName(), f.number, StrikesHud.clock(f.etaSeconds(pt)))
                    .withStyle(ChatFormatting.YELLOW);
            g.drawString(font, eta, cx - font.width(eta) / 2, cy + 8, 0xFFFFFFFF);
            hint(g, font, w, h);
            return;
        }

        int c = 0xE0E8E8E8;
        int m = 16, l = 18;
        // F5 — вид со стороны: без «видеоканала», только телеметрия и прицел
        boolean feed = mc.options.getCameraType().isFirstPerson();
        if (feed) {
            // лёгкие помехи и строчная развёртка, как у аналогового видеоканала
            staticNoise(g, w, h, 0.05f);
            for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x14000000);
        }
        // уголки кадра
        g.fill(m, m, m + l, m + 1, c);
        g.fill(m, m, m + 1, m + l, c);
        g.fill(w - m - l, m, w - m, m + 1, c);
        g.fill(w - m - 1, m, w - m, m + l, c);
        g.fill(m, h - m - 1, m + l, h - m, c);
        g.fill(m, h - m - l, m + 1, h - m, c);
        g.fill(w - m - l, h - m - 1, w - m, h - m, c);
        g.fill(w - m - 1, h - m - l, w - m, h - m, c);
        // перекрестие с разрывом
        g.fill(cx - 22, cy, cx - 5, cy + 1, c);
        g.fill(cx + 5, cy, cx + 22, cy + 1, c);
        g.fill(cx, cy - 22, cx + 1, cy - 5, c);
        g.fill(cx, cy + 5, cx + 1, cy + 22, c);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFF4030);

        // метка цели
        target(g, f.target(), w, h, 0xFFFF3030);
        if (newTarget != null && newTargetTicks > 0 && (newTargetTicks / 4) % 2 == 0) target(g, newTarget, w, h, 0xFF30FF60);

        // телеметрия
        double alt = p.getY() - ua.zentix.airstrike.entity.StrikeProjectile.surfaceY(mc.level, p.getX(), p.getZ());
        double kmh = p.speed() * 20 * 3.6;
        double dist = p.position().distanceTo(f.target());
        boolean rec = (mc.level.getGameTime() / 10) % 2 == 0;
        String left1 = (rec ? "● " : "  ") + "REC  " + f.weapon().displayName().getString().toUpperCase(Locale.ROOT) + " №" + f.number;
        g.drawString(font, left1, m + 6, m + 6, rec ? 0xFFFF4040 : 0xFFE8E8E8);
        g.drawString(font, Component.translatable("airstrike.phase." + f.phase().getSerializedName()).getString().toUpperCase(Locale.ROOT),
                m + 6, m + 18, f.phase() == FlightPhase.TERMINAL ? 0xFFFF4040 : 0xFFE8E8E8);
        Component[] right = {
                Component.translatable("airstrike.camera.alt", String.format(Locale.ROOT, "%.0f", alt)),
                Component.translatable("airstrike.camera.speed", String.format(Locale.ROOT, "%.0f", kmh)),
                Component.translatable("airstrike.camera.range", String.format(Locale.ROOT, "%.0f", dist)),
                Component.literal("T-" + StrikesHud.clock(f.etaSeconds(pt)))
        };
        for (int i = 0; i < right.length; i++) {
            g.drawString(font, right[i], w - m - 6 - font.width(right[i]), m + 6 + 12 * i, i == 3 ? 0xFFFFC040 : 0xFFE8E8E8);
        }
        if (f.nuclear()) g.drawString(font, "☢", cx - font.width("☢") / 2, m + 6, 0xFFFFD020);
        hint(g, font, w, h);
    }

    private static void hint(GuiGraphics g, Font font, int w, int h) {
        Component hint = Component.translatable("airstrike.camera.hint", ua.zentix.airstrike.client.Keys.CAMERA.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.GRAY);
        g.drawString(font, hint, w / 2 - font.width(hint) / 2, h - 34, 0xFFFFFFFF);
    }

    private static void target(GuiGraphics g, Vec3 at, int w, int h, int color) {
        float[] s = NukeView.project(at);
        if (s == null) return;
        int x = (int) (s[0] * w), y = (int) (s[1] * h);
        if (x < 0 || x > w || y < 0 || y > h) return;
        int r = 7;
        g.fill(x - r, y - r, x + r + 1, y - r + 1, color);
        g.fill(x - r, y + r, x + r + 1, y + r + 1, color);
        g.fill(x - r, y - r, x - r + 1, y + r + 1, color);
        g.fill(x + r, y - r, x + r + 1, y + r + 1, color);
    }

    private static void big(GuiGraphics g, Font font, Component text, int cx, int y, float scale) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, 0xFFFFFFFF);
        g.pose().popPose();
    }

    /** Помехи: случайные серые штрихи, {@code amount} — доля экрана. */
    private static void staticNoise(GuiGraphics g, int w, int h, float amount) {
        int n = (int) (w * h / 40 * amount);
        for (int i = 0; i < n; i++) {
            int x = NOISE.nextInt(w), y = NOISE.nextInt(h);
            int v = NOISE.nextInt(200) + 55;
            int len = 1 + NOISE.nextInt(6);
            g.fill(x, y, x + len, y + 1, 0xFF000000 | v << 16 | v << 8 | v);
        }
    }
}
