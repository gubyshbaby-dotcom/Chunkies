package dev.chunkies.mixin;

import dev.chunkies.config.ChunkiesConfig;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The server sends each player at most as many chunks per tick as that player's client asked for in its
 * last acknowledgement (capped at 64). The client's number is deliberately conservative - it budgets only a
 * few milliseconds per tick for chunks - so a server that has the chunks ready still trickles them out. The
 * acknowledgement based flow control itself stays untouched (a client that stops acknowledging stops
 * receiving); only the rate it asks for is scaled.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    @ModifyVariable(method = "onChunkBatchReceivedByClient", at = @At("HEAD"), argsOnly = true)
    private float chunkies$boostRequestedRate(float desiredChunksPerTick) {
        if (!ChunkiesConfig.sendBoost || Float.isNaN(desiredChunksPerTick)) {
            return desiredChunksPerTick;
        }
        return Math.max(ChunkiesConfig.sendMinChunksPerTick, desiredChunksPerTick * ChunkiesConfig.sendRateMultiplier);
    }

    @ModifyConstant(method = "onChunkBatchReceivedByClient", constant = @Constant(floatValue = 64.0F))
    private float chunkies$raiseRateCap(float vanillaMax) {
        return ChunkiesConfig.sendBoost ? Math.max(vanillaMax, ChunkiesConfig.sendMaxChunksPerTick) : vanillaMax;
    }
}
