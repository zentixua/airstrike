package ua.zentix.airstrike.mixin.net;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.airstrike.nuclear.world.ChunkSendGate;

/**
 * Чанки, чьи руины уже должны стоять, а не стоят, не уходят игроку ({@link ChunkSendGate}): на время отбора чанков
 * для отправки они убраны из очереди игрока и возвращаются в неё после — уйдут, когда руины встанут.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin implements ChunkSendGate.Gated {
    @Shadow
    @Final
    private LongSet pendingChunks;

    @Unique
    private LongArrayList airstrike$held;

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void airstrike$withhold(ServerPlayer player, CallbackInfo ci) {
        airstrike$held = ChunkSendGate.withhold(player.serverLevel(), pendingChunks);
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void airstrike$restore(ServerPlayer player, CallbackInfo ci) {
        if (airstrike$held == null) return;
        pendingChunks.addAll(airstrike$held);
        airstrike$held = null;
    }
}
