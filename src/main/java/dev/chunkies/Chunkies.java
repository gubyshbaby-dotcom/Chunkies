package dev.chunkies;

import com.mojang.logging.LogUtils;
import dev.chunkies.config.ChunkiesConfig;
import dev.chunkies.worldgen.ChunkStepScheduler;
import dev.chunkies.worldgen.WorkerGovernor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(Chunkies.MOD_ID)
public final class Chunkies {
    public static final String MOD_ID = "chunkies";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Chunkies(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, ChunkiesConfig.SPEC, MOD_ID + ".toml");
        modBus.addListener(this::onConfigLoad);
        modBus.addListener(this::onConfigReload);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ChunkiesCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> WorkerGovernor.tickStart());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> WorkerGovernor.tickEnd(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> logSettings());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> ChunkStepScheduler.reset());
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ChunkiesConfig.SPEC) {
            ChunkiesConfig.bake();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ChunkiesConfig.SPEC) {
            ChunkiesConfig.bake();
        }
    }

    private static void logSettings() {
        LOGGER.info(
                "Chunkies: parallel worldgen {} ({} threads, parallel structure checks {}), player ticket queue {}, "
                        + "off-thread chunk parsing {}, eager light {}, skip redundant saves {}, send boost {}",
                onOff(ChunkiesConfig.worldgenParallel),
                ChunkiesConfig.resolvedWorldgenThreads(),
                onOff(ChunkiesConfig.parallelStructureStarts),
                ChunkiesConfig.resolvedPlayerTicketConcurrency(),
                onOff(ChunkiesConfig.offThreadParsing),
                onOff(ChunkiesConfig.eagerLight),
                onOff(ChunkiesConfig.skipRedundantSaves),
                onOff(ChunkiesConfig.sendBoost));
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
