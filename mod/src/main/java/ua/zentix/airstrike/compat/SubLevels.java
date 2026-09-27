package ua.zentix.airstrike.compat;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.Position;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Единственное место работы с летательными аппаратами Sable («sub-levels»), через sable-companion (MIT, вшит в jar).
 * Без Sable companion отдаёт заглушку: аппаратов нет, координаты не меняются.
 * <p>
 * Как устроено: блоки аппарата живут в далёком «плоте» (plot) того же мира, а в мире рисуется их проекция.
 * С Sable ванильный {@code Level.clip} уже учитывает аппараты и возвращает попадание в координатах плота —
 * поэтому достаточно узнать, лежит ли точка в плоте, и спроецировать её в мир.
 */
public final class SubLevels {
    private static volatile boolean broken;

    private SubLevels() {}

    private static SableCompanion companion() {
        return SableCompanion.INSTANCE;
    }

    /** Аппарат, в плоте которого лежит точка, или null. */
    @Nullable
    public static SubLevelAccess containing(Level level, Position plotPos) {
        if (broken) return null;
        try {
            return companion().getContaining(level, plotPos);
        } catch (RuntimeException | LinkageError e) {
            disable(e);
            return null;
        }
    }

    public static boolean isInPlot(Level level, Position pos) {
        return containing(level, pos) != null;
    }

    /** Мировые координаты точки плота (для точки вне плота — она же). */
    public static Vec3 toWorld(Level level, Vec3 pos) {
        if (broken) return pos;
        try {
            return companion().projectOutOfSubLevel(level, (Position) pos);
        } catch (RuntimeException | LinkageError e) {
            disable(e);
            return pos;
        }
    }

    /** Точка плота, которая сейчас находится в мировой точке world (для аппарата sub). */
    public static Vec3 toPlot(SubLevelAccess sub, Vec3 world) {
        try {
            return sub.logicalPose().transformPositionInverse(world);
        } catch (RuntimeException | LinkageError e) {
            disable(e);
            return world;
        }
    }

    /** Центр аппарата в мире. */
    public static Vec3 center(SubLevelAccess sub) {
        Vector3d c = sub.boundingBox().center();
        return new Vec3(c.x, c.y, c.z);
    }

    /** Аппараты в радиусе (по габаритам). */
    public static List<SubLevelAccess> near(Level level, Vec3 at, double radius) {
        List<SubLevelAccess> out = new ArrayList<>();
        if (broken) return out;
        try {
            BoundingBox3d box = new BoundingBox3d(at.x - radius, at.y - radius, at.z - radius, at.x + radius, at.y + radius, at.z + radius);
            for (SubLevelAccess s : companion().getAllIntersecting(level, box)) out.add(s);
        } catch (RuntimeException | LinkageError e) {
            disable(e);
        }
        return out;
    }

    @Nullable
    public static SubLevelAccess byId(Level level, Vec3 near, UUID id) {
        for (SubLevelAccess s : near(level, near, 2048)) {
            if (id.equals(s.getUniqueId())) return s;
        }
        return null;
    }

    /** Имя аппарата для интерфейса. */
    public static Component describe(@Nullable SubLevelAccess subLevel) {
        String name = subLevel == null ? null : subLevel.getName();
        return name == null || name.isBlank()
                ? Component.translatable("airstrike.target.aircraft")
                : Component.translatable("airstrike.target.aircraft.named", name);
    }

    /**
     * API Sable не обещает стабильности (см. docs/HANDOFF-mod.md). Если обновление сборки его сломает,
     * мод продолжит работать без наведения на аппараты, а причина останется в логе.
     */
    private static void disable(Throwable e) {
        if (!broken) {
            broken = true;
            Airstrike.LOG.error("Sable companion сломался — наведение на аппараты отключено до перезапуска", e);
        }
    }
}
