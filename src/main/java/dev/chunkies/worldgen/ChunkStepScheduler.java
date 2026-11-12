package dev.chunkies.worldgen;

import dev.chunkies.Chunkies;
import dev.chunkies.config.ChunkiesConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * every queued chunk advancing a little at a time. Waiting slowly raises a step's urgency, so that nothing
 * far away (force-loaded areas, portals) can starve.</p>
 *
 * <p>Thousands of steps can be queued at once (one chunk alone asks for structure starts in a 23x23 area), so
 * the queue is a heap, and a step that cannot start because of a lock is parked on that lock until it is
 * released instead of being looked at again and again.</p>
 */
public final class ChunkStepScheduler {
    private static final Object MUTEX = new Object();

    // ---- everything below is guarded by MUTEX unless noted ----

    /** Steps that are free to start as far as we know, most urgent first. */
    private static PriorityQueue<Step> ready = new PriorityQueue<>(1024);
    /** Exclusive steps waiting for the running exclusive step to finish. */
    private static final ArrayList<Step> EXCLUSIVE_WAITERS = new ArrayList<>();
    /** Number of steps parked on a chunk lock or in {@link #EXCLUSIVE_WAITERS}. */
    private static int parked;
    private static int running;
    private static boolean exclusiveBusy;
    private static long sequence;
    /** Value of {@link #priorityEpoch} the keys in {@link #ready} were computed for. */
    private static int readyEpoch;
    private static long lastRekeyNanos;

    private static volatile ThreadPoolExecutor workers;
    /** Size of the worker pool. */
    private static int slots = 1;
    /** How many of the workers may be busy right now (see {@link WorkerGovernor}). */
    private static int activeSlots = Integer.MAX_VALUE;

    /** Bumped whenever urgencies may have changed (players moved, tickets changed). Not guarded. */
    private static final AtomicInteger PRIORITY_EPOCH = new AtomicInteger();

    /** A waiting step gains one chunk of urgency per this many nanoseconds. */
    private static final long AGING_NANOS_PER_CHUNK = TimeUnit.SECONDS.toNanos(2);
    /** Urgencies are refreshed at most this often, however much players move. */
    private static final long MIN_REKEY_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    /** Distances beyond this are all "far away"; bounds how long aging needs to rescue a step. */
    private static final int MAX_DISTANCE = 64;
    /**
     * Stand-in distance for a step whose chunk is no longer wanted at that status (the player moved on while
     * it was queued). Such a step still has to run eventually - the chunk system considers it started - but
     * it only fills otherwise idle workers, until aging lets it through after a few minutes.
     */
    private static final int ABANDONED_DISTANCE = MAX_DISTANCE + 16;
    /**
     * For every status: how far a chunk in that status can be from the full chunk that needs it (taken from
     * the generation pyramid, so it follows whatever the game defines).
     */
    private static final int[] REACH = computeReach();

    // statistics, shown by /chunkies (not guarded)
    public static final AtomicLong STEPS_RUN = new AtomicLong();
    public static final AtomicInteger PEAK_PARALLEL = new AtomicInteger();

    private ChunkStepScheduler() {
    }

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

    static final class Step implements Comparable<Step> {
        final StepDomain domain;
        final GenerationChunkHolder holder;
        final int x;
        final int z;
        final ChunkStatus status;
        final StepPlan plan;
        final Supplier<CompletableFuture<ChunkAccess>> work;
        final CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        final int reach;
        final long submittedNanos = System.nanoTime();
        long seq;
        /** Ordering key, see {@link #computeKey()}. Only changes while the step is outside the heap. */
        long key;

        Step(StepDomain domain, GenerationChunkHolder holder, ChunkStatus status, StepPlan plan, Supplier<CompletableFuture<ChunkAccess>> work) {
            this.domain = domain;
            this.holder = holder;
            ChunkPos pos = holder.getPos();
            this.x = pos.x;
            this.z = pos.z;
            this.status = status;
            this.plan = plan;
            this.work = work;
            this.reach = REACH[status.getIndex()];
        }

        /** True if the chunk's ticket no longer asks for this status (e.g. the player left the area). */
        private boolean abandoned() {
            ChunkStatus wanted = ChunkLevel.generationStatus(this.holder.getTicketLevel());
            return wanted == null || this.status.isAfter(wanted);
        }

        /**
         * Smaller keys run first. The urgency of a step at time {@code t} is its critical distance (chunks
         * between a player and the nearest chunk that depends on this step) minus one for every
         * {@link #AGING_NANOS_PER_CHUNK} it has waited. Comparing two steps at the same {@code t}, the
         * {@code t} cancels out, which leaves a key that does not change while a step sits in the heap.
         */
        void computeKey() {
            int distance;
            if (this.abandoned()) {
                distance = ABANDONED_DISTANCE;
            } else {
                distance = Math.max(0, Math.min(this.domain.distanceToNearestPlayer(this.x, this.z), MAX_DISTANCE) - this.reach);
            }
            this.key = distance * AGING_NANOS_PER_CHUNK + this.submittedNanos;
        }

        @Override
        public int compareTo(Step other) {
            // System.nanoTime() values are only comparable by difference.
            long diff = this.key - other.key;
            if (diff != 0L) {
                return diff < 0L ? -1 : 1;
            }
            return Long.compare(this.seq, other.seq);
        }
    }

    /** Tells the scheduler that urgencies may have changed (players moved, tickets came or went). */
    public static void prioritiesChanged() {
        PRIORITY_EPOCH.incrementAndGet();
    }

    /**
     * Drops everything that is still queued. Called once a server has fully stopped: by then the chunk system
     * has already waited for all generation work, so anything left belongs to a world that no longer exists
     * (this matters in single player, where the next world starts in the same JVM).
     */
    public static void reset() {
        synchronized (MUTEX) {
            ready.clear();
            EXCLUSIVE_WAITERS.clear();
            parked = 0;
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
        dispatch(started, 0);
    }

    public static int pendingSteps() {
        synchronized (MUTEX) {
            return ready.size() + parked;
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
        ensureWorkers();
        Step step = new Step(domain, holder, status, plan, work);
        domain.inFlight.incrementAndGet();
        step.computeKey();
        List<Step> started;
        synchronized (MUTEX) {
            step.seq = sequence++;
            ready.add(step);
            started = startRunnable();
        }
        dispatch(started, 0);
        return step.result;
    }

    private static void dispatch(List<Step> started, int from) {
        if (started == null) {
            return;
        }
        for (int i = from; i < started.size(); i++) {
            Step step = started.get(i);
            workers.execute(() -> runLoop(step));
        }
    }

    /** Returns a locked chunk position inside the step's lock area, or {@link Long#MIN_VALUE} if it is all free. */
    private static long findBlocker(Step step) {
        int r = step.plan.lockRadius();
        if (r == 0) {
            long pos = ChunkPos.asLong(step.x, step.z);
            return step.domain.locked.contains(pos) ? pos : Long.MIN_VALUE;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long pos = ChunkPos.asLong(step.x + dx, step.z + dz);
                if (step.domain.locked.contains(pos)) {
                    return pos;
                }
            }
        }
        return Long.MIN_VALUE;
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
        if (running > PEAK_PARALLEL.get()) {
            PEAK_PARALLEL.set(running);
        }
    }

    /** Frees the step's locks and slot and puts everything that was parked on them back into the heap. */
    private static void release(Step step) {
        int r = step.plan.lockRadius();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long pos = ChunkPos.asLong(step.x + dx, step.z + dz);
                step.domain.locked.remove(pos);
                ArrayList<Step> waiters = step.domain.waiters.remove(pos);
                if (waiters != null) {
                    unpark(waiters);
                }
            }
        }
        running--;
        if (step.plan.exclusive()) {
            exclusiveBusy = false;
            if (!EXCLUSIVE_WAITERS.isEmpty()) {
                unpark(EXCLUSIVE_WAITERS);
                EXCLUSIVE_WAITERS.clear();
            }
        }
    }

    private static void unpark(ArrayList<Step> steps) {
        for (int i = 0, n = steps.size(); i < n; i++) {
            Step step = steps.get(i);
            step.computeKey();
            ready.add(step);
        }
        parked -= steps.size();
    }

    /** Recomputes the keys of everything in the heap after players moved (rate limited). */
    private static void rekeyIfNeeded() {
        int epoch = PRIORITY_EPOCH.get();
        if (epoch == readyEpoch || ready.isEmpty()) {
            readyEpoch = epoch;
            return;
        }
        long now = System.nanoTime();
        if (now - lastRekeyNanos < MIN_REKEY_INTERVAL_NANOS) {
            return;
        }
        lastRekeyNanos = now;
        readyEpoch = epoch;
        ArrayList<Step> steps = new ArrayList<>(ready);
        for (int i = 0, n = steps.size(); i < n; i++) {
            steps.get(i).computeKey();
        }
        ready = new PriorityQueue<>(steps); // heapifies in O(n)
    }

    /** Starts as many steps as slots and locks allow, most urgent first. Caller holds the mutex. */
    private static List<Step> startRunnable() {
        int limit = Math.min(slots, activeSlots);
        if (running >= limit || ready.isEmpty()) {
            return null;
        }
        rekeyIfNeeded();
        List<Step> started = null;
        while (running < limit) {
            Step step = ready.poll();
            if (step == null) {
                break;
            }
            if (step.plan.exclusive() && exclusiveBusy) {
                EXCLUSIVE_WAITERS.add(step);
                parked++;
                continue;
            }
            long blocker = findBlocker(step);
            if (blocker != Long.MIN_VALUE) {
                ArrayList<Step> waiters = step.domain.waiters.get(blocker);
                if (waiters == null) {
                    waiters = new ArrayList<>(2);
                    step.domain.waiters.put(blocker, waiters);
                }
                waiters.add(step);
                parked++;
                continue;
            }
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
            if (continueInline) {
                next = started.get(0);
                dispatch(started, 1);
            } else {
                dispatch(started, 0);
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
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
            step.result.completeExceptionally(cause);
        }
    }
}
