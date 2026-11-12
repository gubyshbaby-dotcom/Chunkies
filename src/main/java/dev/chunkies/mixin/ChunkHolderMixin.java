package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.chunkies.loading.LightTouchTarget;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin implements LightTouchTarget {
    @Shadow
    public abstract void sectionLightChanged(LightLayer type, int sectionY);

    /** Server thread only. */
    @Unique
    private boolean chunkies$lightTouchOnly;

    @Override
    public void chunkies$lightSectionTouched(LightLayer layer, int sectionY) {
        this.chunkies$lightTouchOnly = true;
        try {
            this.sectionLightChanged(layer, sectionY);
        } finally {
            this.chunkies$lightTouchOnly = false;
        }
    }

    @WrapWithCondition(
            method = "sectionLightChanged",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setUnsaved(Z)V"))
    private boolean chunkies$markModifiedOnlyForRealChanges(ChunkAccess chunk, boolean unsaved) {
        return !this.chunkies$lightTouchOnly;
    }
}
