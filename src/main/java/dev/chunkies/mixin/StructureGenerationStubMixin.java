package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.chunkies.worldgen.WorldgenLocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.spongepowered.asm.mixin.Mixin;

/**
 * This is where a structure's pieces are actually laid out (jigsaw assembly, strongholds, fortresses, ...).
 * Plenty of that code, vanilla's included, keeps its working state in static fields because there has only
 * ever been one world generation thread per dimension. Deciding <em>whether</em> a structure may start in a
 * chunk (spacing, biome and terrain height checks - the expensive bulk of the structure-starts step) runs in
 * parallel; the layout itself stays strictly one at a time, across all dimensions.
 */
@Mixin(Structure.GenerationStub.class)
public abstract class StructureGenerationStubMixin {
    @WrapMethod(method = "getPiecesBuilder")
    private StructurePiecesBuilder chunkies$layOutOneStructureAtATime(Operation<StructurePiecesBuilder> original) {
        synchronized (WorldgenLocks.STRUCTURE_LAYOUT) {
            return original.call();
        }
    }
}
