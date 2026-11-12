package dev.chunkies.loading;

import java.util.concurrent.atomic.LongAdder;

/** Counters shown by {@code /chunkies}. */
public final class LoadingStats {
    public static final LongAdder LIGHT_UPDATES_SKIPPED = new LongAdder();

    private LoadingStats() {
    }
}
