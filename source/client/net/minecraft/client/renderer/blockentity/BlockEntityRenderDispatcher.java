package net.minecraft.client.renderer.blockentity;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class BlockEntityRenderDispatcher implements ResourceManagerReloadListener {
    private Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> renderers = ImmutableMap.of();
    private final Font font;
    private final Supplier<EntityModelSet> entityModelSet;
    private Vec3 cameraPos;
    private final BlockModelResolver blockModelResolver;
    private final ItemModelResolver itemModelResolver;
    private final EntityRenderDispatcher entityRenderer;
    private final SpriteGetter sprites;
    private final PlayerSkinRenderCache playerSkinRenderCache;

    public BlockEntityRenderDispatcher(
        Font font,
        Supplier<EntityModelSet> entityModelSet,
        BlockModelResolver blockModelResolver,
        ItemModelResolver itemModelResolver,
        EntityRenderDispatcher entityRenderer,
        SpriteGetter sprites,
        PlayerSkinRenderCache playerSkinRenderCache
    ) {
        this.blockModelResolver = blockModelResolver;
        this.itemModelResolver = itemModelResolver;
        this.entityRenderer = entityRenderer;
        this.font = font;
        this.entityModelSet = entityModelSet;
        this.sprites = sprites;
        this.playerSkinRenderCache = playerSkinRenderCache;
    }

    public <E extends BlockEntity, S extends BlockEntityRenderState> @Nullable BlockEntityRenderer<E, S> getRenderer(E blockEntity) {
        return (BlockEntityRenderer<E, S>)this.renderers.get(blockEntity.getType());
    }

    public <E extends BlockEntity, S extends BlockEntityRenderState> @Nullable BlockEntityRenderer<E, S> getRenderer(S state) {
        return (BlockEntityRenderer<E, S>)this.renderers.get(state.blockEntityType);
    }

    /** Builds a renderer candidate first, then publishes one live dispatcher entry transactionally. */
    public synchronized <E extends BlockEntity, S extends BlockEntityRenderState> void allcraftRegister(
        BlockEntityType<? extends E> type, BlockEntityRendererProvider<E, S> provider
    ) {
        BlockEntityRendererProvider.Context context = this.createContext();
        BlockEntityRenderer<?, ?> candidate = provider.create(context);
        Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> previous = this.renderers;
        Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> next = new HashMap<>(previous);
        next.put(type, candidate);
        this.renderers = Map.copyOf(next);
        net.minecraft.allcraft.AllcraftRegistries.recordUndo(
            "restore live block-entity renderer for " + net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type),
            () -> {
                synchronized (BlockEntityRenderDispatcher.this) {
                    BlockEntityRenderDispatcher.this.renderers = previous;
                }
            }
        );
    }

    private BlockEntityRendererProvider.Context createContext() {
        return new BlockEntityRendererProvider.Context(
            this,
            this.blockModelResolver,
            this.itemModelResolver,
            this.entityRenderer,
            this.entityModelSet.get(),
            this.font,
            this.sprites,
            this.playerSkinRenderCache
        );
    }

    public void prepare(Vec3 cameraPos) {
        this.cameraPos = cameraPos;
    }

    public <E extends BlockEntity, S extends BlockEntityRenderState> @Nullable S tryExtractRenderState(
        E blockEntity, float partialTicks, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress, boolean isGloballyRendered
    ) {
        BlockEntityRenderer<E, S> renderer = this.getRenderer(blockEntity);
        if (renderer == null) {
            return null;
        }

        if (!blockEntity.hasLevel() || !blockEntity.getType().isValid(blockEntity.getBlockState())) {
            return null;
        }

        if (isGloballyRendered != renderer.shouldRenderOffScreen()) {
            return null;
        }

        if (!renderer.shouldRender(blockEntity, this.cameraPos)) {
            return null;
        }

        Vec3 cameraPosition = this.cameraPos;
        S state = renderer.createRenderState();
        renderer.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        return state;
    }

    public <S extends BlockEntityRenderState> void submit(S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        BlockEntityRenderer<?, S> renderer = this.getRenderer(state);
        if (renderer != null) {
            try {
                renderer.submit(state, poseStack, submitNodeCollector, camera);
            } catch (Throwable t) {
                CrashReport report = CrashReport.forThrowable(t, "Rendering Block Entity");
                CrashReportCategory category = report.addCategory("Block Entity Details");
                state.fillCrashReportCategory(category);
                throw new ReportedException(report);
            }
        }
    }

    @Override
    public synchronized void onResourceManagerReload(ResourceManager resourceManager) {
        BlockEntityRendererProvider.Context context = this.createContext();
        this.renderers = BlockEntityRenderers.createEntityRenderers(context);
    }
}
