package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.chunkies.loading.LightUpdateSink;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The light engine notifies the chunk system about every section that an update pass "affected". That
 * includes sections whose light values really changed, but also every section (and its 26 neighbours) that
 * was merely set up - which is all that happens when a chunk with stored light is loaded from disk. The chunk
 * system reacts to each notification by marking the chunk as modified, so loading a chunk makes it and its
 * eight neighbours "unsaved" although their light is exactly what is on disk.
 *
 * <p>Here we remember which sections actually had a light value written during the pass, so the receiving
 * end can tell the two cases apart.</p>
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {
    @Unique
    private final LongOpenHashSet chunkies$writtenSections = new LongOpenHashSet();
    @Unique
    private long chunkies$lastWrittenSection;
    @Unique
    private boolean chunkies$hasLastWrittenSection;

    @Inject(method = "setStoredLevel", at = @At("HEAD"))
    private void chunkies$rememberWrittenSection(long levelPos, int lightLevel, CallbackInfo ci) {
        long section = SectionPos.blockToSection(levelPos);
        // Light spreads block by block, so consecutive writes nearly always hit the same section.
        if (!this.chunkies$hasLastWrittenSection || section != this.chunkies$lastWrittenSection) {
            this.chunkies$writtenSections.add(section);
            this.chunkies$lastWrittenSection = section;
            this.chunkies$hasLastWrittenSection = true;
        }
    }

    @WrapOperation(
            method = "swapSectionMap",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LightChunkGetter;onLightUpdate(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)V"))
    private void chunkies$tellWhetherValuesChanged(LightChunkGetter chunkSource, LightLayer layer, SectionPos pos, Operation<Void> original) {
        if (chunkSource instanceof LightUpdateSink sink) {
            sink.chunkies$nextLightUpdateChangedValues(this.chunkies$writtenSections.contains(pos.asLong()));
            try {
                original.call(chunkSource, layer, pos);
            } finally {
                sink.chunkies$nextLightUpdateChangedValues(true);
            }
        } else {
            original.call(chunkSource, layer, pos);
        }
    }

    @Inject(method = "swapSectionMap", at = @At("TAIL"))
    private void chunkies$forgetWrittenSections(CallbackInfo ci) {
        if (!this.chunkies$writtenSections.isEmpty()) {
            this.chunkies$writtenSections.clear();
        }
        this.chunkies$hasLastWrittenSection = false;
    }
}
