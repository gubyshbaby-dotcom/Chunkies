package dev.chunkies.worldgen;

import dev.chunkies.config.ChunkiesConfig;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * How a generation step is run by the {@link ChunkStepScheduler}.
 *
 * @param lockRadius    chunks around the centre that the step may write to; they stay locked while it runs
 * @param exclusive     at most one exclusive step runs at any time (used for code that vanilla and mods
 *                      wrote assuming a single world generation thread)
 * @param holdUntilDone keep the locks and the worker slot until the step's future completes, not just until
 *                      the synchronous part returns
 */
public record StepPlan(int lockRadius, boolean exclusive, boolean holdUntilDone) {
    private static final StepPlan EXCLUSIVE = new StepPlan(0, true, true);
    private static final StepPlan PARALLEL = new StepPlan(0, false, true);
    private static final StepPlan PARALLEL_SYNC_PART = new StepPlan(0, false, false);

    /**
     * Decides whether (and how) the given step goes through the scheduler. Returns {@code null} for steps
     * that keep their vanilla behaviour: everything that is not real generation work (loading a chunk from
     * disk walks through the same statuses with no-op tasks) and the steps that already hop to another
     * thread on their own.
     */
    @Nullable
    public static StepPlan of(ChunkStep step, ChunkAccess chunk) {
        ChunkStatus status = step.targetStatus();
        if (ChunkPyramid.GENERATION_PYRAMID.getStepTo(status) != step) {
            return null; // loading pyramid (or something custom): nothing heavy happens there
        }
        if (!chunk.getPersistedStatus().isBefore(status)) {
            return null; // already generated up to here
        }
        if (status == ChunkStatus.STRUCTURE_STARTS) {
            // Most of this step is checking where structures may start (spacing, biome, terrain height): pure
            // sampling that is safe to run side by side. The stateful part, laying out the pieces, takes a
            // global lock of its own (see StructureGenerationStubMixin).
            return ChunkiesConfig.parallelStructureStarts ? PARALLEL : EXCLUSIVE;
        }
        if (status == ChunkStatus.SPAWN) {
            // Creates and initialises entities. Cheap, and entity code was never meant to run concurrently
            // within a dimension, so keep it one at a time like vanilla does.
            return EXCLUSIVE;
        }
        if (status == ChunkStatus.STRUCTURE_REFERENCES
                || status == ChunkStatus.BIOMES
                || status == ChunkStatus.NOISE
                || status == ChunkStatus.SURFACE
                || status == ChunkStatus.CARVERS) {
            return PARALLEL; // only ever touch their own chunk
        }
        if (status == ChunkStatus.FEATURES) {
            // Features and structure pieces write into the neighbouring chunks as well.
            int radius = Math.max(0, step.blockStateWriteRadius());
            return radius == 0 ? PARALLEL : new StepPlan(radius, false, true);
        }
        if (status == ChunkStatus.INITIALIZE_LIGHT) {
            // The synchronous part scans the chunk's blocks (so it must not overlap with a neighbour's
            // features); the rest is queued on the light engine and must not keep a worker slot busy.
            return PARALLEL_SYNC_PART;
        }
        return null; // EMPTY (disk), LIGHT (light engine), FULL (server thread)
    }
}
