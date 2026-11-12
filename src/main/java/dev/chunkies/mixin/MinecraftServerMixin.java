package dev.chunkies.mixin;

import dev.chunkies.config.ChunkiesConfig;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * After each tick the server works through pending chunk tasks until the next tick is due. If tasks are
 * still waiting at that point, vanilla lets them push the next tick back by up to a whole tick length
 * (50 ms). Vanilla rarely gets there, because its own queues leave gaps in the supply of tasks. With the
 * pipeline actually kept busy the supply is continuous, and that allowance would be used on every tick
 * during chunk loading - the server would drop to 18 TPS and below exactly when players are moving.
 *
 * <p>Cap the allowance. Chunk tasks still get all the time that is left in a tick, plus this much if the
 * tick itself ran over, so loading keeps making progress on an overloaded server.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @ModifyArg(method = "runServer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(JJ)J"), index = 0)
    private long chunkies$capLateTickAllowance(long vanillaDeadline) {
        long capNanos = ChunkiesConfig.lateTickAllowanceMillis * 1_000_000L;
        long now = Util.getNanos();
        long allowance = vanillaDeadline - now;
        return allowance > capNanos ? now + capNanos : vanillaDeadline;
    }
}
