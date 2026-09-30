package ua.zentix.airstrike.warhead;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Ванильный путь взрыва у аппарата Sable ({@link StagedExplosion}): {@code Explosion.explode} идёт целиком (лучи
 * с миксином Sable, сбор сущностей, {@code ExplosionEvent.Detonate}), а свой цикл урона и отбрасывания пропускает —
 * список сущностей после {@code Detonate} (что оставили обработчики) уходит в урон мода порциями. Цикл пропускает
 * миксин {@code mixin/explosion/ExplosionHandoffMixin} и только у того взрыва, который мод сейчас ведёт: взрыв
 * из обработчика {@code Detonate} — другой объект, его урон ванильный. Не встал миксин — урон остаётся ванильным
 * ({@link #end} даёт null).
 */
public final class ExplosionHandoff {
    @Nullable
    private static Explosion expected;
    @Nullable
    private static List<Entity> taken;
    /** Последний ванильный взрыв мода отдал урон моду. */
    private static boolean lastHanded;

    private ExplosionHandoff() {}

    /** Мод начинает ванильный {@code explode()} взрыва {@code e}. */
    static void begin(Explosion e) {
        expected = e;
        taken = null;
    }

    /** {@code explode()} кончился: список сущностей после {@code Detonate} или null — урон уже сделала ваниль. */
    @Nullable
    static List<Entity> end() {
        List<Entity> t = taken;
        lastHanded = t != null;
        expected = null;
        taken = null;
        return t;
    }

    /** Миксин: цикл урона {@code explode()} взрыва {@code e} с его списком; {@code true} — список забран, цикл пропустить. */
    public static boolean take(Explosion e, List<Entity> entities) {
        if (e != expected || taken != null) return false;
        taken = new ArrayList<>(entities);
        return true;
    }

    /** Проверки: миксин забрал список у последнего ванильного взрыва мода (встал). */
    public static boolean lastHanded() {
        return lastHanded;
    }
}
