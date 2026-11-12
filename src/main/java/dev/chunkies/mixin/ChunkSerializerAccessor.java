package dev.chunkies.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkSerializer.class)
public interface ChunkSerializerAccessor {
    @Accessor("BLOCK_STATE_CODEC")
    static Codec<PalettedContainer<BlockState>> chunkies$blockStateCodec() {
        throw new AssertionError();
    }

    @Invoker("makeBiomeCodec")
    static Codec<PalettedContainerRO<Holder<Biome>>> chunkies$makeBiomeCodec(Registry<Biome> registry) {
        throw new AssertionError();
    }
}
