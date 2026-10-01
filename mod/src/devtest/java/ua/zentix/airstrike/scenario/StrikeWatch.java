package ua.zentix.airstrike.scenario;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.strike.VirtualFlights;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Один удар сценария на встроенном сервере: какой снаряд пущен (UUID — новый среди снарядов в мире и вне его) и когда
 * он взорвался (первый {@code ExplosionEvent.Start}, источник урона которого — этот снаряд). Сущность у клиента
 * пропадает и без взрыва — при потере отслеживания и при уходе в полёт вне мира ({@code VirtualFlights.park}: новая
 * сущность с тем же UUID), поэтому «удар» — только взрыв своего снаряда.
 */
public final class StrikeWatch {
    @Nullable
    private volatile UUID projectile;
    private volatile int impactTick = -1;
    /** Время мира сервера у взрыва: часы клиента отстают от сервера и догоняют его рывками. */
    private volatile long impactGameTime = -1;
    @Nullable
    private volatile Vec3 impactAt;

    /** UUID снарядов мира: в загруженном мире и вне его. */
    public static Set<UUID> projectiles(ServerLevel level) {
        Set<UUID> out = new HashSet<>();
        for (StrikeProjectile p : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(StrikeProjectile.class), e -> true)) {
            out.add(p.getUUID());
        }
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) out.add(p.getUUID());
        return out;
    }

    /**
     * Пуск: снаряд — единственный новый UUID между снимками до и после команды. {@code false} — нового нет или их
     * несколько (тогда удар не засчитывается вовсе).
     */
    public boolean launched(Set<UUID> before, Set<UUID> after) {
        Set<UUID> fresh = new HashSet<>(after);
        fresh.removeAll(before);
        projectile = fresh.size() == 1 ? fresh.iterator().next() : null;
        return projectile != null;
    }

    /** Взрыв, чей источник — снаряд {@code by} (null — не снаряд). {@code true} — это удар нашего снаряда, первый. */
    public boolean onBlast(@Nullable UUID by, Vec3 at, int tick, long gameTime) {
        UUID mine = projectile;
        if (mine == null || !mine.equals(by) || impactTick >= 0) return false;
        impactAt = at;
        impactGameTime = gameTime;
        impactTick = tick;
        return true;
    }

    @Nullable
    public UUID projectile() {
        return projectile;
    }

    /** Тик удара (часы, которые передал {@link #onBlast}), −1 — удара ещё не было. */
    public int impactTick() {
        return impactTick;
    }

    /** Время мира сервера у удара, −1 — удара ещё не было. */
    public long impactGameTime() {
        return impactGameTime;
    }

    @Nullable
    public Vec3 impactAt() {
        return impactAt;
    }
}
