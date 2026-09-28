package ua.zentix.airstrike.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import ua.zentix.airstrike.compat.SplitGuard;

/** Дробление аппарата Sable не роняет мир — см. {@link SplitGuard}. Без Sable миксин просто не применяется. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager", remap = false)
public abstract class SubLevelSplitGuardMixin implements SplitGuard {
    @WrapMethod(method = "split")
    private void airstrike$guardSplit(Operation<Void> original) {
        try {
            original.call();
        } catch (RuntimeException e) {
            if (!SplitGuard.isRemovedPlot(e)) throw e;
            SplitGuard.swallowed(e);
        }
    }
}
