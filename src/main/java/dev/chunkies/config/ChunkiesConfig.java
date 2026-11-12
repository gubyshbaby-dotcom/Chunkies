package dev.chunkies.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration of the mod ({@code config/chunkies.toml}).
 *
 * <p>Mixins never touch the {@link ModConfigSpec} values directly: they read the plain static fields below,
 * which start out with the defaults and are refreshed by {@link #bake()} whenever the config file is
 * (re)loaded. That keeps hot paths free of config lookups and makes the mixins safe to run even before the
 * config has been loaded.</p>
 */
public final class ChunkiesConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue PLAYER_TICKET_CONCURRENCY;
    private static final ModConfigSpec.BooleanValue WORLDGEN_PARALLEL;
    private static final ModConfigSpec.IntValue WORLDGEN_THREADS;
    private static final ModConfigSpec.BooleanValue PARALLEL_STRUCTURE_STARTS;
    private static final ModConfigSpec.BooleanValue YIELD_TO_SERVER_THREAD;
    private static final ModConfigSpec.BooleanValue OFF_THREAD_PARSING;
    private static final ModConfigSpec.BooleanValue EAGER_LIGHT;
    private static final ModConfigSpec.BooleanValue SKIP_REDUNDANT_SAVES;
    private static final ModConfigSpec.BooleanValue SEND_BOOST;
    private static final ModConfigSpec.DoubleValue SEND_RATE_MULTIPLIER;
    private static final ModConfigSpec.DoubleValue SEND_MIN_CHUNKS_PER_TICK;
    private static final ModConfigSpec.DoubleValue SEND_MAX_CHUNKS_PER_TICK;

    // ---- baked values (read by mixins) ----
    public static volatile int playerTicketConcurrency = 0;
    public static volatile boolean worldgenParallel = true;
    public static volatile int worldgenThreads = 0;
    public static volatile boolean parallelStructureStarts = true;
    public static volatile boolean yieldToServerThread = true;
    public static volatile boolean offThreadParsing = true;
    public static volatile boolean eagerLight = true;
    public static volatile boolean skipRedundantSaves = true;
    public static volatile boolean sendBoost = true;
    public static volatile float sendRateMultiplier = 2.0F;
    public static volatile float sendMinChunksPerTick = 8.0F;
    public static volatile float sendMaxChunksPerTick = 128.0F;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("How chunks around players are queued for loading.").push("tickets");
        PLAYER_TICKET_CONCURRENCY = b
                .comment(
                        "How many player-requested chunks may be in the loading/generation pipeline at the same time.",
                        "Vanilla hard-codes 4, which makes chunk loading wait on thread hand-offs instead of using the CPU.",
                        "Chunks are still handed out nearest-to-the-player first, so larger values load the same area,",
                        "just with more of it in flight at once.",
                        "0 = automatic (32 per world generation thread, at least 64). Requires a server restart.")
                .defineInRange("playerTicketConcurrency", 0, 0, 65536);
        b.pop();

        b.comment("Generation of new chunks.").push("worldgen");
        WORLDGEN_PARALLEL = b
                .comment(
                        "Run the world generation steps that vanilla executes on a single thread (structure references,",
                        "surface, carvers, features) on several threads at once. Chunks that are close enough to touch",
                        "each other are never worked on at the same time, and structure layout and mob spawning still",
                        "run one at a time, so the result is the same terrain - it is just produced faster.",
                        "Turn this off if a world generation mod misbehaves with it.")
                .define("parallel", true);
        PARALLEL_STRUCTURE_STARTS = b
                .comment(
                        "Also check in parallel where structures may start (spacing, biome and terrain height tests).",
                        "With many structure mods this is what delays the first chunks after arriving in new terrain.",
                        "The pieces of a structure are still laid out strictly one structure at a time.",
                        "Only has an effect when 'parallel' is on. Turn it off first if a structure mod misbehaves.")
                .define("parallelStructureStarts", true);
        YIELD_TO_SERVER_THREAD = b
                .comment(
                        "When the game tick runs out of time because the server thread is not getting a CPU core,",
                        "temporarily use fewer world generation workers (never less than half) until it recovers.",
                        "Has no effect on machines with spare cores.")
                .define("yieldToServerThread", true);
        WORLDGEN_THREADS = b
                .comment(
                        "Number of world generation worker threads. 0 = automatic (CPU cores - 1).",
                        "Requires a server restart.")
                .defineInRange("threads", 0, 0, 256);
        b.pop();

        b.comment("Loading of chunks that already exist on disk.").push("loading");
        OFF_THREAD_PARSING = b
                .comment(
                        "Decode the block and biome data of loaded chunks on background threads instead of the server",
                        "thread (which is where 1.21.1 does it). Takes the largest piece of chunk-loading work off the",
                        "thread that also has to run the game tick.")
                .define("offThreadParsing", true);
        EAGER_LIGHT = b
                .comment(
                        "Let the lighting thread start working as soon as it receives tasks instead of waiting for the",
                        "server thread to be idle. Chunks no longer stall on lighting while the server is busy ticking.")
                .define("eagerLightScheduling", true);
        SKIP_REDUNDANT_SAVES = b
                .comment(
                        "Vanilla marks every chunk as modified while loading it, so each chunk a player merely walks",
                        "past is serialized and written to disk again when it unloads (on the server thread).",
                        "With this on, a loaded chunk is only saved if something in it actually changed.",
                        "Chunks upgraded from an older game version are still re-saved once.")
                .define("skipRedundantSaves", true);
        b.pop();

        b.comment(
                        "Sending of ready chunks to players. Vanilla sends as many chunks per tick as the player's client",
                        "asked for (the client budgets only a few milliseconds per tick for this) and never more than 64.")
                .push("sending");
        SEND_BOOST = b
                .comment("Master switch for the options below. Off = vanilla send rate.")
                .define("enabled", true);
        SEND_RATE_MULTIPLIER = b
                .comment(
                        "The chunk rate requested by the client is multiplied by this. Higher values fill the view",
                        "distance sooner but cost the client more frame time while chunks are streaming in.")
                .defineInRange("rateMultiplier", 2.0, 1.0, 64.0);
        SEND_MIN_CHUNKS_PER_TICK = b
                .comment("Never send fewer chunks per tick than this (if they are ready), whatever the client asks for.")
                .defineInRange("minChunksPerTick", 8.0, 0.01, 1024.0);
        SEND_MAX_CHUNKS_PER_TICK = b
                .comment("Upper limit of chunks sent to one player per tick (vanilla: 64).")
                .defineInRange("maxChunksPerTick", 128.0, 1.0, 4096.0);
        b.pop();

        SPEC = b.build();
    }

    private ChunkiesConfig() {
    }

    /** Copies the loaded config values into the static fields used by the mixins. */
    public static void bake() {
        playerTicketConcurrency = PLAYER_TICKET_CONCURRENCY.get();
        worldgenParallel = WORLDGEN_PARALLEL.get();
        worldgenThreads = WORLDGEN_THREADS.get();
        parallelStructureStarts = PARALLEL_STRUCTURE_STARTS.get();
        yieldToServerThread = YIELD_TO_SERVER_THREAD.get();
        offThreadParsing = OFF_THREAD_PARSING.get();
        eagerLight = EAGER_LIGHT.get();
        skipRedundantSaves = SKIP_REDUNDANT_SAVES.get();
        sendBoost = SEND_BOOST.get();
        sendRateMultiplier = SEND_RATE_MULTIPLIER.get().floatValue();
        sendMinChunksPerTick = SEND_MIN_CHUNKS_PER_TICK.get().floatValue();
        sendMaxChunksPerTick = SEND_MAX_CHUNKS_PER_TICK.get().floatValue();
    }

    /** Number of world generation worker threads after resolving "automatic". */
    public static int resolvedWorldgenThreads() {
        int configured = worldgenThreads;
        return configured > 0 ? configured : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** Width of the player ticket throttler after resolving "automatic". */
    public static int resolvedPlayerTicketConcurrency() {
        int configured = playerTicketConcurrency;
        if (configured > 0) {
            return configured;
        }
        // Enough chunks in flight to keep every worker fed (measured: ~32 per worker); more only blurs the
        // nearest-first order the throttler provides.
        return Math.min(512, Math.max(64, 32 * resolvedWorldgenThreads()));
    }
}
