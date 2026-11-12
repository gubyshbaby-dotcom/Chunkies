package dev.chunkies.worldgen;

/** Locks shared by the world generation mixins. */
public final class WorldgenLocks {
    /** Held while the pieces of a structure are being laid out. */
    public static final Object STRUCTURE_LAYOUT = new Object();

    private WorldgenLocks() {
    }
}
