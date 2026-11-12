package dev.chunkies.loading;

import net.minecraft.world.level.LightLayer;

/** Implemented (through a mixin) by chunk holders. */
public interface LightTouchTarget {
    /**
     * Like the vanilla "section light changed" notification, but for an update that did not change any stored
     * light value: players still get the section's light re-sent, the chunk is not marked as modified.
     */
    void chunkies$lightSectionTouched(LightLayer layer, int sectionY);
}
