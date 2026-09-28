package ua.zentix.airstrike.nuclear.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearWarhead;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Световой импульс и проникающая радиация по сущностям (DESIGN-nuke §1.4, §1.5) — под общим бюджетом тика,
 * ближние к подрыву первыми. Импульс и в жизни не мгновенный: максимум у 15 кт через 0.04·W^0.44 ≈ 0.14 с, основная
 * энергия приходит за десяток таких времён (Glasstone §7.85), так что пара тиков на сотни сущностей — честно.
 * Разом в тике подрыва (ожоги, смерти, лут) сервер вставал на сотни миллисекунд.
 * <p>
 * Список — снимок ({@code getEntitiesOfClass}: смерть моба добавляет лут в живую карту сущностей), его первая
 * единица работы: в тике подрыва ничего тяжёлого. Кто потом умер или выгрузился, пропускается ({@link LivingEntity#isAlive}).
 */
final class PulseJob {
    private final Detonation d;
    @Nullable
    private final UUID owner;
    @Nullable
    private List<LivingEntity> targets;
    private int next;
    private int touched;
    private long nanos;
    private final long startTick;

    PulseJob(ServerLevel level, Detonation d, @Nullable UUID owner) {
        this.d = d;
        this.owner = owner;
        this.startTick = level.getGameTime();
    }

    /** Живые сущности в радиусе света и радиации, ближние первыми. */
    private static List<LivingEntity> inRange(ServerLevel level, Detonation d) {
        double range = NuclearWarhead.exposureRange(d);
        List<LivingEntity> in = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(d.burst(), range * 2, range * 2, range * 2),
                e -> e.distanceToSqr(d.burst()) <= range * range));
        in.sort(Comparator.comparingDouble(e -> e.distanceToSqr(d.burst())));
        return in;
    }

    /** Сколько успеем за бюджет; true — импульс прошёл по всем. */
    boolean work(ServerLevel level, WorkClock clock) {
        Entity ownerEntity = owner == null ? null : level.getPlayerByUUID(owner);
        while ((targets == null || next < targets.size()) && clock.canStart()) {
            long t0 = clock.begin();
            if (targets == null) targets = inRange(level, d);
            else if (NuclearWarhead.expose(level, d, targets.get(next++), ownerEntity)) touched++;
            nanos += clock.end(t0);
        }
        if (targets == null || next < targets.size()) return false;
        Airstrike.LOG.info("Ядерный подрыв №{}: свет и радиация — {} сущностей из {} за {} тиков, {} мс", d.id(), touched, targets.size(),
                level.getGameTime() - startTick + 1, nanos / 1_000_000);
        return true;
    }
}
