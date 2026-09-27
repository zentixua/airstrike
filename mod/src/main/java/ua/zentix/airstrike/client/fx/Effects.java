package ua.zentix.airstrike.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.List;

/** Клиентские таймлайны эффектов (взрывы, выброс газов, обрушение): тикают вместе с миром, на паузе стоят. */
public final class Effects {
    private Effects() {}

    /** Эффект; tick(t) вызывается с t = 0, 1, 2 …, false — закончился. */
    public interface Effect {
        boolean tick(ClientLevel level, int t);
    }

    private static final class Running {
        final Effect effect;
        int t;

        Running(Effect effect) {
            this.effect = effect;
        }
    }

    private static final List<Running> RUNNING = new ArrayList<>();

    public static void add(Effect effect) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Running r = new Running(effect);
        // нулевой тик — сразу, в момент события (вспышка не ждёт)
        if (run(level, r)) RUNNING.add(r);
    }

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        RUNNING.removeIf(r -> !run(level, r));
    }

    private static boolean run(ClientLevel level, Running r) {
        try {
            return r.effect.tick(level, r.t++);
        } catch (RuntimeException e) {
            Airstrike.LOG.error("Эффект упал с ошибкой и убран", e);
            return false;
        }
    }

    public static void clear() {
        RUNNING.clear();
    }
}
