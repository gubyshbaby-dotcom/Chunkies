package dev.chunkies.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import dev.chunkies.config.ChunkiesConfig;
import dev.chunkies.loading.ChunkPreParser;
import dev.chunkies.worldgen.ChunkStepScheduler;
import dev.chunkies.worldgen.StepDomain;
import dev.chunkies.worldgen.StepPlan;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private PlayerMap playerMap;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    protected abstract ChunkGenerator generator();

    @Unique
    private volatile Codec<PalettedContainerRO<Holder<Biome>>> chunkies$biomeCodec;

    @Unique
    private final StepDomain chunkies$steps = new StepDomain();

    /**
     * Vanilla runs the generation step right here, on the single thread that drives the whole chunk
     * pipeline of the dimension. Hand it to the worker pool instead; the chunk system already copes with
     * steps that finish later (noise and biome filling have always worked that way).
     */
    @WrapOperation(
            method = "applyStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/status/ChunkStep;apply(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkAccess> chunkies$runStepOnWorkers(
            ChunkStep step,
            WorldGenContext context,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original,
            @Local(argsOnly = true) GenerationChunkHolder holder) {
        StepPlan plan = ChunkiesConfig.worldgenParallel ? StepPlan.of(step, chunk) : null;
        if (plan == null) {
            return original.call(step, context, cache, chunk);
        }
        return ChunkStepScheduler.submit(this.chunkies$steps, holder, step.targetStatus(), plan, () -> {
            try {
                return original.call(step, context, cache, chunk);
            } catch (Throwable throwable) {
                // Same report vanilla builds when a step fails inline; the chunk holder turns the failed
                // future into a server crash with this report attached.
                ChunkPos pos = holder.getPos();
                CrashReport report = CrashReport.forThrowable(throwable, "Exception generating new chunk");
                CrashReportCategory category = report.addCategory("Chunk to be generated");
                category.setDetail("Status being generated", () -> step.targetStatus().getName());
                category.setDetail("Location", String.format(Locale.ROOT, "%d,%d", pos.x, pos.z));
                category.setDetail("Position hash", ChunkPos.asLong(pos.x, pos.z));
                category.setDetail("Generator", this.generator());
                throw new ReportedException(report);
            }
        });
    }

    /**
     * A chunk's NBT has just been read from disk and upgraded on a background thread; decode its palettes
     * there too, before the server thread gets to it.
     */
    @ModifyExpressionValue(
            method = "scheduleChunkLoad",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> chunkies$preParseOffThread(CompletableFuture<Optional<CompoundTag>> read) {
        if (!ChunkiesConfig.offThreadParsing) {
            return read;
        }
        return read.thenApply(tag -> {
            if (tag.isPresent()) {
                Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = this.chunkies$biomeCodec;
                if (biomeCodec == null) {
                    biomeCodec = ChunkSerializerAccessor.chunkies$makeBiomeCodec(this.level.registryAccess().registryOrThrow(Registries.BIOME));
                    this.chunkies$biomeCodec = biomeCodec;
                }
                Codec<PalettedContainer<BlockState>> blockCodec = ChunkSerializerAccessor.chunkies$blockStateCodec();
                ChunkPreParser.preParse(tag.get(), blockCodec, biomeCodec);
            }
            return tag;
        });
    }

    /**
     * Chunks stay clean after loading (see ChunkSerializerMixin), so a chunk that the data fixer just brought
     * up from an older game version has to ask for a save explicitly - otherwise it would be upgraded again on
     * every load. "shouldSave" is the flag vanilla itself uses for that.
     */
    @WrapMethod(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")
    private CompoundTag chunkies$resaveUpgradedChunks(CompoundTag tag, Operation<CompoundTag> original) {
        int version = ChunkStorage.getVersion(tag);
        CompoundTag upgraded = original.call(tag);
        if (ChunkiesConfig.skipRedundantSaves
                && version != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            upgraded.putBoolean("shouldSave", true);
        }
        return upgraded;
    }

    /** Keeps the scheduler's idea of where the players are up to date (once per tick, server thread). */
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void chunkies$trackPlayers(CallbackInfo ci) {
        this.chunkies$steps.updatePlayers(this.playerMap.getAllPlayers());
    }

    /** The server waits for this to turn false before it saves and shuts down. */
    @ModifyReturnValue(method = "hasWork", at = @At("RETURN"))
    private boolean chunkies$pendingStepsAreWork(boolean original) {
        return original || this.chunkies$steps.hasWork();
    }
}
