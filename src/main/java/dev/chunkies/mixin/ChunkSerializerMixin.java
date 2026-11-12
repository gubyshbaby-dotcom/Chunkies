package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.chunkies.config.ChunkiesConfig;
import dev.chunkies.loading.ChunkPreParser;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
    /**
     * The two palette decodes of every chunk section (block states and biomes). If a background thread has
     * already decoded this exact tag, use its result instead of doing the work again on the server thread.
     */
    @WrapOperation(
            method = "read",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
                    remap = false))
    private static DataResult<?> chunkies$usePreParsedPalette(
            Codec<?> codec, DynamicOps<?> ops, Object input, Operation<DataResult<?>> original) {
        DataResult<?> preParsed = ChunkPreParser.take(input);
        return preParsed != null ? preParsed : original.call(codec, ops, input);
    }

    /**
     * Building a section counts all of its blocks. If the background thread already built the section from
     * these very containers, take that one.
     */
    @WrapOperation(
            method = "read",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/chunk/PalettedContainer;Lnet/minecraft/world/level/chunk/PalettedContainerRO;)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection chunkies$usePreBuiltSection(
            PalettedContainer<BlockState> states,
            PalettedContainerRO<Holder<Biome>> biomes,
            Operation<LevelChunkSection> original) {
        LevelChunkSection preBuilt = ChunkPreParser.takeSection(states, biomes);
        return preBuilt != null ? preBuilt : original.call(states, biomes);
    }

    /**
     * Up to this point the deserializer has filled the fresh chunk object through its ordinary setters (light
     * flag, structure starts and references, generation status), and each of those marks the chunk as
     * modified. The result is that vanilla considers every chunk it has just read from disk "unsaved" and
     * serializes and writes it again - on the server thread - the moment it is unloaded or auto-saved, even if
     * nothing in it ever changed. The chunk is identical to what is on disk here, so it starts out clean.
     * Vanilla's own "this chunk must be re-saved" flag is evaluated right after this point and still applies,
     * and so does anything mods change in their chunk-load handlers.
     */
    @Inject(
            method = "read",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setAllReferences(Ljava/util/Map;)V",
                    shift = At.Shift.AFTER))
    private static void chunkies$freshlyLoadedChunkIsClean(
            ServerLevel level,
            PoiManager poiManager,
            RegionStorageInfo regionStorageInfo,
            ChunkPos pos,
            CompoundTag tag,
            CallbackInfoReturnable<ProtoChunk> cir,
            @Local ChunkAccess chunk) {
        if (ChunkiesConfig.skipRedundantSaves) {
            chunk.setUnsaved(false);
        }
    }
}
