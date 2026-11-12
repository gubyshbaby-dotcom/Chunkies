package dev.chunkies.worldgen;

import dev.chunkies.Chunkies;
import dev.chunkies.config.ChunkiesConfig;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import net.minecraft.server.MinecraftServer;

/**
 * Keeps world generation from starving the server thread.
 *
 * <p>The generation workers are sized for the machine (cores - 1), which is the same CPU budget vanilla gives
 * its own worker pool. On a box where that leaves the server thread fighting for a core, the game tick is
 * what should win. Once per second this looks at two things: is the tick close to its time budget, and did
 * the server thread actually get the CPU while ticking (CPU time vs. wall-clock time of the tick)? Only when
 * both say "the tick is slow because the thread is not getting scheduled" is a worker taken away, down to
 * half of them; workers come back as soon as the tick has headroom again. A server whose ticks are slow for
 * reasons of their own keeps all its workers - taking them away would not help it.</p>
 */
public final class WorkerGovernor {
    private static final long SAMPLE_NANOS = 1_000_000_000L;
    /** Workers are given back only after this many calm samples in a row; they are taken away at once. */
    private static final int CALM_SAMPLES_TO_RECOVER = 3;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME_SUPPORTED = cpuTimeSupported();

    private static long tickStartWall;
    private static long tickStartCpu;
    private static long wallSum;
    private static long cpuSum;
    private static int ticks;
    private static long sampleStart;
    private static int calmSamples;

    private WorkerGovernor() {
    }

    private static boolean cpuTimeSupported() {
        try {
            return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** Server thread, start of a tick. */
    public static void tickStart() {
        if (!CPU_TIME_SUPPORTED || !ChunkiesConfig.yieldToServerThread) {
            return;
        }
        tickStartWall = System.nanoTime();
        tickStartCpu = THREADS.getCurrentThreadCpuTime();
    }

    /** Server thread, end of a tick. */
    public static void tickEnd(MinecraftServer server) {
        if (!CPU_TIME_SUPPORTED || !ChunkiesConfig.yieldToServerThread) {
            ChunkStepScheduler.setActiveSlots(Integer.MAX_VALUE);
            return;
        }
        if (tickStartWall == 0L) {
            return;
        }
        wallSum += System.nanoTime() - tickStartWall;
        cpuSum += THREADS.getCurrentThreadCpuTime() - tickStartCpu;
        long now = System.nanoTime();
        tickStartWall = 0L;
        ticks++;
        if (sampleStart == 0L) {
            sampleStart = now;
        }
        if (now - sampleStart < SAMPLE_NANOS) {
            return;
        }
        sampleStart = now;
        double budgetMillis = server.tickRateManager().nanosecondsPerTick() / 1.0e6;
        double tickMillis = wallSum / 1.0e6 / ticks;
        double cpuShare = wallSum > 0L ? (double) cpuSum / (double) wallSum : 1.0;
        wallSum = 0L;
        cpuSum = 0L;
        ticks = 0;
        if (server.tickRateManager().isSprinting()) {
            return;
        }
        int active = ChunkStepScheduler.activeSlots();
        int decision = decide(active, ChunkStepScheduler.workerCount(), tickMillis, budgetMillis, cpuShare);
        if (decision > active) {
            // Give workers back slowly, so a tick that is only just coping does not make the count flap.
            if (++calmSamples < CALM_SAMPLES_TO_RECOVER) {
                return;
            }
            calmSamples = 0;
        } else {
            calmSamples = 0;
        }
        if (decision != active) {
            ChunkStepScheduler.setActiveSlots(decision);
            Chunkies.LOGGER.debug(
                    "World generation workers: {} -> {} (tick {} ms of {} ms, server thread got {}% CPU)",
                    active, decision, String.format("%.1f", tickMillis), String.format("%.0f", budgetMillis), Math.round(cpuShare * 100));
        }
    }

    /**
     * @param active     workers currently allowed to run
     * @param total      size of the worker pool
     * @param tickMillis average wall-clock duration of a tick over the last second
     * @param budget     time a tick may take (50 ms at 20 TPS)
     * @param cpuShare   fraction of that wall-clock time the server thread was actually on a CPU
     * @return the number of workers allowed from now on
     */
    static int decide(int active, int total, double tickMillis, double budget, double cpuShare) {
        int floor = Math.max(1, total / 2);
        boolean tickIsTight = tickMillis > 0.8 * budget;
        boolean starved = cpuShare < 0.85;
        if (tickIsTight && starved) {
            return Math.max(floor, active - 1);
        }
        if (tickMillis < 0.6 * budget || cpuShare > 0.95) {
            return Math.min(total, active + 1);
        }
        return Math.min(total, Math.max(floor, active));
    }
}
