package ua.zentix.airstrike.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.aim.Designator;

/**
 * Клавиши (Управление → Airstrike). «Пуск» по умолчанию — ЛКМ, но только пока смотришь в бинокль пульта:
 * в это время ванильная атака не работает, поэтому конфликта с ней нет. Клавиши по умолчанию — свободные в сборке
 * хоста (All of Create Aeronautics, 217 модов): на V там уже четыре действия (колчан Supplementaries, рюкзаки
 * Backpacked, пружины Create), и NeoForge запускает их все сразу — открывалось окно рюкзаков.
 */
public final class Keys {
    public static final String CATEGORY = "key.categories.airstrike";

    private static final IKeyConflictContext SCOPING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Designator.isScoping();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    };

    public static final KeyMapping FIRE = new KeyMapping("key.airstrike.fire", SCOPING, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);
    /** Камера снаряда: к ближайшему по времени, к следующему, к себе. N — рядом с прежней V. */
    public static final KeyMapping CAMERA = new KeyMapping(Airstrike.CAMERA_KEY, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);
    public static final KeyMapping MENU = new KeyMapping("key.airstrike.menu", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
    /** Карта наведения (та же, что «На карте» в пульте); по умолчанию «,» (M у Xaero в сборке). */
    public static final KeyMapping MAP = new KeyMapping("key.airstrike.map", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, CATEGORY);

    private Keys() {}
}
