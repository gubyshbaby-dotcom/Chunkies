package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.chunkies.config.ChunkiesConfig;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    /**
     * For a chunk that already carries correct light (every finished chunk loaded from disk) the "light" step
     * has nothing to compute: it only waits for the light engine to take in the stored light data. Vanilla
     * nevertheless flips the chunk's "light is correct" flag off and on again around that wait, and flipping
     * the flag marks the chunk as modified - one of the reasons every loaded chunk gets written straight back
     * to disk. Do the same wait without touching the flag.
     */
    @WrapOperation(
            method = "light",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ThreadedLevelLightEngine;lightChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<ChunkAccess> chunkies$keepLitChunksClean(
            ThreadedLevelLightEngine lightEngine,
            ChunkAccess chunk,
            boolean isLighted,
            Operation<CompletableFuture<ChunkAccess>> original) {
        if (isLighted && ChunkiesConfig.skipRedundantSaves) {
            ChunkPos pos = chunk.getPos();
            return lightEngine.waitForPendingTasks(pos.x, pos.z).thenApply(ignored -> chunk);
        }
        return original.call(lightEngine, chunk, isLighted);
    }
}
