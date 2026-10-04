package ua.zentix.airstrike.compat;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.Position;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.util.Terrain;

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
    private static volatile long lastFailureLog;

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
            fail(e);
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
            fail(e);
            return pos;
        }
    }

    /** Точка плота, которая сейчас находится в мировой точке world (для аппарата sub). */
    public static Vec3 toPlot(SubLevelAccess sub, Vec3 world) {
        try {
            return sub.logicalPose().transformPositionInverse(world);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return world;
        }
    }

    /** Центр аппарата в мире. */
    public static Vec3 center(SubLevelAccess sub) {
        try {
            Vector3d c = sub.boundingBox().center();
            return new Vec3(c.x, c.y, c.z);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return Vec3.ZERO;
        }
    }

    /** Во сколько раз аппарат в мире меньше, чем в плоте (наименьшая ось масштаба; 1 — если не узнать). */
    public static double minScale(SubLevelAccess sub) {
        try {
            var s = sub.logicalPose().scale();
            double m = Math.min(s.x(), Math.min(s.y(), s.z()));
            return m > 1e-3 ? m : 1;
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return 1;
        }
    }

    /** Аппараты в радиусе (по габаритам). */
    public static List<SubLevelAccess> near(Level level, Vec3 at, double radius) {
        List<SubLevelAccess> out = new ArrayList<>();
        if (broken) return out;
        try {
            BoundingBox3d box = new BoundingBox3d(at.x - radius, at.y - radius, at.z - radius, at.x + radius, at.y + radius, at.z + radius);
            for (SubLevelAccess s : companion().getAllIntersecting(level, box)) out.add(s);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
        return out;
    }

    /**
     * Может ли в кубе ±{@code radius} вокруг {@code at} быть аппарат: есть хоть один по габаритам — или Sable стоит,
     * а связь с ним сломана (тогда «да»: лучше ванильный путь со всеми миксинами Sable).
     */
    public static boolean mayHaveCraftNear(Level level, Vec3 at, double radius) {
        if (!ModList.get().isLoaded("sable")) return false;
        if (broken) return true;
        List<SubLevelAccess> near = near(level, at, radius);
        return broken || !near.isEmpty();
    }

    /** Все аппараты мира. */
    public static List<SubLevelAccess> all(Level level) {
        return near(level, Vec3.ZERO, 3.0e7);
    }

    /** Чанк в сетке плотов Sable — и тогда, когда аппарат там не загружен (в отличие от {@link #containing}). */
    public static boolean inPlotGrid(Level level, net.minecraft.world.level.ChunkPos chunk) {
        if (broken) return false;
        try {
            return companion().isInPlotGrid(level, chunk);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    /** Аппарат, в плоте которого лежит чанк, или null. */
    @Nullable
    public static SubLevelAccess containing(Level level, net.minecraft.world.level.ChunkPos chunk) {
        if (broken) return null;
        try {
            return companion().getContaining(level, chunk);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    /** Id аппарата, в плоте которого лежит чанк, или null. */
    @Nullable
    public static UUID containingId(Level level, net.minecraft.world.level.ChunkPos chunk) {
        SubLevelAccess sub = containing(level, chunk);
        return sub == null ? null : uniqueId(sub);
    }

    @Nullable
    private static UUID uniqueId(SubLevelAccess sub) {
        try {
            return sub.getUniqueId();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    @Nullable
    public static SubLevelAccess byId(Level level, Vec3 near, UUID id) {
        for (SubLevelAccess s : near(level, near, 2048)) {
            if (id.equals(s.getUniqueId())) return s;
        }
        return null;
    }

    /**
     * Аппарат с точкой плота {@code plotPos} виден из {@code from}: луч до её места в мире не упирается ни в блоки мира,
     * ни в другой аппарат — упереться в сам аппарат и значит его видеть (с Sable {@code Level.clip} отдаёт попадание
     * в аппарат в координатах его плота). Только по готовым чанкам ({@link Terrain#readyAlong}).
     */
    public static boolean visibleFrom(ServerLevel level, Vec3 from, Vec3 plotPos) {
        SubLevelAccess craft = containing(level, plotPos);
        if (craft == null) return false;
        Vec3 to = toWorld(level, plotPos);
        if (!Terrain.readyAlong(level, from, to)) return false;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return true;
        SubLevelAccess struck = containing(level, hit.getLocation());
        UUID id = uniqueId(craft);
        return struck != null && id != null && id.equals(uniqueId(struck));
    }

    /** Имя аппарата для интерфейса. */
    public static Component describe(@Nullable SubLevelAccess subLevel) {
        String name = subLevel == null ? null : name(subLevel);
        return name == null || name.isBlank()
                ? Component.translatable("airstrike.target.aircraft")
                : Component.translatable("airstrike.target.aircraft.named", name);
    }

    /** Имя аппарата, которое дал ему игрок; null — без имени. */
    @Nullable
    public static String name(SubLevelAccess subLevel) {
        try {
            return subLevel.getName();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    /**
     * API Sable не обещает стабильности (README Sable). Если обновление сборки его сломает (класса или метода
     * нет — {@link LinkageError}), мод продолжит работать без наведения на аппараты до перезапуска, а причина
     * останется в логе. Исключение в отдельном вызове (аппарат в эту секунду дробится или уже удалён) — только
     * этот вызов: иначе один сбой Sable отключал наведение на все аппараты до перезапуска игры.
     */
    private static void fail(Throwable e) {
        if (e instanceof LinkageError) {
            if (!broken) {
                broken = true;
                Airstrike.LOG.error("Sable companion сломался — наведение на аппараты отключено до перезапуска", e);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFailureLog > 60_000) {
            lastFailureLog = now;
            Airstrike.LOG.warn("Sable не ответил на запрос об аппарате (запрос пропущен)", e);
        }
    }
}
