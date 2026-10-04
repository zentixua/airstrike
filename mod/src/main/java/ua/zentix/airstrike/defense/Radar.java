package ua.zentix.airstrike.defense;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.target.Sides;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Радар: какие снаряды мода видно из точки и чьи они. Смотрит только список снарядов мира ({@link StrikeWorld#projectiles}:
 * в мире и вне его) — мир не обходит, блоков и чанков не читает, поэтому одинаково видит и снаряд в загруженных
 * чанках, и летящий вне мира. Игроков, мобов и аппараты Create Aeronautics радар не видит: только снаряды мода.
 * <p>
 * С какой дальности снаряд видно и стоит ли он ракеты — его паспорт ({@link WeaponSpec.Signature}): B-2 малозаметен,
 * МБР не догнать, «Град» не стоит ракеты. Свои — тот же игрок или его команда ({@link Sides}); снаряд без владельца
 * (консоль, командный блок) — чужой. Огонь — отдельно ({@link FireControl}): тот же радар возьмёт и переносной ЗРК.
 */
public final class Radar {
    private Radar() {}

    /**
     * Цель на экране радара.
     *
     * @param projectile снаряд (в мире или вне его)
     * @param distance   до него, блоков
     * @param hostile    чужой
     * @param engageable чужой, стоит ракеты, без седока и ближе дальности огня
     */
    public record Track(StrikeProjectile projectile, double distance, boolean hostile, boolean engageable) {
        public UUID id() {
            return projectile.getUUID();
        }

        public Vec3 position() {
            return projectile.position();
        }
    }

    /** Снаряд в воздухе: не убран, не в ячейке и не на направляющей пусковой (её радар не отличит от земли), не в грунте. */
    public static boolean airborne(StrikeProjectile p) {
        if (p.isRemoved() || !p.isActive()) return false;
        FlightPhase ph = p.flightPhase();
        return !ph.onLauncher() && ph != FlightPhase.DRILL;
    }

    /**
     * Что видит радар в точке {@code at}, от ближних к дальним.
     *
     * @param range       дальность радара, блоков (малозаметные — ближе, по паспорту)
     * @param engageRange дальность огня, блоков
     * @param owner       чей радар (null — ничей: чужие ему все)
     */
    public static List<Track> scan(ServerLevel level, Vec3 at, double range, double engageRange, @Nullable UUID owner) {
        MinecraftServer server = level.getServer();
        List<Track> out = new ArrayList<>();
        double r2 = range * range;
        for (StrikeProjectile p : DefenseWorld.get(level).projectiles(level)) {
            if (!airborne(p)) continue;
            double d2 = p.position().distanceToSqr(at);
            if (d2 > r2) continue;
            WeaponSpec.Signature sig = p.airframe().radar();
            double d = Math.sqrt(d2);
            if (d > range * sig.visibility()) continue;
            boolean hostile = !Sides.friendly(server, owner, p.ownerId());
            // снаряд с седоком — не цель: ракета ЗРК не идёт за тем, на ком кто-то сидит
            boolean engageable = hostile && sig.intercept() && !p.isVehicle() && d <= engageRange;
            out.add(new Track(p, d, hostile, engageable));
        }
        out.sort(Comparator.comparingDouble(Track::distance));
        return out;
    }
}
