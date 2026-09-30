package ua.zentix.airstrike.mixin.explosion;

import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.airstrike.warhead.ExplosionTimer;

/**
 * Отметки времени между шагами {@code Explosion.explode} для {@link ExplosionTimer}: только чтение часов, поведение
 * взрыва не меняется. Пишутся лишь во время взрыва боевой части мода.
 */
@Mixin(Explosion.class)
public abstract class ExplosionTimingMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void airstrike$enter(CallbackInfo ci) {
        ExplosionTimer.enter();
    }

    @Inject(method = "explode", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/Level;gameEvent(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;)V"))
    private void airstrike$afterGameEvent(CallbackInfo ci) {
        ExplosionTimer.mark(ExplosionTimer.Stage.GAME_EVENT.ordinal());
    }

    @Inject(method = "explode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private void airstrike$afterRays(CallbackInfo ci) {
        ExplosionTimer.mark(ExplosionTimer.Stage.RAYS.ordinal());
    }

    @Inject(method = "explode", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private void airstrike$afterEntities(CallbackInfo ci) {
        ExplosionTimer.mark(ExplosionTimer.Stage.ENTITIES.ordinal());
    }

    @Inject(method = "explode", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/neoforged/neoforge/event/EventHooks;onExplosionDetonate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/Explosion;Ljava/util/List;D)V"))
    private void airstrike$afterDetonate(CallbackInfo ci) {
        ExplosionTimer.mark(ExplosionTimer.Stage.DETONATE.ordinal());
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void airstrike$afterDamage(CallbackInfo ci) {
        ExplosionTimer.exit();
    }
}
