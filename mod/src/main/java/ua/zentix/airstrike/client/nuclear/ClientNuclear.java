package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearEvents;

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
    /**
     * Подрыв на клиенте. «Вживую» (не при входе в мир) — со вспышкой, звуком и тряской. Время картинки считают свои
     * тики клиента от прихода пакета, а не игровые часы: те прыгают назад, когда сервер догоняет отставание,
     * а шар и двойная вспышка длятся доли секунды — их нельзя ни проскочить, ни повторить.
     */
    public static final class Active {
        public final Detonation d;
        public final boolean live;
        public final CloudPuffs puffs;
        /** Сколько тиков прошло до прихода пакета (у пришедших при входе в мир — с самого подрыва). */
        private final long before;
        private long age;
        final NukeSounds.Schedule sounds;

        Active(Detonation d, boolean live, long clientNow) {
            this.d = d;
            this.live = live;
            this.before = live ? 0 : Math.max(0, clientNow - d.gameTime());
            this.puffs = new CloudPuffs(d);
            this.sounds = new NukeSounds.Schedule(this);
        }

        /** Тики после подрыва (с долей кадра). */
        public double ticks(float partialTick) {
            return before + age + partialTick;
        }

        /** Секунды модели после подрыва (с учётом масштаба мира). */
        public double seconds(float partialTick) {
            return ticks(partialTick) / (20.0 * d.scale());
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
        ClientLevel level = Minecraft.getInstance().level;
        if (DETONATIONS.containsKey(d.id()) || level == null) return;
        Active a = new Active(d, true, level.getGameTime());
        DETONATIONS.put(d.id(), a);
        NukeFlash.detonation(a);
    }

    /** Вход в мир или смена измерения: всё, что уже есть, без вспышки и удара (они уже прошли). */
    public static void sync(S2C.NukeSync p) {
        DETONATIONS.clear();
        WARNINGS.clear();
        ClientLevel level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        for (Detonation d : p.detonations()) DETONATIONS.put(d.id(), new Active(d, false, now));
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
        NukeDust.reset();
    }

    // ---------------------------------------------------------------- тик

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        for (Iterator<Active> it = DETONATIONS.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            a.age++;
            a.sounds.tick();
            if (a.ticks(0) > lifeTicks(a.d)) it.remove();
        }
        WARNINGS.values().removeIf(w -> now > w.detonateTime() + 100);
        NukeSounds.tick(level, now);
        NukeFlash.tick();
        Deafness.tick();
        NukeDust.tick();
        Geiger.tick();
    }

    /**
     * Подрыв исчезает с клиента, когда гриб рассеялся, а подрыв с осадками — когда его забудет и сервер
     * (далеко по ветру осадки и чёрный дождь приходят через десятки игровых часов).
     */
    private static double lifeTicks(Detonation d) {
        double cloudTicks = CloudPuffs.lifeSeconds(d) * 20 * d.scale();
        return d.hasFallout() ? Math.max(cloudTicks, NuclearEvents.FORGET_AFTER) : cloudTicks;
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

}
