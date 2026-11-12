package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A structure that spans several chunks is one shared {@link StructureStart}; every chunk it touches places
 * "its" part of the pieces during the features step. Placing mutates the pieces (bounding boxes, template
 * settings, "chest already placed" flags, ...), which is fine on vanilla's single worldgen thread but not when
 * two far-apart chunks of the same large structure are decorated at the same moment. One structure is
 * therefore placed by one thread at a time.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @WrapMethod(method = "placeInChunk")
    private void chunkies$placeOneChunkAtATime(
            WorldGenLevel level,
            StructureManager structureManager,
            ChunkGenerator generator,
            RandomSource random,
            BoundingBox box,
            ChunkPos chunkPos,
            Operation<Void> original) {
        synchronized (this) {
            original.call(level, structureManager, generator, random, box, chunkPos);
        }
    }
}
