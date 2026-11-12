package dev.chunkies.mixin;

import dev.chunkies.config.ChunkiesConfig;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Light tasks (including the ones every chunk needs before it can become "full") are only collected by the
 * light thread; actually running a batch is triggered from the server thread whenever it is idle, or once a
 * thousand tasks have piled up. While the server thread is busy ticking, chunks therefore sit waiting for
 * light. Let the light thread kick off a batch itself as soon as it has received work. Batches still form
 * naturally: the trigger is queued behind every task that is already waiting.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin {
    @Shadow
    public abstract void tryScheduleUpdate();

    @ModifyArg(
            method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;message(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter$Message;"),
            index = 0)
    private Runnable chunkies$scheduleLightEagerly(Runnable enqueue) {
        if (!ChunkiesConfig.eagerLight) {
            return enqueue;
        }
        return () -> {
            enqueue.run();
            this.tryScheduleUpdate();
        };
    }
}
