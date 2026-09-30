package ua.zentix.airstrike.entity.flight;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.util.UUID;

/** Взведение и неконтактный взрыватель снаряда. */
public final class ProximityFuse {
    /** Взрыватель взводится на таком удалении от пусковой (или с выходом на маршевый участок). */
    public static final double ARM_DISTANCE = 96;

    private ProximityFuse() {}

    /** Взрыватель взведён: снаряд отошёл от места старта {@code launchPos} (null — неизвестно) или вышел на маршевый участок. */
    public static boolean armed(FlightPhase phase, Vec3 pos, @Nullable Vec3 launchPos) {
        if (phase.ordinal() >= FlightPhase.CRUISE.ordinal()) return true;
        return launchPos == null || pos.distanceToSqr(launchPos) > ARM_DISTANCE * ARM_DISTANCE;
    }

    /**
     * Кого задевает путь носа {@code from → to} на скорости {@code speed}: как в датапаке — людей рядом с траекторией,
     * а ещё саму цель {@code targetId}. На своего ({@code owner}) — нет (если он сам не цель): пролёт над головой
     * запустившего не должен его убивать. Из задетых — ближайший к началу пути; null — никого.
     */
    @Nullable
    public static Entity victim(ServerLevel level, Entity self, Vec3 from, Vec3 to, double speed, @Nullable UUID targetId,
                                @Nullable UUID owner) {
        double r = speed / 8 + 1.8;
        AABB sweep = new AABB(from, to).inflate(r + 1);
        Entity best = null;
        double bestT = Double.MAX_VALUE;
        for (Entity e : level.getEntities(self, sweep, e -> isTarget(e, targetId, owner))) {
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 on = closestOnSegment(from, to, c);
            double reach = r + e.getBbWidth() * 0.5;
            if (on.distanceToSqr(c) <= reach * reach) {
                double t = on.distanceToSqr(from);
                if (t < bestT) {
                    bestT = t;
                    best = e;
                }
            }
        }
        return best;
    }

    /**
     * Цель, выбранная оператором, взводит взрыватель всегда (кроме наблюдателя). Случайный человек у траектории — по
     * ванильному правилу «кого замечают»: не в творческом режиме и не наблюдатель
     * ({@link EntitySelector#NO_CREATIVE_OR_SPECTATOR}: так ванильные мобы выбирают, на кого нападать).
     */
    private static boolean isTarget(Entity e, @Nullable UUID targetId, @Nullable UUID owner) {
        if (!e.isAlive() || e.isSpectator() || e instanceof StrikeProjectile) return false;
        if (e.getUUID().equals(targetId)) return true;
        return e instanceof Player && !e.getUUID().equals(owner) && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e);
    }

    /** Ближайшая к {@code p} точка отрезка {@code a–b}. */
    public static Vec3 closestOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0e-9) return a;
        double t = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return a.add(ab.scale(t));
    }
}
