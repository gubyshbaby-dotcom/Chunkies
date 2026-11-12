package dev.chunkies.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Per-dimension state of the {@link ChunkStepScheduler}: which chunk positions are currently locked by a
 * running generation step, which steps are waiting for one of those locks, how many steps of this dimension
 * are queued or running, and where the players are (used to work on the chunks closest to a player first).
 */
public final class StepDomain {
    /** Chunk positions locked by running steps. Guarded by the scheduler mutex. */
    final LongOpenHashSet locked = new LongOpenHashSet();
    /** Steps parked until the given chunk position is unlocked. Guarded by the scheduler mutex. */
    final Long2ObjectOpenHashMap<ArrayList<ChunkStepScheduler.Step>> waiters = new Long2ObjectOpenHashMap<>();
    /** Steps of this dimension that are queued or running. */
    final AtomicInteger inFlight = new AtomicInteger();

    private volatile long[] playerChunks = new long[0];
    private int ticksSinceRefresh;

    public boolean hasWork() {
        return this.inFlight.get() > 0;
    }

    /** Called from the server thread once per tick. */
    public void updatePlayers(Collection<ServerPlayer> players) {
        long[] old = this.playerChunks;
        long[] now = new long[players.size()];
        int i = 0;
        boolean changed = old.length != now.length;
        for (ServerPlayer player : players) {
            if (i >= now.length) {
                break;
            }
            long pos = player.chunkPosition().toLong();
            changed |= i >= old.length || old[i] != pos;
            now[i++] = pos;
        }
        if (changed) {
            this.playerChunks = now;
        }
        // Tickets can also change without anybody moving (force-loading, view distance), so refresh the
        // queue's priorities once a second regardless.
        if (changed || ++this.ticksSinceRefresh >= 20) {
            this.ticksSinceRefresh = 0;
            if (this.hasWork()) {
                ChunkStepScheduler.prioritiesChanged();
            }
        }
    }

    /** Chebyshev distance (in chunks) from the given chunk to the closest player, or 0 if nobody is around. */
    int distanceToNearestPlayer(int chunkX, int chunkZ) {
        long[] players = this.playerChunks;
        if (players.length == 0) {
            return 0;
        }
        int best = Integer.MAX_VALUE;
        for (long pos : players) {
            int dx = Math.abs(ChunkPos.getX(pos) - chunkX);
            int dz = Math.abs(ChunkPos.getZ(pos) - chunkZ);
            int d = Math.max(dx, dz);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }
}
