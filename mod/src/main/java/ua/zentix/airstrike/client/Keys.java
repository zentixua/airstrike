package ua.zentix.airstrike.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import ua.zentix.airstrike.client.aim.Designator;

/**
 * Клавиши (Управление → Airstrike). «Пуск» по умолчанию — ЛКМ, но только пока смотришь в бинокль пульта:
 * в это время ванильная атака не работает, поэтому конфликта с ней нет.
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
    public static final KeyMapping MENU = new KeyMapping("key.airstrike.menu", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);

    private Keys() {}
}
