package dev.chunkies;

import com.mojang.brigadier.CommandDispatcher;
import dev.chunkies.config.ChunkiesConfig;
import dev.chunkies.loading.ChunkPreParser;
import dev.chunkies.loading.LoadingStats;
import dev.chunkies.worldgen.ChunkStepScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** {@code /chunkies}: shows what the mod is doing right now. Operators only. */
public final class ChunkiesCommand {
    private ChunkiesCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunkies")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    report(context.getSource());
                    return 1;
                }));
    }

    private static void report(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        line(source, Component.literal("Chunkies").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(" - tick %.1f ms", server.getCurrentSmoothedTickTime())).withStyle(ChatFormatting.GRAY)));

        if (ChunkiesConfig.worldgenParallel) {
            line(source, entry("World generation", String.format(
                    "%d of %d workers active, %d steps running, %d queued, %d run so far (peak %d at once)",
                    ChunkStepScheduler.activeSlots(),
                    ChunkStepScheduler.workerCount(),
                    ChunkStepScheduler.runningSteps(),
                    ChunkStepScheduler.pendingSteps(),
                    ChunkStepScheduler.STEPS_RUN.get(),
                    ChunkStepScheduler.PEAK_PARALLEL.get())));
        } else {
            line(source, entry("World generation", "parallel generation is turned off"));
        }

        line(source, entry("Chunk loading", String.format(
                "%d chunk sections decoded off the server thread (%d picked up), %d no-op light notifications skipped",
                ChunkPreParser.SECTIONS_PREPARSED.get(),
                ChunkPreParser.SECTIONS_USED.get(),
                LoadingStats.LIGHT_UPDATES_SKIPPED.sum())));

        line(source, entry("Player ticket queue", ChunkiesConfig.resolvedPlayerTicketConcurrency() + " chunks in flight (vanilla: 4)"));

        if (ChunkiesConfig.sendBoost) {
            line(source, entry("Chunk sending", String.format(
                    "client rate x%.1f, at least %.0f and at most %.0f chunks per tick",
                    ChunkiesConfig.sendRateMultiplier, ChunkiesConfig.sendMinChunksPerTick, ChunkiesConfig.sendMaxChunksPerTick)));
        } else {
            line(source, entry("Chunk sending", "vanilla rate"));
        }

        for (ServerLevel level : server.getAllLevels()) {
            line(source, entry(level.dimension().location().toString(), level.getChunkSource().getLoadedChunksCount() + " chunks loaded"));
        }
    }

    private static MutableComponent entry(String name, String value) {
        return Component.literal(name + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static void line(CommandSourceStack source, Component component) {
        source.sendSuccess(() -> component, false);
    }
}
