package dev.chunkies.loading;

import com.google.common.collect.MapMaker;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Decodes the block and biome palettes of a chunk that was just read from disk on a background thread.
 *
 * <p>In 1.21.1 the whole chunk is deserialized on the server thread. The bulk of that time goes into decoding
 * the per-section palettes and counting the blocks of every section, which is pure computation on the chunk's
 * own data and does not need the server thread at all (newer Minecraft versions moved it off-thread for the
 * same reason). We do that work right after the chunk's NBT has been read and upgraded, and the regular
 * deserializer then picks up the finished objects instead of building them again.</p>
 *
 * <p>Results are keyed by identity (of the palette's NBT tag, and of the decoded block container) and held
 * weakly, so anything that is never collected by the deserializer simply disappears together with the
 * chunk's NBT.</p>
 */
public final class ChunkPreParser {
    private static final ConcurrentMap<Object, DataResult<?>> PARSED = new MapMaker().weakKeys().concurrencyLevel(8).makeMap();
    private static final ConcurrentMap<Object, LevelChunkSection> SECTIONS = new MapMaker().weakKeys().concurrencyLevel(8).makeMap();

    // statistics, shown by /chunkies
    public static final AtomicLong SECTIONS_PREPARSED = new AtomicLong();
    public static final AtomicLong SECTIONS_USED = new AtomicLong();

    private ChunkPreParser() {
    }

    /** Runs on a background thread. Must never throw: the server thread falls back to decoding itself. */
    public static void preParse(
            CompoundTag chunkTag,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec) {
        try {
            ListTag sections = chunkTag.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < sections.size(); i++) {
                CompoundTag section = sections.getCompound(i);
                DataResult<PalettedContainer<BlockState>> states = null;
                DataResult<PalettedContainerRO<Holder<Biome>>> biomes = null;
                if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                    CompoundTag tag = section.getCompound("block_states");
                    states = blockStateCodec.parse(NbtOps.INSTANCE, tag);
                    PARSED.put(tag, states);
                    SECTIONS_PREPARSED.incrementAndGet();
                }
                if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                    CompoundTag tag = section.getCompound("biomes");
                    biomes = biomeCodec.parse(NbtOps.INSTANCE, tag);
                    PARSED.put(tag, biomes);
                }
                // Both halves decoded cleanly: build the section as well (its constructor counts every block).
                if (states != null && biomes != null && states.isSuccess() && biomes.isSuccess()) {
                    PalettedContainer<BlockState> stateContainer = states.getOrThrow();
                    SECTIONS.put(stateContainer, new LevelChunkSection(stateContainer, biomes.getOrThrow()));
                }
            }
        } catch (Throwable ignored) {
            // Broken data is reported by the regular deserializer, with proper context.
        }
    }

    /**
     * Returns (and forgets) the section that was pre-built from exactly these two containers, if there is one.
     */
    @Nullable
    public static LevelChunkSection takeSection(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        LevelChunkSection section = SECTIONS.remove(states);
        if (section != null && section.getStates() == states && section.getBiomes() == biomes) {
            SECTIONS_USED.incrementAndGet();
            return section;
        }
        return null;
    }

    /** Returns (and forgets) the pre-decoded container for the given palette tag, if there is one. */
    @Nullable
    public static DataResult<?> take(Object paletteTag) {
        return PARSED.remove(paletteTag);
    }
}
