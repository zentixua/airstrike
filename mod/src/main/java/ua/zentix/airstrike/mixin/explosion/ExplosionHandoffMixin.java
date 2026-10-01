package ua.zentix.airstrike.mixin.explosion;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ua.zentix.airstrike.warhead.ExplosionHandoff;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Урон взрыва мода у аппарата — порциями ({@link ExplosionHandoff}): у взрыва, который мод ведёт ванильным
 * {@code explode()}, цикл урона и отбрасывания (единственный {@code List.iterator} метода — по списку сущностей после
 * {@code ExplosionEvent.Detonate}) идёт по пустому списку, а сам список забирает мод — только тот объект, что ушёл
 * в {@code Detonate} ({@link ExplosionHandoff#onDetonate}). Остальные взрывы не трогаются.
 */
@Mixin(Explosion.class)
public abstract class ExplosionHandoffMixin {
    @WrapOperation(method = "explode", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", ordinal = 0))
    private Iterator<Entity> airstrike$handOffDamage(List<Entity> entities, Operation<Iterator<Entity>> original) {
        if (ExplosionHandoff.take((Explosion) (Object) this, entities)) return Collections.emptyIterator();
        return original.call(entities);
    }
}
