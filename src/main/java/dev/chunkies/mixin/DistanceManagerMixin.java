package dev.chunkies.mixin;

import dev.chunkies.config.ChunkiesConfig;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Vanilla lets only 4 player-requested chunks travel through the load/generate pipeline at once (the "player
 * ticket throttler"). Each of those chunks needs a dozen thread hand-offs before the slot frees up, so on an
 * otherwise idle server chunk loading is limited by hand-off latency rather than by CPU or disk. The
 * throttler still hands chunks out nearest-first; we only widen it.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;<init>(Ljava/util/List;Ljava/util/concurrent/Executor;I)V"),
            index = 2)
    private int chunkies$widenPlayerTicketThrottler(int vanillaMaxTasks) {
        return ChunkiesConfig.resolvedPlayerTicketConcurrency();
    }
}
