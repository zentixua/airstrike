package ua.zentix.airstrike.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.WeaponType;

/**
 * Тревога у тех, по кому бьют: сирена с двух сторон «города» и мигающая надпись над хотбаром.
 * Сирена у каждого своя с памятью 26 с (длина записи) — вторая поверх не включается, но надпись обновляется.
 * И строка хода залпа у того, кто его запустил.
 */
public final class Alerts {
    private static final int SIREN_MEMORY = 530;
    private static final int BANNER_TICKS = 100;

    private static long ticks;
    private static long lastSiren = -SIREN_MEMORY;
    private static int bannerLeft;
    private static Component banner = Component.empty();

    private static int salvoFired, salvoTotal, salvoWeapon, salvoLeft;

    private Alerts() {}

    public static void siren(S2C.Siren p) {
        boolean missile = p.kind() == S2C.Siren.MISSILE;
        banner = Component.translatable(missile ? "airstrike.alert.missile" : "airstrike.alert.air_raid").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        bannerLeft = BANNER_TICKS;
        if (ticks - lastSiren < SIREN_MEMORY) return;
        lastSiren = ticks;
        // две сирены по разные стороны от цели, чуть разного тона — как на улицах города
        Vec3 c = p.pos();
        Vec3 a = missile ? c.add(125, 15, -65) : c.add(115, 15, 80);
        Vec3 b = missile ? c.add(-95, 15, 115) : c.add(-130, 15, -70);
        ClientSounds.at(ModSounds.SIREN.get(), a, 16, 1.0f);
        ClientSounds.at(ModSounds.SIREN.get(), b, 16, 0.97f);
    }

    public static void salvo(S2C.SalvoStatus p) {
        salvoWeapon = p.weapon();
        salvoFired = p.fired();
        salvoTotal = p.total();
        salvoLeft = p.fired() >= p.total() ? 60 : 20 * 20;
    }

    public static void tick() {
        ticks++;
        if (bannerLeft > 0) bannerLeft--;
        if (salvoLeft > 0) salvoLeft--;
    }

    public static void reset() {
        bannerLeft = salvoLeft = 0;
        lastSiren = ticks - SIREN_MEMORY;
    }

    /** Надпись тревоги (мигает) и ход залпа — над хотбаром, где раньше была строка actionbar. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int y = g.guiHeight() - 72;
        if (bannerLeft > 0) {
            float t = ticks + delta.getGameTimeDeltaPartialTick(false);
            float pulse = 0.55f + 0.45f * Mth.sin(t * 0.45f);
            int alpha = (int) (255 * Math.min(1, bannerLeft / 20f) * pulse);
            if (alpha > 8) {
                g.pose().pushPose();
                g.pose().translate(cx, y, 0);
                g.pose().scale(1.5f, 1.5f, 1);
                g.drawString(font, banner, -font.width(banner) / 2, 0, (alpha << 24) | 0xFF3030);
                g.pose().popPose();
            }
            y -= 16;
        }
        if (salvoLeft > 0 && salvoTotal > 0) {
            Component line = salvoFired >= salvoTotal
                    ? Component.translatable("airstrike.salvo.done").withStyle(ChatFormatting.GRAY)
                    : Component.translatable("airstrike.hud.salvo", weaponName(), salvoFired, salvoTotal).withStyle(ChatFormatting.YELLOW);
            g.drawString(font, line, cx - font.width(line) / 2, y, 0xFFFFFFFF);
        }
    }

    private static Component weaponName() {
        WeaponType[] w = WeaponType.values();
        return salvoWeapon >= 0 && salvoWeapon < w.length ? w[salvoWeapon].displayName() : Component.empty();
    }
}
