package dev.chunkies.loading;

/**
 * Implemented (through a mixin) by the server's chunk cache, which is where the light engine reports "the
 * light of this section was updated".
 */
public interface LightUpdateSink {
    /**
     * Tells the sink whether the update it is about to receive comes with changed light values. Called on the
     * light thread immediately before the notification, and reset to {@code true} right after it.
     */
    void chunkies$nextLightUpdateChangedValues(boolean changedValues);
}
