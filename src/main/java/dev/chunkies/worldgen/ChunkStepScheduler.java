package dev.chunkies.worldgen;

import dev.chunkies.Chunkies;
import dev.chunkies.config.ChunkiesConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * Runs world generation steps on a pool of worker threads.
 *
 * <p>Vanilla 1.21.1 executes almost every generation step (structures, surface, carvers, features, ...) on a
 * single "worldgen" actor, one chunk at a time per dimension; only noise and biome filling are spread over
 * the worker pool. This scheduler takes those steps off the actor and runs them concurrently, under two
 * rules that keep the work as safe as it is in vanilla:</p>
 *
 * <ul>
 *   <li>A step locks every chunk it may write to (its own chunk, plus the neighbours for features). Two steps
 *       whose areas overlap never run at the same time. All locks of a step are taken at once, so there is no
 *       lock ordering and no way to deadlock.</li>
 *   <li>Steps flagged {@linkplain StepPlan#exclusive() exclusive} run one at a time.</li>
 * </ul>
 *
 * <p>When more steps are runnable than there are free workers, they are ordered along the dependency graph of
 * chunk generation rather than by arrival: a step is as urgent as the closest-to-a-player chunk that cannot
 * be finished without it. A chunk needs structure starts from up to 11 chunks away, biomes from 3, terrain
 * from 2 and features from 1, so e.g. a structure start 9 chunks from a player is on the critical path of the
 * chunk the player stands in and runs before terrain noise 4 chunks away. The effect is that the cone of work
 * under the nearest missing chunk is finished first and terrain appears around players quickly, instead of
 * every queued chunk advancing a little at a time. Steps that have waited long are aged upwards so that
 * nothing far away (other players, force-loaded areas) can starve.</p>
 */
public final class ChunkStepScheduler {
    private static final Object MUTEX = new Object();
    /** Steps waiting for a worker slot or for their chunk locks. Guarded by {@link #MUTEX}. */
    private static final ArrayList<Step> PENDING = new ArrayList<>();
    private static int running;
    private static boolean exclusiveBusy;
    private static long sequence;

    private static volatile ThreadPoolExecutor workers;
    /** Size of the worker pool. */
    private static int slots = 1;
    /** How many of the workers may be busy right now (see {@link WorkerGovernor}). Guarded by the mutex. */
    private static int activeSlots = Integer.MAX_VALUE;

    /** A waiting step gains one chunk of urgency per this many nanoseconds. */
    private static final long AGING_NANOS_PER_CHUNK = TimeUnit.MILLISECONDS.toNanos(250);
    /** Distances beyond this are all "far away"; bounds how long aging needs to rescue a step. */
    private static final int MAX_DISTANCE = 64;
    /**
     * Stand-in distance for a step whose chunk is no longer wanted at that status (the player moved on while
     * it was queued). Such a step still has to run eventually - the chunk system considers it started - but
     * only fills otherwise idle workers, until aging lets it through after roughly half a minute.
     */
    private static final int ABANDONED_DISTANCE = MAX_DISTANCE + 120;
    /**
     * For every status: how far a chunk in that status can be from the full chunk that needs it (taken from
     * the generation pyramid, so it follows whatever the game defines).
     */
    private static final int[] REACH = computeReach();

    private static int[] computeReach() {
        List<ChunkStatus> statuses = ChunkStatus.getStatusList();
        int[] reach = new int[statuses.size()];
        ChunkStep full = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL);
        for (ChunkStatus status : statuses) {
            int radius = 0;
            try {
                radius = full.getAccumulatedRadiusOf(status);
            } catch (RuntimeException ignored) {
                // not a dependency of a full chunk
            }
            reach[status.getIndex()] = radius;
        }
        return reach;
    }

    // statistics, shown by /chunkies
    public static final AtomicLong STEPS_RUN = new AtomicLong();
    public static final AtomicInteger PEAK_PARALLEL = new AtomicInteger();

    private ChunkStepScheduler() {
    }

    private static final class Step {
        final StepDomain domain;
        final GenerationChunkHolder holder;
        final int x;
        final int z;
        final StepPlan plan;
        final Supplier<CompletableFuture<ChunkAccess>> work;
        final CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        final ChunkStatus status;
        final int reach;
        final long submittedNanos = System.nanoTime();
        long seq;
        int criticalDistance;
        int distanceVersion = -1;

        Step(StepDomain domain, GenerationChunkHolder holder, ChunkStatus status, StepPlan plan, Supplier<CompletableFuture<ChunkAccess>> work) {
            this.domain = domain;
            this.holder = holder;
            ChunkPos pos = holder.getPos();
            this.x = pos.x;
            this.z = pos.z;
            this.plan = plan;
            this.work = work;
            this.status = status;
            this.reach = REACH[status.getIndex()];
        }

        /** True if the chunk's ticket no longer asks for this status (e.g. the player left the area). */
        boolean abandoned() {
            ChunkStatus wanted = ChunkLevel.generationStatus(this.holder.getTicketLevel());
            return wanted == null || this.status.isAfter(wanted);
        }

        /**
         * Smaller runs first. Distance (in chunks) from a player to the nearest chunk that depends on this
         * step, minus one for every {@link #AGING_NANOS_PER_CHUNK} the step has been waiting.
         */
        long urgency(long now) {
            int version = this.domain.playersVersion();
            if (version != this.distanceVersion) {
                int distance = Math.min(this.domain.distanceToNearestPlayer(this.x, this.z), MAX_DISTANCE);
                this.criticalDistance = Math.max(0, distance - this.reach);
                this.distanceVersion = version;
            }
            int distance = this.abandoned() ? ABANDONED_DISTANCE : this.criticalDistance;
            return distance - (now - this.submittedNanos) / AGING_NANOS_PER_CHUNK;
        }
    }

    public static int workerCount() {
        ensureWorkers();
        return slots;
    }

    /** Workers currently allowed to run steps; never more than {@link #workerCount()}. */
    public static int activeSlots() {
        int total = workerCount();
        synchronized (MUTEX) {
            return Math.min(activeSlots, total);
        }
    }

    public static void setActiveSlots(int count) {
        List<Step> started;
        synchronized (MUTEX) {
            if (activeSlots == count) {
                return;
            }
            activeSlots = Math.max(1, count);
            started = workers != null ? startRunnable() : null;
        }
        if (started != null) {
            for (Step step : started) {
                workers.execute(() -> runLoop(step));
            }
        }
    }

    public static int pendingSteps() {
        synchronized (MUTEX) {
            return PENDING.size();
        }
    }

    public static int runningSteps() {
        synchronized (MUTEX) {
            return running;
        }
    }

    private static ThreadPoolExecutor ensureWorkers() {
        ThreadPoolExecutor pool = workers;
        if (pool == null) {
            synchronized (MUTEX) {
                pool = workers;
                if (pool == null) {
                    int count = ChunkiesConfig.resolvedWorldgenThreads();
                    AtomicInteger ids = new AtomicInteger();
                    ThreadGroup group = Thread.currentThread().getThreadGroup();
                    pool = new ThreadPoolExecutor(count, count, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                        Thread thread = new Thread(group, runnable, "Chunkies-Worldgen-" + ids.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setUncaughtExceptionHandler((t, e) -> Chunkies.LOGGER.error("Uncaught exception in {}", t.getName(), e));
                        return thread;
                    });
                    pool.allowCoreThreadTimeOut(true);
                    slots = count;
                    workers = pool;
                    Chunkies.LOGGER.debug("World generation worker pool started with {} thread(s); dependency reach per status: {}", count, java.util.Arrays.toString(REACH));
                }
            }
        }
        return pool;
    }

    /**
     * Queues a generation step. The returned future completes with the result of {@code work} once the step
     * has run.
     */
    public static CompletableFuture<ChunkAccess> submit(
            StepDomain domain,
            GenerationChunkHolder holder,
            ChunkStatus status,
            StepPlan plan,
            Supplier<CompletableFuture<ChunkAccess>> work) {
        ThreadPoolExecutor pool = ensureWorkers();
        Step step = new Step(domain, holder, status, plan, work);
        domain.inFlight.incrementAndGet();
        boolean start;
        synchronized (MUTEX) {
            step.seq = sequence++;
            // Whenever a slot is free, everything in PENDING is blocked by a lock, so the newcomer does not
            // jump any queue by starting right away.
            start = canStart(step);
            if (start) {
                acquire(step);
            } else {
                PENDING.add(step);
            }
        }
        if (start) {
            pool.execute(() -> runLoop(step));
        }
        return step.result;
    }

    private static boolean canStart(Step step) {
        if (running >= Math.min(slots, activeSlots) || (step.plan.exclusive() && exclusiveBusy)) {
            return false;
        }
        return locksFree(step);
    }

    private static boolean locksFree(Step step) {
        int r = step.plan.lockRadius();
        if (r == 0) {
            return !step.domain.locked.contains(ChunkPos.asLong(step.x, step.z));
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (step.domain.locked.contains(ChunkPos.asLong(step.x + dx, step.z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void acquire(Step step) {
        int r = step.plan.lockRadius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                step.domain.locked.add(ChunkPos.asLong(step.x + dx, step.z + dz));
            }
        }
        running++;
        if (step.plan.exclusive()) {
            exclusiveBusy = true;
        }
        int peak = PEAK_PARALLEL.get();
        if (running > peak) {
            PEAK_PARALLEL.set(running);
        }
    }

    private static void release(Step step) {
        int r = step.plan.lockRadius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                step.domain.locked.remove(ChunkPos.asLong(step.x + dx, step.z + dz));
            }
        }
        running--;
        if (step.plan.exclusive()) {
            exclusiveBusy = false;
        }
    }

    /** Starts as many pending steps as slots and locks allow, most urgent first. Caller holds the mutex. */
    private static List<Step> startRunnable() {
        List<Step> started = null;
        long now = System.nanoTime();
        int limit = Math.min(slots, activeSlots);
        while (running < limit && !PENDING.isEmpty()) {
            int best = -1;
            long bestUrgency = Long.MAX_VALUE;
            int bestLevel = Integer.MAX_VALUE;
            long bestSeq = Long.MAX_VALUE;
            for (int i = 0, n = PENDING.size(); i < n; i++) {
                Step candidate = PENDING.get(i);
                if (candidate.plan.exclusive() && exclusiveBusy) {
                    continue;
                }
                // Order: urgency along the dependency graph, then the chunk's ticket level, then arrival.
                long urgency = candidate.urgency(now);
                if (urgency > bestUrgency) {
                    continue;
                }
                int level = candidate.holder.getQueueLevel();
                if (urgency == bestUrgency && (level > bestLevel || (level == bestLevel && candidate.seq > bestSeq))) {
                    continue;
                }
                if (!locksFree(candidate)) {
                    continue;
                }
                best = i;
                bestUrgency = urgency;
                bestLevel = level;
                bestSeq = candidate.seq;
            }
            if (best < 0) {
                break;
            }
            Step step = PENDING.get(best);
            int last = PENDING.size() - 1;
            PENDING.set(best, PENDING.get(last));
            PENDING.remove(last);
            acquire(step);
            if (started == null) {
                started = new ArrayList<>(4);
            }
            started.add(step);
        }
        return started;
    }

    /** Runs a step, and then keeps going with whatever became runnable, without bouncing between threads. */
    private static void runLoop(Step first) {
        Step step = first;
        while (step != null) {
            CompletableFuture<ChunkAccess> inner;
            try {
                inner = step.work.get();
                if (inner == null) {
                    inner = CompletableFuture.failedFuture(new IllegalStateException("Generation step returned no future"));
                }
            } catch (Throwable throwable) {
                inner = CompletableFuture.failedFuture(throwable);
            }
            STEPS_RUN.incrementAndGet();
            if (!step.plan.holdUntilDone() || inner.isDone()) {
                step = finish(step, inner, true);
            } else {
                Step async = step;
                CompletableFuture<ChunkAccess> pendingInner = inner;
                inner.whenComplete((chunk, throwable) -> finish(async, pendingInner, false));
                step = null;
            }
        }
    }

    /**
     * Releases the step's locks and slot, hands newly runnable steps to the workers and publishes the result.
     *
     * @return a step the calling worker thread should continue with, or {@code null}
     */
    private static Step finish(Step step, CompletableFuture<ChunkAccess> inner, boolean continueInline) {
        List<Step> started;
        synchronized (MUTEX) {
            release(step);
            started = startRunnable();
        }
        Step next = null;
        if (started != null) {
            int from = 0;
            if (continueInline) {
                next = started.get(0);
                from = 1;
            }
            for (int i = from; i < started.size(); i++) {
                Step other = started.get(i);
                workers.execute(() -> runLoop(other));
            }
        }
        step.domain.inFlight.decrementAndGet();
        // Publish last: completing the future runs the chunk system's own callbacks on this thread.
        if (inner.isDone()) {
            publish(step, inner);
        } else {
            inner.whenComplete((chunk, throwable) -> publish(step, inner));
        }
        return next;
    }

    private static void publish(Step step, CompletableFuture<ChunkAccess> inner) {
        try {
            step.result.complete(inner.join());
        } catch (Throwable throwable) {
            Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                    ? throwable.getCause()
                    : throwable;
            step.result.completeExceptionally(cause);
        }
    }
}
