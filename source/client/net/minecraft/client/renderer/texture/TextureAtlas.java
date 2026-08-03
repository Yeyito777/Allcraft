package net.minecraft.client.renderer.texture;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class TextureAtlas extends AbstractTexture implements TickableTexture, Dumpable {
    private static final Logger LOGGER = LogUtils.getLogger();
    @Deprecated
    public static final Identifier LOCATION_BLOCKS = Identifier.withDefaultNamespace("textures/atlas/blocks.png");
    @Deprecated
    public static final Identifier LOCATION_ITEMS = Identifier.withDefaultNamespace("textures/atlas/items.png");
    @Deprecated
    public static final Identifier LOCATION_PARTICLES = Identifier.withDefaultNamespace("textures/atlas/particles.png");
    private List<TextureAtlasSprite> sprites = List.of();
    private final List<AllcraftRetiredAllocation> allcraftRetiredAllocations = new ArrayList<>();
    private final List<SpriteContents> allcraftRetiredContents = new ArrayList<>();
    private List<SpriteContents.AnimationState> animatedTexturesStates = List.of();
    private Map<Identifier, TextureAtlasSprite> texturesByName = Map.of();
    private @Nullable TextureAtlasSprite missingSprite;
    private final Identifier location;
    private final int maxSupportedTextureSize;
    private int width;
    private int height;
    private int maxMipLevel;
    private int mipLevelCount;
    private GpuTextureView[] mipViews = new GpuTextureView[0];
    private @Nullable GpuBuffer spriteUbos;

    public TextureAtlas(Identifier location) {
        this.location = location;
        this.maxSupportedTextureSize = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
    }

    private void createTexture(int newWidth, int newHeight, int newMipLevel) {
        LOGGER.info("Created: {}x{}x{} {}-atlas", newWidth, newHeight, newMipLevel, this.location);
        GpuDevice device = RenderSystem.getDevice();
        this.releaseTextures();
        this.texture = device.createTexture(this.location::toString, 15, GpuFormat.RGBA8_UNORM, newWidth, newHeight, 1, newMipLevel + 1);
        this.textureView = device.createTextureView(this.texture);
        this.width = newWidth;
        this.height = newHeight;
        this.maxMipLevel = newMipLevel;
        this.mipLevelCount = newMipLevel + 1;
        this.mipViews = new GpuTextureView[this.mipLevelCount];

        for (int level = 0; level <= this.maxMipLevel; level++) {
            this.mipViews[level] = device.createTextureView(this.texture, level, 1);
        }
    }

    public void upload(SpriteLoader.Preparations preparations) {
        this.createTexture(preparations.width(), preparations.height(), preparations.mipLevel());
        this.clearTextureData();
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        this.texturesByName = Map.copyOf(preparations.regions());
        this.missingSprite = this.texturesByName.get(MissingTextureAtlasSprite.getLocation());
        if (this.missingSprite == null) {
            throw new IllegalStateException("Atlas '" + this.location + "' (" + this.texturesByName.size() + " sprites) has no missing texture sprite");
        }

        this.sprites = ImmutableList.copyOf(preparations.regions().values());
        this.allcraftRebuildAnimationStates();

        this.uploadInitialContents();
        if (SharedConstants.DEBUG_DUMP_TEXTURE_ATLAS) {
            Path dumpDir = TextureUtil.getDebugTexturePath();

            try {
                Files.createDirectories(dumpDir);
                this.dumpContents(this.location, dumpDir);
            } catch (Exception e) {
                LOGGER.warn("Failed to dump atlas contents to {}", dumpDir);
            }
        }
    }

    private void uploadInitialContents() {
        GpuDevice device = RenderSystem.getDevice();
        int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
        int uboBlockSize = spriteUboSize * this.mipLevelCount;
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
        List<TextureAtlasSprite> staticSprites = this.sprites.stream().filter(s -> !s.isAnimated()).toList();
        List<GpuTextureView[]> scratchTextures = new ArrayList<>();
        ByteBuffer buffer = MemoryUtil.memAlloc(staticSprites.size() * uboBlockSize);

        for (int i = 0; i < staticSprites.size(); i++) {
            TextureAtlasSprite sprite = staticSprites.get(i);
            sprite.uploadSpriteUbo(buffer, i * uboBlockSize, this.maxMipLevel, this.width, this.height, spriteUboSize);
            GpuTexture scratchTexture = device.createTexture(
                () -> sprite.contents().name().toString(),
                5,
                GpuFormat.RGBA8_UNORM,
                sprite.contents().width(),
                sprite.contents().height(),
                1,
                this.mipLevelCount
            );
            GpuTextureView[] views = new GpuTextureView[this.mipLevelCount];

            for (int level = 0; level <= this.maxMipLevel; level++) {
                sprite.uploadFirstFrame(scratchTexture, level);
                views[level] = device.createTextureView(scratchTexture);
            }

            scratchTextures.add(views);
        }

        try (GpuBuffer ubo = device.createBuffer(() -> "SpriteAnimationInfo", 128, buffer)) {
            for (int level = 0; level < this.mipLevelCount; level++) {
                try (RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "Animate " + this.location, this.mipViews[level], Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT);

                    for (int i = 0; i < staticSprites.size(); i++) {
                        renderPass.bindTexture("Sprite", scratchTextures.get(i)[level], sampler);
                        renderPass.setUniform("SpriteAnimationInfo", ubo.slice(i * uboBlockSize + level * spriteUboSize, SpriteContents.UBO_SIZE));
                        renderPass.draw(6, 1, 0, 0);
                    }
                }
            }
        }

        for (GpuTextureView[] views : scratchTextures) {
            for (GpuTextureView view : views) {
                view.close();
                view.texture().close();
            }
        }

        MemoryUtil.memFree(buffer);
        this.uploadAnimationFrames();
    }

    @Override
    public void dumpContents(Identifier selfId, Path dir) throws IOException {
        String outputId = selfId.toDebugFileName();
        TextureUtil.writeAsPNG(dir, outputId, this.getTexture(), this.maxMipLevel, argb -> argb);
        dumpSpriteNames(dir, outputId, this.texturesByName);
    }

    private static void dumpSpriteNames(Path dir, String outputId, Map<Identifier, TextureAtlasSprite> regions) {
        Path outputPath = dir.resolve(outputId + ".txt");

        try (Writer output = Files.newBufferedWriter(outputPath)) {
            for (Entry<Identifier, TextureAtlasSprite> e : regions.entrySet().stream().sorted(Entry.comparingByKey()).toList()) {
                TextureAtlasSprite value = e.getValue();
                output.write(
                    String.format(
                        Locale.ROOT,
                        "%s\tx=%d\ty=%d\tw=%d\th=%d%n",
                        e.getKey(),
                        value.getX(),
                        value.getY(),
                        value.contents().width(),
                        value.contents().height()
                    )
                );
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write file {}", outputPath, e);
        }
    }

    public void cycleAnimationFrames() {
        if (this.texture != null) {
            for (SpriteContents.AnimationState animationState : this.animatedTexturesStates) {
                animationState.tick();
            }

            this.uploadAnimationFrames();
        }
    }

    private void uploadAnimationFrames() {
        if (this.animatedTexturesStates.stream().anyMatch(SpriteContents.AnimationState::needsToDraw)) {
            for (int level = 0; level <= this.maxMipLevel; level++) {
                try (RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "Animate " + this.location, this.mipViews[level], Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(renderPass);

                    for (SpriteContents.AnimationState animationState : this.animatedTexturesStates) {
                        if (animationState.needsToDraw()) {
                            animationState.drawToAtlas(renderPass, animationState.getDrawUbo(level));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        this.cycleAnimationFrames();
    }

    public TextureAtlasSprite getSprite(Identifier location) {
        TextureAtlasSprite result = this.texturesByName.getOrDefault(location, this.missingSprite);
        if (result == null) {
            throw new IllegalStateException("Tried to lookup sprite, but atlas is not initialized");
        } else {
            return result;
        }
    }

    /** Snapshot used for model-only rebakes; all sprite coordinates remain valid. */
    public SpriteLoader.Preparations allcraftSnapshot() {
        return new SpriteLoader.Preparations(
            this.width,
            this.height,
            this.maxMipLevel,
            this.missingSprite(),
            this.texturesByName,
            java.util.concurrent.CompletableFuture.completedFuture(null)
        );
    }

    public boolean allcraftCanReplace(TextureAtlasSprite existing, SpriteContents replacement) {
        return this.texture != null
            && this.texturesByName.get(replacement.name()) == existing
            && existing.contents().width() == replacement.width()
            && existing.contents().height() == replacement.height();
    }

    /** Changes only one existing atlas rectangle, preserving baked UVs and every chunk mesh. */
    public void allcraftReplace(TextureAtlasSprite existing, SpriteContents replacement) {
        if (!this.allcraftCanReplace(existing, replacement)) {
            throw new IllegalArgumentException("Sprite cannot be replaced in-place: " + replacement.name());
        }
        // Use the same blit path as a normal atlas upload. A direct image copy would update
        // only the sprite body and leave stale atlas padding, producing colored grid lines at
        // distance once filtering and mipmaps sample across sprite edges.
        TextureAtlasSprite uploaded = new TextureAtlasSprite(
            this.location,
            replacement,
            this.width,
            this.height,
            existing.getX(),
            existing.getY(),
            existing.allcraftPadding()
        );
        this.allcraftUploadStaticSprite(uploaded);
    }

    /**
     * Applies an atlas diff without resizing the atlas. Existing allocations become tombstones
     * when a sprite moves or is deleted, so old baked models can continue sampling valid pixels
     * until their replacement meshes are committed.
     */
    public AllcraftAtlasUpdate allcraftApply(Map<Identifier, SpriteContents> replacements, Set<Identifier> deletions) {
        if (this.texture == null || replacements.isEmpty() && deletions.isEmpty()) {
            return new AllcraftAtlasUpdate(0, 0, false, false);
        }

        Map<Identifier, TextureAtlasSprite> active = new HashMap<>(this.texturesByName);
        Set<Identifier> actualDeletions = new HashSet<>(deletions);
        actualDeletions.removeAll(replacements.keySet());
        actualDeletions.remove(MissingTextureAtlasSprite.getLocation());
        List<AllcraftPlacement> placements = new ArrayList<>();
        boolean[][] occupied = this.allcraftOccupiedGrid();
        int unit = 1 << this.maxMipLevel;
        int padding = this.allcraftDefaultPadding();
        boolean structural = false;
        boolean animationChanged = false;

        for (Entry<Identifier, SpriteContents> entry : replacements.entrySet().stream().sorted(Entry.comparingByKey()).toList()) {
            Identifier id = entry.getKey();
            SpriteContents contents = entry.getValue();
            TextureAtlasSprite existing = active.get(id);
            int x;
            int y;
            int spritePadding = existing == null ? padding : existing.allcraftPadding();
            if (existing != null && existing.contents().width() == contents.width() && existing.contents().height() == contents.height()) {
                x = existing.getX();
                y = existing.getY();
            } else {
                int allocationWidth = Mth.roundToward(contents.width() + spritePadding * 2, unit);
                int allocationHeight = Mth.roundToward(contents.height() + spritePadding * 2, unit);
                int[] slot = allcraftFindFreeSlot(occupied, allocationWidth / unit, allocationHeight / unit);
                if (slot == null) {
                    throw new AllcraftAtlasCapacityException(
                        this.location + " has no stable room for " + id + " (" + allocationWidth + "x" + allocationHeight + ")"
                    );
                }
                x = slot[0] * unit;
                y = slot[1] * unit;
                allcraftMark(occupied, slot[0], slot[1], allocationWidth / unit, allocationHeight / unit);
                structural = true;
            }
            if (existing == null) {
                structural = true;
            }
            if (existing != null && existing.isAnimated() != contents.isAnimated()) {
                animationChanged = true;
            } else if (contents.isAnimated()) {
                animationChanged = true;
            }
            placements.add(new AllcraftPlacement(id, existing, contents, x, y, spritePadding));
        }

        for (Identifier id : actualDeletions) {
            TextureAtlasSprite removed = active.remove(id);
            if (removed != null) {
                this.allcraftRetire(removed, true);
                structural = true;
                animationChanged |= removed.isAnimated();
            }
        }
        for (AllcraftPlacement placement : placements) {
            TextureAtlasSprite replacement = new TextureAtlasSprite(
                this.location,
                placement.contents,
                this.width,
                this.height,
                placement.x,
                placement.y,
                placement.padding
            );
            active.put(placement.id, replacement);
            if (placement.existing != null) {
                boolean moved = placement.existing.getX() != placement.x
                    || placement.existing.getY() != placement.y
                    || placement.existing.contents().width() != placement.contents.width()
                    || placement.existing.contents().height() != placement.contents.height();
                this.allcraftRetire(placement.existing, moved);
            }
            placement.uploaded = replacement;
        }

        this.texturesByName = Map.copyOf(active);
        this.missingSprite = this.texturesByName.get(MissingTextureAtlasSprite.getLocation());
        this.sprites = ImmutableList.copyOf(this.texturesByName.values());
        this.allcraftRebuildAnimationStates();
        for (AllcraftPlacement placement : placements) {
            if (!placement.uploaded.isAnimated()) {
                this.allcraftUploadStaticSprite(placement.uploaded);
            }
        }
        this.uploadAnimationFrames();
        return new AllcraftAtlasUpdate(placements.size(), actualDeletions.size(), structural, animationChanged);
    }

    private void allcraftUploadStaticSprite(TextureAtlasSprite uploaded) {
        GpuDevice device = RenderSystem.getDevice();
        int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
        int uboBlockSize = spriteUboSize * this.mipLevelCount;
        ByteBuffer buffer = MemoryUtil.memAlloc(uboBlockSize);
        uploaded.uploadSpriteUbo(buffer, 0, this.maxMipLevel, this.width, this.height, spriteUboSize);
        GpuTexture scratchTexture = device.createTexture(
            () -> uploaded.contents().name() + " Allcraft hot reload",
            5,
            GpuFormat.RGBA8_UNORM,
            uploaded.contents().width(),
            uploaded.contents().height(),
            1,
            this.mipLevelCount
        );
        GpuTextureView[] views = new GpuTextureView[this.mipLevelCount];
        for (int level = 0; level <= this.maxMipLevel; level++) {
            uploaded.uploadFirstFrame(scratchTexture, level);
            views[level] = device.createTextureView(scratchTexture);
        }
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
        try (GpuBuffer ubo = device.createBuffer(() -> "AllcraftSpriteHotReload", 128, buffer)) {
            for (int level = 0; level < this.mipLevelCount; level++) {
                try (RenderPass renderPass = device.createCommandEncoder()
                        .createRenderPass(() -> "Allcraft hot reload " + uploaded.contents().name(), this.mipViews[level], Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT);
                    renderPass.bindTexture("Sprite", views[level], sampler);
                    renderPass.setUniform("SpriteAnimationInfo", ubo.slice(level * spriteUboSize, SpriteContents.UBO_SIZE));
                    renderPass.draw(6, 1, 0, 0);
                }
            }
        } finally {
            for (GpuTextureView view : views) {
                if (view != null) {
                    view.close();
                }
            }
            scratchTexture.close();
            MemoryUtil.memFree(buffer);
        }
    }

    private void allcraftRebuildAnimationStates() {
        this.animatedTexturesStates.forEach(SpriteContents.AnimationState::close);
        this.animatedTexturesStates = List.of();
        if (this.spriteUbos != null) {
            this.spriteUbos.close();
            this.spriteUbos = null;
        }
        List<TextureAtlasSprite> animated = this.sprites.stream().filter(TextureAtlasSprite::isAnimated).toList();
        if (animated.isEmpty()) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        int spriteUboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
        int uboBlockSize = spriteUboSize * this.mipLevelCount;
        ByteBuffer spriteUboBuffer = MemoryUtil.memAlloc(animated.size() * uboBlockSize);
        for (int i = 0; i < animated.size(); i++) {
            animated.get(i).uploadSpriteUbo(spriteUboBuffer, i * uboBlockSize, this.maxMipLevel, this.width, this.height, spriteUboSize);
        }
        this.spriteUbos = device.createBuffer(() -> this.location + " sprite UBOs", 128, spriteUboBuffer);
        Builder<SpriteContents.AnimationState> states = ImmutableList.builder();
        for (int i = 0; i < animated.size(); i++) {
            SpriteContents.AnimationState state = animated.get(i).createAnimationState(this.spriteUbos.slice(i * uboBlockSize, uboBlockSize), spriteUboSize);
            if (state != null) {
                states.add(state);
            }
        }
        this.animatedTexturesStates = states.build();
        MemoryUtil.memFree(spriteUboBuffer);
    }

    private boolean[][] allcraftOccupiedGrid() {
        int unit = 1 << this.maxMipLevel;
        boolean[][] occupied = new boolean[this.height / unit][this.width / unit];
        for (TextureAtlasSprite sprite : this.texturesByName.values()) {
            int width = Mth.roundToward(sprite.contents().width() + sprite.allcraftPadding() * 2, unit) / unit;
            int height = Mth.roundToward(sprite.contents().height() + sprite.allcraftPadding() * 2, unit) / unit;
            allcraftMark(occupied, sprite.getX() / unit, sprite.getY() / unit, width, height);
        }
        for (AllcraftRetiredAllocation allocation : this.allcraftRetiredAllocations) {
            int width = Mth.roundToward(allocation.width + allocation.padding * 2, unit) / unit;
            int height = Mth.roundToward(allocation.height + allocation.padding * 2, unit) / unit;
            allcraftMark(occupied, allocation.x / unit, allocation.y / unit, width, height);
        }
        return occupied;
    }

    private void allcraftRetire(TextureAtlasSprite sprite, boolean preserveAllocation) {
        if (preserveAllocation) {
            this.allcraftRetiredAllocations.add(
                new AllcraftRetiredAllocation(
                    sprite.getX(), sprite.getY(), sprite.contents().width(), sprite.contents().height(), sprite.allcraftPadding()
                )
            );
        }
        this.allcraftRetiredContents.add(sprite.contents());
    }

    /**
     * Releases CPU images and stable-coordinate tombstones after every old consumer has published
     * its replacement. Until this point the rectangles remain unavailable, so an old mesh can
     * never sample pixels belonging to a newer sprite.
     */
    public void allcraftReleaseRetiredContents() {
        this.allcraftRetiredContents.forEach(SpriteContents::close);
        this.allcraftRetiredContents.clear();
    }

    /** Frees coordinate tombstones only after every compiled section uses the new model generation. */
    public void allcraftReleaseRetiredAllocations() {
        this.allcraftRetiredAllocations.clear();
    }

    private int allcraftDefaultPadding() {
        return this.sprites.isEmpty() ? 1 << this.maxMipLevel : this.sprites.getFirst().allcraftPadding();
    }

    private static int @Nullable [] allcraftFindFreeSlot(boolean[][] occupied, int width, int height) {
        for (int y = 0; y + height <= occupied.length; y++) {
            for (int x = 0; x + width <= occupied[y].length; x++) {
                boolean free = true;
                for (int yy = y; free && yy < y + height; yy++) {
                    for (int xx = x; xx < x + width; xx++) {
                        if (occupied[yy][xx]) {
                            free = false;
                            break;
                        }
                    }
                }
                if (free) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private static void allcraftMark(boolean[][] occupied, int x, int y, int width, int height) {
        for (int yy = y; yy < y + height && yy < occupied.length; yy++) {
            for (int xx = x; xx < x + width && xx < occupied[yy].length; xx++) {
                occupied[yy][xx] = true;
            }
        }
    }

    public Map<Identifier, TextureAtlasSprite> allcraftSprites() {
        return this.texturesByName;
    }

    public TextureAtlasSprite missingSprite() {
        return Objects.requireNonNull(this.missingSprite, "Atlas not initialized");
    }

    public void clearTextureData() {
        this.sprites.forEach(TextureAtlasSprite::close);
        this.allcraftReleaseRetiredContents();
        this.allcraftReleaseRetiredAllocations();
        this.sprites = List.of();
        this.animatedTexturesStates.forEach(SpriteContents.AnimationState::close);
        this.animatedTexturesStates = List.of();
        this.texturesByName = Map.of();
        this.missingSprite = null;
        if (this.spriteUbos != null) {
            this.spriteUbos.close();
            this.spriteUbos = null;
        }
    }

    @Override
    protected void releaseTextures() {
        super.releaseTextures();

        for (GpuTextureView view : this.mipViews) {
            view.close();
        }
    }

    @Override
    public void close() {
        this.clearTextureData();
        super.close();
    }

    public Identifier location() {
        return this.location;
    }

    public int maxSupportedTextureSize() {
        return this.maxSupportedTextureSize;
    }

    public int maxMipLevel() {
        return this.maxMipLevel;
    }

    int getWidth() {
        return this.width;
    }

    int getHeight() {
        return this.height;
    }

    public record AllcraftAtlasUpdate(int replacements, int deletions, boolean structural, boolean animationChanged) {
    }

    public static class AllcraftAtlasCapacityException extends RuntimeException {
        public AllcraftAtlasCapacityException(String message) {
            super(message);
        }
    }

    private static final class AllcraftPlacement {
        private final Identifier id;
        private final @Nullable TextureAtlasSprite existing;
        private final SpriteContents contents;
        private final int x;
        private final int y;
        private final int padding;
        private @Nullable TextureAtlasSprite uploaded;

        private AllcraftPlacement(
            Identifier id, @Nullable TextureAtlasSprite existing, SpriteContents contents, int x, int y, int padding
        ) {
            this.id = id;
            this.existing = existing;
            this.contents = contents;
            this.x = x;
            this.y = y;
            this.padding = padding;
        }
    }

    private record AllcraftRetiredAllocation(int x, int y, int width, int height, int padding) {
    }
}
