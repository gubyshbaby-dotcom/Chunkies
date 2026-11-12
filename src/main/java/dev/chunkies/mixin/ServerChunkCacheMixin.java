package dev.chunkies.mixin;

import dev.chunkies.config.ChunkiesConfig;
import dev.chunkies.loading.LightTouchTarget;
import dev.chunkies.loading.LightUpdateSink;
import dev.chunkies.loading.LoadingStats;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements LightUpdateSink {
    @Shadow
    @Final
    public ServerLevel level;

    @Shadow
    @Nullable
    private ChunkHolder getVisibleChunkIfPresent(long chunkPos) {
        throw new AssertionError();
    }

    /** Only touched by the light thread of this level, around a single notification. */
    @Unique
    private boolean chunkies$lightUpdateChangedValues = true;

    @Override
    public void chunkies$nextLightUpdateChangedValues(boolean changedValues) {
        this.chunkies$lightUpdateChangedValues = changedValues;
    }

    /**
     * A light notification for a section whose stored values did not change (see
     * LayerLightSectionStorageMixin). Vanilla queues a server-thread task for each of them - about fifty per
     * loaded chunk - and each task marks the chunk as modified. Nothing on disk is out of date, so the chunk
     * stays clean; and unless players already have the chunk (then its light is re-sent, as in vanilla),
     * there is nothing to do at all.
     */
    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void chunkies$skipLightUpdatesWithoutChanges(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        if (this.chunkies$lightUpdateChangedValues || !ChunkiesConfig.skipRedundantSaves) {
            return;
        }
        ci.cancel();
        LoadingStats.LIGHT_UPDATES_SKIPPED.increment();
        ChunkHolder holder = this.getVisibleChunkIfPresent(pos.chunk().toLong());
        if (holder == null || holder.getTickingChunk() == null) {
            return; // not shown to anyone yet: the light travels with the chunk when it is sent
        }
        int sectionY = pos.y();
        this.level.getServer().execute(() -> ((LightTouchTarget) holder).chunkies$lightSectionTouched(layer, sectionY));
    }
}
