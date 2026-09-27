package ua.zentix.airstrike.client.nuclear;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import ua.zentix.airstrike.item.GeigerCounterItem;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.radiation.GeigerFormat;
import ua.zentix.airstrike.registry.ModSounds;

import java.util.Locale;

/**
 * Счётчик Гейгера в руке: щелчки с пуассоновой частотой по мощности дозы (фон 0.15 мкЗв/ч — щелчок раз в
 * пару секунд, у эпицентра — сплошной треск) и показания над хотбаром: мощность дозы и накопленная доза.
 */
public final class Geiger {
    /** Щелчков в секунду на 1 мкЗв/ч (у бытовых счётчиков порядка единиц). */
    private static final double CPS_PER_USV = 2.5;
    /** Выше — счётчик захлёбывается: треск не частит дальше. */
    private static final double MAX_CPS = 400;
    private static final RandomSource RANDOM = RandomSource.create();

    private Geiger() {}

    private static boolean holding(LocalPlayer p) {
        return p.getMainHandItem().getItem() instanceof GeigerCounterItem || p.getOffhandItem().getItem() instanceof GeigerCounterItem;
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !holding(p)) return;
        S2C.Radiation r = ClientNuclear.radiationState();
        double usv = GeigerFormat.microSievertsPerHour(r == null ? 0 : r.rate());
        double lambda = Math.min(MAX_CPS, usv * CPS_PER_USV) / 20;
        int clicks = poisson(lambda);
        // больше трёх щелчков за тик ухо уже не различает
        for (int i = 0; i < Math.min(3, clicks); i++) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.GEIGER_CLICK.get(), 0.9f + RANDOM.nextFloat() * 0.2f, 0.5f));
        }
    }

    private static int poisson(double lambda) {
        if (lambda <= 0) return 0;
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= RANDOM.nextDouble();
        } while (p > l && k < 50);
        return k - 1;
    }

    /** Показания над хотбаром справа — только со счётчиком в руке. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui || !holding(p)) return;
        S2C.Radiation r = ClientNuclear.radiationState();
        double rate = r == null ? 0 : r.rate();
        double usv = GeigerFormat.microSievertsPerHour(rate);
        ChatFormatting color = usv < 1 ? ChatFormatting.GREEN : usv < 100 ? ChatFormatting.YELLOW : usv < 10_000 ? ChatFormatting.GOLD : ChatFormatting.RED;
        Component line1 = Component.translatable("airstrike.geiger.rate", GeigerFormat.rate(rate)).withStyle(color);
        Component line2 = Component.translatable("airstrike.geiger.dose", String.format(Locale.ROOT, "%.2f", r == null ? 0 : r.doseGy()))
                .withStyle(ChatFormatting.GRAY);
        Font font = mc.font;
        int x = g.guiWidth() / 2 + 98, y = g.guiHeight() - 30;
        g.drawString(font, line1, x, y, 0xFFFFFFFF);
        g.drawString(font, line2, x, y + 10, 0xFFFFFFFF);
    }
}
