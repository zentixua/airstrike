package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.Detonation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Всё ядерное, что знает клиент: подрывы этого измерения (из них по модели выводятся вспышка, шар, гриб,
 * волна, звук, дождь) и летящие МБР. Сервер присылает по одному пакету на событие — дальше клиент сам.
 */
public final class ClientNuclear {
    /** Подрыв, который пришёл «вживую» (не при входе в мир): для него вспышка, звук и тряска. */
    public static final class Active {
        public final Detonation d;
        public final boolean live;
        public final CloudPuffs puffs;
        final NukeSounds.Schedule sounds;

        Active(Detonation d, boolean live) {
            this.d = d;
            this.live = live;
            this.puffs = new CloudPuffs(d);
            this.sounds = new NukeSounds.Schedule(d, live);
        }
    }

    private static final Map<Integer, Active> DETONATIONS = new LinkedHashMap<>();
    private static final Map<Integer, S2C.NukeWarning> WARNINGS = new LinkedHashMap<>();
    @Nullable
    private static S2C.Radiation radiation;

    private ClientNuclear() {}

    // ---------------------------------------------------------------- пакеты

    public static void warning(S2C.NukeWarning w) {
        boolean fresh = !WARNINGS.containsKey(w.strikeId());
        WARNINGS.put(w.strikeId(), w);
        if (fresh) NukeSounds.warning(w);
    }

    public static void detonation(S2C.NukeDetonation p) {
        Detonation d = p.detonation();
        WARNINGS.values().removeIf(w -> w.target().distanceToSqr(new Vec3(d.burst().x, w.target().y, d.burst().z)) < 4 && Math.abs(w.detonateTime() - d.gameTime()) < 40);
        if (DETONATIONS.containsKey(d.id())) return;
        Active a = new Active(d, true);
        DETONATIONS.put(d.id(), a);
        NukeFlash.detonation(d);
    }

    /** Вход в мир или смена измерения: всё, что уже есть, без вспышки и удара (они уже прошли). */
    public static void sync(S2C.NukeSync p) {
        DETONATIONS.clear();
        WARNINGS.clear();
        for (Detonation d : p.detonations()) DETONATIONS.put(d.id(), new Active(d, false));
        for (S2C.NukeWarning w : p.warnings()) WARNINGS.put(w.strikeId(), w);
    }

    public static void radiation(S2C.Radiation p) {
        radiation = p;
    }

    public static void reset() {
        DETONATIONS.clear();
        WARNINGS.clear();
        radiation = null;
        NukeFlash.reset();
        NukeSounds.reset();
        Deafness.reset();
    }

    // ---------------------------------------------------------------- тик

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        for (Iterator<Active> it = DETONATIONS.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            a.sounds.tick(now);
            if (expired(a.d, now)) it.remove();
        }
        WARNINGS.values().removeIf(w -> now > w.detonateTime() + 100);
        NukeSounds.tick(level, now);
        NukeFlash.tick();
        Deafness.tick();
        Geiger.tick();
    }

    /** Подрыв исчезает с клиента, когда гриб рассеялся и чёрный дождь кончился. */
    private static boolean expired(Detonation d, long now) {
        double cloudTicks = CloudPuffs.lifeSeconds(d) * 20 * d.scale();
        double rainTicks = d.hasFallout() ? (Detonation.BLACK_RAIN_HOURS + 6) * 1000 : 0;
        return now - d.gameTime() > Math.max(cloudTicks, rainTicks);
    }

    // ---------------------------------------------------------------- для рисования и звука

    public static List<Active> detonations() {
        return DETONATIONS.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(DETONATIONS.values()));
    }

    public static boolean isEmpty() {
        return DETONATIONS.isEmpty() && WARNINGS.isEmpty();
    }

    public static Iterable<S2C.NukeWarning> warnings() {
        return WARNINGS.values();
    }

    @Nullable
    public static S2C.Radiation radiationState() {
        return radiation;
    }

    /** Тики после подрыва (с долей кадра). */
    public static double ticksSince(Detonation d, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime() - d.gameTime() + partialTick;
    }

    /** Секунды модели после подрыва (с учётом масштаба мира). */
    public static double seconds(Detonation d, float partialTick) {
        return ticksSince(d, partialTick) / (20.0 * d.scale());
    }
}
