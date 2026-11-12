package dev.chunkies.mixin;

import com.mojang.datafixers.util.Either;
import dev.chunkies.config.ChunkiesConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.StrictQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every chunk task for the server thread, the worldgen actor and the light thread passes through this sorter.
 * Vanilla hands a processor the tasks of exactly one chunk position, waits until they have all reported back
 * and only then picks the next position - a full round trip between threads per chunk. That is unnoticeable
 * while four chunks are in flight, but once the pipeline is actually fed it becomes the pace of everything:
 * a chunk that is ready to be finished on the server thread sits in this queue for over a hundred
 * milliseconds while the server thread is idle most of the time.
 *
 * <p>For the server thread, hand out several positions per round trip instead. They are still taken in
 * priority order and the server thread still runs them one after another in exactly the order they were
 * handed out; a task that arrives with a higher priority now waits for at most one batch rather than for one
 * position.</p>
 */
@Mixin(ChunkTaskPriorityQueueSorter.class)
public abstract class ChunkTaskPriorityQueueSorterMixin {
    @Shadow
    @Final
    private ProcessorMailbox<StrictQueue.IntRunnable> mailbox;

    @Shadow
    @Final
    private Set<ProcessorHandle<?>> sleeping;

    @Shadow
    private <T> void pollTask(ChunkTaskPriorityQueue<Function<ProcessorHandle<Unit>, T>> queue, ProcessorHandle<T> processor) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean chunkies$runsOnServerThread(ProcessorHandle<?> processor) {
        String name = processor.name();
        return "main".equals(name) || "player ticket throttler".equals(name);
    }

    @Inject(method = "pollTask", at = @At("HEAD"), cancellable = true)
    private <T> void chunkies$handOutBatches(
            ChunkTaskPriorityQueue<Function<ProcessorHandle<Unit>, T>> queue, ProcessorHandle<T> processor, CallbackInfo ci) {
        int batch = ChunkiesConfig.dispatchBatch;
        // Only the server thread is worth batching for: reaching it costs a wake-up of a thread that is
        // usually parked, and that is where chunks were measured to queue up. The worldgen and light actors
        // live on the worker pool, where a hand-off is cheap, and they profit from the finer-grained
        // priority order of one position at a time while terrain is being generated.
        if (batch <= 1 || !chunkies$runsOnServerThread(processor)) {
            return;
        }
        ci.cancel();
        this.mailbox.tell(new StrictQueue.IntRunnable(3, () -> {
            List<CompletableFuture<?>> inFlight = null;
            int positions = 0;
            while (positions < batch) {
                // Tasks of one chunk position, or null if the queue is empty or at its limit.
                Stream<Either<Function<ProcessorHandle<Unit>, T>, Runnable>> tasks = queue.pop();
                if (tasks == null) {
                    break;
                }
                positions++;
                if (inFlight == null) {
                    inFlight = new ArrayList<>();
                }
                List<CompletableFuture<?>> collect = inFlight;
                // Same handling as vanilla: real tasks are sent to the processor, markers run right here.
                tasks.forEach(task -> task.ifLeft(work -> collect.add(processor.ask(work))).ifRight(Runnable::run));
            }
            if (positions == 0) {
                this.sleeping.add(processor);
            } else {
                CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).thenAccept(done -> this.pollTask(queue, processor));
            }
        }));
    }
}
