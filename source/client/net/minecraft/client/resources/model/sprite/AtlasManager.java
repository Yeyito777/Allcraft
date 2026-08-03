package net.minecraft.client.resources.model.sprite;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class AtlasManager implements AutoCloseable, PreparableReloadListener, SpriteGetter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<AtlasManager.AtlasConfig> KNOWN_ATLASES = List.of(
        new AtlasManager.AtlasConfig(Sheets.ARMOR_TRIMS_SHEET, AtlasIds.ARMOR_TRIMS, false),
        new AtlasManager.AtlasConfig(Sheets.BANNER_SHEET, AtlasIds.BANNER_PATTERNS, false),
        new AtlasManager.AtlasConfig(TextureAtlas.LOCATION_BLOCKS, AtlasIds.BLOCKS, true),
        new AtlasManager.AtlasConfig(TextureAtlas.LOCATION_ITEMS, AtlasIds.ITEMS, false),
        new AtlasManager.AtlasConfig(Sheets.CHEST_SHEET, AtlasIds.CHESTS, false),
        new AtlasManager.AtlasConfig(Sheets.DECORATED_POT_SHEET, AtlasIds.DECORATED_POT, false),
        new AtlasManager.AtlasConfig(Sheets.GUI_SHEET, AtlasIds.GUI, false, Set.of(GuiMetadataSection.TYPE)),
        new AtlasManager.AtlasConfig(Sheets.MAP_DECORATIONS_SHEET, AtlasIds.MAP_DECORATIONS, false),
        new AtlasManager.AtlasConfig(Sheets.PAINTINGS_SHEET, AtlasIds.PAINTINGS, false),
        new AtlasManager.AtlasConfig(TextureAtlas.LOCATION_PARTICLES, AtlasIds.PARTICLES, false),
        new AtlasManager.AtlasConfig(Sheets.SHIELD_SHEET, AtlasIds.SHIELD_PATTERNS, false),
        new AtlasManager.AtlasConfig(Sheets.SHULKER_SHEET, AtlasIds.SHULKER_BOXES, false),
        new AtlasManager.AtlasConfig(Sheets.CELESTIAL_SHEET, AtlasIds.CELESTIALS, false)
    );
    public static final PreparableReloadListener.StateKey<AtlasManager.PendingStitchResults> PENDING_STITCH = new PreparableReloadListener.StateKey<>();
    private final Map<Identifier, AtlasManager.AtlasEntry> atlasByTexture = new HashMap<>();
    private final Map<Identifier, AtlasManager.AtlasEntry> atlasById = new HashMap<>();
    private Map<SpriteId, TextureAtlasSprite> spriteLookup = Map.of();
    private int maxMipmapLevels;

    public AtlasManager(TextureManager textureManager, int maxMipmapLevels) {
        for (AtlasManager.AtlasConfig info : KNOWN_ATLASES) {
            TextureAtlas atlasTexture = new TextureAtlas(info.textureId);
            textureManager.register(info.textureId, atlasTexture);
            AtlasManager.AtlasEntry atlasEntry = new AtlasManager.AtlasEntry(atlasTexture, info);
            this.atlasByTexture.put(info.textureId, atlasEntry);
            this.atlasById.put(info.definitionLocation, atlasEntry);
        }

        this.maxMipmapLevels = maxMipmapLevels;
    }

    public TextureAtlas getAtlasOrThrow(Identifier atlasId) {
        AtlasManager.AtlasEntry atlasEntry = this.atlasById.get(atlasId);
        if (atlasEntry == null) {
            throw new IllegalArgumentException("Invalid atlas id: " + atlasId);
        } else {
            return atlasEntry.atlas();
        }
    }

    public void forEach(BiConsumer<Identifier, TextureAtlas> output) {
        this.atlasById.forEach((atlasId, entry) -> output.accept(atlasId, entry.atlas));
    }

    public void updateMaxMipLevel(int maxMipmapLevels) {
        this.maxMipmapLevels = maxMipmapLevels;
    }

    /** Resolves and atomically applies changed, new, deleted, resized, and animated sprites. */
    public CompletableFuture<AtlasManager.HotReloadResult> allcraftReloadSprites(
        ResourceManager resourceManager,
        Set<Identifier> changedTextureResources,
        Set<Identifier> deletedTextureResources,
        boolean atlasDefinitionsChanged,
        Executor taskExecutor,
        Executor reloadExecutor
    ) {
        Set<Identifier> targetSprites = new java.util.HashSet<>();
        for (Identifier resource : changedTextureResources) {
            Identifier sprite = allcraftTextureResourceToSprite(resource);
            if (sprite != null) {
                targetSprites.add(sprite);
            }
        }
        for (Identifier resource : deletedTextureResources) {
            Identifier sprite = allcraftTextureResourceToSprite(resource);
            if (sprite != null) {
                targetSprites.add(sprite);
            }
        }
        if (targetSprites.isEmpty() && !atlasDefinitionsChanged) {
            return CompletableFuture.completedFuture(AtlasManager.HotReloadResult.EMPTY);
        }

        Set<Identifier> knownSprites = this.spriteLookup.keySet().stream().map(SpriteId::texture).collect(java.util.stream.Collectors.toSet());
        boolean needsResolutionSnapshot = atlasDefinitionsChanged || !knownSprites.containsAll(targetSprites);
        if (needsResolutionSnapshot) {
            return this.allcraftResolveAndApply(resourceManager, targetSprites, taskExecutor, reloadExecutor);
        }

        List<CompletableFuture<AtlasManager.PendingHotSprite>> loads = new ArrayList<>();
        this.spriteLookup.forEach((spriteId, existing) -> {
            if (!targetSprites.contains(spriteId.texture())) {
                return;
            }
            AtlasManager.AtlasEntry atlasEntry = this.atlasByTexture.get(spriteId.atlasLocation());
            if (atlasEntry == null) {
                return;
            }
            Identifier resourceId = allcraftSpriteToTextureResource(spriteId.texture());
            loads.add(CompletableFuture.supplyAsync(() -> {
                Resource resource = resourceManager.getResource(resourceId).orElse(null);
                if (resource == null) {
                    return new AtlasManager.PendingHotSprite(atlasEntry, spriteId.texture(), existing, null);
                }
                try {
                    SpriteContents contents = SpriteResourceLoader.create(atlasEntry.config.additionalMetadata).loadSprite(spriteId.texture(), resource);
                    if (contents != null) {
                        contents.increaseMipLevel(atlasEntry.atlas.maxMipLevel());
                    }
                    return new AtlasManager.PendingHotSprite(atlasEntry, spriteId.texture(), existing, contents);
                } catch (RuntimeException e) {
                    throw new CompletionException(e);
                }
            }, taskExecutor));
        });
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApplyAsync(unused -> {
            Map<AtlasManager.AtlasEntry, Map<Identifier, SpriteContents>> replacements = new HashMap<>();
            Map<AtlasManager.AtlasEntry, Set<Identifier>> deletions = new HashMap<>();
            for (CompletableFuture<AtlasManager.PendingHotSprite> load : loads) {
                AtlasManager.PendingHotSprite pending = load.join();
                if (pending.contents == null) {
                    deletions.computeIfAbsent(pending.atlasEntry, ignored -> new java.util.HashSet<>()).add(pending.spriteId);
                } else {
                    replacements.computeIfAbsent(pending.atlasEntry, ignored -> new HashMap<>()).put(pending.spriteId, pending.contents);
                }
            }
            return this.allcraftApplyAtlasDiffs(replacements, deletions);
        }, reloadExecutor);
    }

    private CompletableFuture<AtlasManager.HotReloadResult> allcraftResolveAndApply(
        ResourceManager resourceManager,
        Set<Identifier> targetSprites,
        Executor taskExecutor,
        Executor reloadExecutor
    ) {
        List<AtlasManager.AtlasEntry> entries = List.copyOf(this.atlasByTexture.values());
        List<CompletableFuture<SpriteLoader.Preparations>> preparations = entries.stream()
            .map(
                entry -> entry.scheduleLoad(resourceManager, taskExecutor, this.maxMipmapLevels)
                    .thenCompose(prepared -> prepared.readyForUpload().thenApply(unused -> prepared))
            )
            .toList();
        return CompletableFuture.allOf(preparations.toArray(CompletableFuture[]::new)).thenApplyAsync(unused -> {
            Map<AtlasManager.AtlasEntry, Map<Identifier, SpriteContents>> replacements = new HashMap<>();
            Map<AtlasManager.AtlasEntry, Set<Identifier>> deletions = new HashMap<>();
            for (int i = 0; i < entries.size(); i++) {
                AtlasManager.AtlasEntry atlasEntry = entries.get(i);
                SpriteLoader.Preparations prepared = preparations.get(i).join();
                Set<Identifier> expected = prepared.regions().keySet();
                Set<Identifier> current = atlasEntry.atlas.allcraftSprites().keySet();
                Set<Identifier> selected = new java.util.HashSet<>(targetSprites);
                // Directory and transformed atlas sources can map a resource path to a different
                // sprite id (particle/live.png -> allcraft:live, palette permutations, filters).
                // A prospective source snapshot therefore owns all set additions/removals even
                // when the direct texture-path heuristic cannot name the resulting sprite.
                expected.stream().filter(id -> !current.contains(id)).forEach(selected::add);
                current.stream().filter(id -> !expected.contains(id)).forEach(selected::add);
                for (TextureAtlasSprite candidate : prepared.regions().values()) {
                    TextureAtlasSprite committed = atlasEntry.atlas.allcraftSprites().get(candidate.contents().name());
                    if (committed != null && committed.contents().allcraftFingerprint() != candidate.contents().allcraftFingerprint()) {
                        selected.add(candidate.contents().name());
                    }
                }
                Map<Identifier, SpriteContents> atlasReplacements = replacements.computeIfAbsent(atlasEntry, ignored -> new HashMap<>());
                Set<Identifier> atlasDeletions = deletions.computeIfAbsent(atlasEntry, ignored -> new java.util.HashSet<>());
                for (TextureAtlasSprite candidate : prepared.regions().values()) {
                    if (selected.contains(candidate.contents().name())) {
                        atlasReplacements.put(candidate.contents().name(), candidate.contents());
                    } else {
                        candidate.close();
                    }
                }
                for (Identifier selectedId : selected) {
                    if (!expected.contains(selectedId) && atlasEntry.atlas.allcraftSprites().containsKey(selectedId)) {
                        atlasDeletions.add(selectedId);
                    }
                }
            }
            return new AtlasManager.ResolvedAtlasDiff(replacements, deletions);
        }, taskExecutor).thenApplyAsync(diff -> this.allcraftApplyAtlasDiffs(diff.replacements, diff.deletions), reloadExecutor);
    }

    private AtlasManager.HotReloadResult allcraftApplyAtlasDiffs(
        Map<AtlasManager.AtlasEntry, Map<Identifier, SpriteContents>> replacements,
        Map<AtlasManager.AtlasEntry, Set<Identifier>> deletions
    ) {
        int updated = 0;
        int deleted = 0;
        boolean structural = false;
        boolean animation = false;
        boolean modelRebake = false;
        Set<AtlasManager.AtlasEntry> appliedEntries = new java.util.HashSet<>();
        try {
            Set<AtlasManager.AtlasEntry> entries = new java.util.HashSet<>(replacements.keySet());
            entries.addAll(deletions.keySet());
            for (AtlasManager.AtlasEntry entry : entries) {
                TextureAtlas.AllcraftAtlasUpdate result = entry.atlas.allcraftApply(
                    replacements.getOrDefault(entry, Map.of()), deletions.getOrDefault(entry, Set.of())
                );
                appliedEntries.add(entry);
                updated += result.replacements();
                deleted += result.deletions();
                structural |= result.structural();
                animation |= result.animationChanged();
                modelRebake |= result.structural()
                    && (entry.config.definitionLocation.equals(AtlasIds.BLOCKS) || entry.config.definitionLocation.equals(AtlasIds.ITEMS));
            }
            this.allcraftRebuildSpriteLookup();
            return new AtlasManager.HotReloadResult(updated, deleted, structural, animation, modelRebake, false);
        } catch (TextureAtlas.AllcraftAtlasCapacityException e) {
            replacements.forEach((entry, contents) -> {
                if (!appliedEntries.contains(entry)) {
                    contents.values().forEach(SpriteContents::close);
                }
            });
            LOGGER.warn("Stable atlas reserve exhausted; requesting staged full atlas reload", e);
            return new AtlasManager.HotReloadResult(updated, deleted, true, animation, true, true);
        }
    }

    /** Called after staged consumers can no longer need the old CPU-side sprite images. */
    public void allcraftReleaseRetiredContents() {
        this.atlasByTexture.values().forEach(entry -> entry.atlas.allcraftReleaseRetiredContents());
    }

    /** Called after every compiled section has left the previous model/UV generation. */
    public void allcraftReleaseRetiredAllocations() {
        this.atlasByTexture.values().forEach(entry -> entry.atlas.allcraftReleaseRetiredAllocations());
    }

    private void allcraftRebuildSpriteLookup() {
        Map<SpriteId, TextureAtlasSprite> result = new HashMap<>();
        this.atlasByTexture.forEach(
            (atlasTexture, entry) -> entry.atlas.allcraftSprites().forEach((id, sprite) -> result.put(new SpriteId(atlasTexture, id), sprite))
        );
        this.spriteLookup = Map.copyOf(result);
    }

    private static @Nullable Identifier allcraftTextureResourceToSprite(Identifier resource) {
        String path = resource.getPath();
        if (!path.startsWith("textures/") || !path.endsWith(".png")) {
            return null;
        }
        return Identifier.fromNamespaceAndPath(resource.getNamespace(), path.substring("textures/".length(), path.length() - ".png".length()));
    }

    private static Identifier allcraftSpriteToTextureResource(Identifier sprite) {
        return Identifier.fromNamespaceAndPath(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
    }

    /** Provides current atlas coordinates to a model-only rebake without restitching or uploading any atlas. */
    public PreparableReloadListener allcraftSnapshotProvider() {
        return new PreparableReloadListener() {
            @Override
            public void prepareSharedState(PreparableReloadListener.SharedState currentReload) {
                Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> snapshots = new HashMap<>();
                AtlasManager.this.atlasById.forEach(
                    (atlasId, entry) -> snapshots.put(atlasId, CompletableFuture.completedFuture(entry.atlas.allcraftSnapshot()))
                );
                currentReload.set(
                    AtlasManager.PENDING_STITCH,
                    new AtlasManager.PendingStitchResults(List.of(), snapshots, CompletableFuture.completedFuture(null))
                );
            }

            @Override
            public CompletableFuture<Void> reload(
                PreparableReloadListener.SharedState currentReload,
                Executor taskExecutor,
                PreparableReloadListener.PreparationBarrier preparationBarrier,
                Executor reloadExecutor
            ) {
                return preparationBarrier.wait(Unit.INSTANCE).thenApply(unused -> null);
            }

            @Override
            public String getName() {
                return "AllcraftAtlasSnapshot";
            }
        };
    }

    @Override
    public void close() {
        this.spriteLookup = Map.of();
        this.atlasById.values().forEach(AtlasManager.AtlasEntry::close);
        this.atlasById.clear();
        this.atlasByTexture.clear();
    }

    @Override
    public TextureAtlasSprite get(SpriteId sprite) {
        TextureAtlasSprite result = this.spriteLookup.get(sprite);
        if (result != null) {
            return result;
        } else {
            Identifier atlasTextureId = sprite.atlasLocation();
            AtlasManager.AtlasEntry atlasEntry = this.atlasByTexture.get(atlasTextureId);
            if (atlasEntry == null) {
                throw new IllegalArgumentException("Invalid atlas texture id: " + atlasTextureId);
            } else {
                return atlasEntry.atlas().missingSprite();
            }
        }
    }

    @Override
    public void prepareSharedState(PreparableReloadListener.SharedState currentReload) {
        int atlasCount = this.atlasById.size();
        List<AtlasManager.PendingStitch> pendingStitches = new ArrayList<>(atlasCount);
        Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> pendingStitchById = new HashMap<>(atlasCount);
        List<CompletableFuture<?>> readyForUploads = new ArrayList<>(atlasCount);
        this.atlasById.forEach((atlasId, atlasEntry) -> {
            CompletableFuture<SpriteLoader.Preparations> stitchingDone = new CompletableFuture<>();
            pendingStitchById.put(atlasId, stitchingDone);
            pendingStitches.add(new AtlasManager.PendingStitch(atlasEntry, stitchingDone));
            readyForUploads.add(stitchingDone.thenCompose(SpriteLoader.Preparations::readyForUpload));
        });
        CompletableFuture<?> allReadyForUploads = CompletableFuture.allOf(readyForUploads.toArray(CompletableFuture[]::new));
        currentReload.set(PENDING_STITCH, new AtlasManager.PendingStitchResults(pendingStitches, pendingStitchById, allReadyForUploads));
    }

    @Override
    public CompletableFuture<Void> reload(
        PreparableReloadListener.SharedState currentReload,
        Executor taskExecutor,
        PreparableReloadListener.PreparationBarrier preparationBarrier,
        Executor reloadExecutor
    ) {
        AtlasManager.PendingStitchResults pendingStitches = currentReload.get(PENDING_STITCH);
        ResourceManager resourceManager = currentReload.resourceManager();
        pendingStitches.pendingStitches
            .forEach(pending -> pending.entry.scheduleLoad(resourceManager, taskExecutor, this.maxMipmapLevels).whenComplete((value, throwable) -> {
                if (value != null) {
                    pending.preparations.complete(value);
                } else {
                    pending.preparations.completeExceptionally(throwable);
                }
            }));
        return pendingStitches.allReadyToUpload
            .thenCompose(preparationBarrier::wait)
            .thenAcceptAsync(unused -> this.updateSpriteMaps(pendingStitches), reloadExecutor);
    }

    private void updateSpriteMaps(AtlasManager.PendingStitchResults pendingStitches) {
        this.spriteLookup = pendingStitches.joinAndUpload();
        Map<Identifier, TextureAtlasSprite> globalSpriteLookup = new HashMap<>();
        this.spriteLookup
            .forEach(
                (id, sprite) -> {
                    if (!id.texture().equals(MissingTextureAtlasSprite.getLocation())) {
                        TextureAtlasSprite previous = globalSpriteLookup.putIfAbsent(id.texture(), sprite);
                        if (previous != null) {
                            LOGGER.warn(
                                "Duplicate sprite {} from atlas {}, already defined in atlas {}. This will be rejected in a future version",
                                id.texture(),
                                id.atlasLocation(),
                                previous.atlasLocation()
                            );
                        }
                    }
                }
            );
    }

    public record AtlasConfig(Identifier textureId, Identifier definitionLocation, boolean createMipmaps, Set<MetadataSectionType<?>> additionalMetadata) {
        public AtlasConfig(Identifier textureId, Identifier definitionLocation, boolean createMipmaps) {
            this(textureId, definitionLocation, createMipmaps, Set.of());
        }
    }

    private record AtlasEntry(TextureAtlas atlas, AtlasManager.AtlasConfig config) implements AutoCloseable {
        @Override
        public void close() {
            this.atlas.close();
        }

        private CompletableFuture<SpriteLoader.Preparations> scheduleLoad(ResourceManager resourceManager, Executor executor, int maxMipmapLevels) {
            return SpriteLoader.create(this.atlas)
                .loadAndStitch(
                    resourceManager, this.config.definitionLocation, this.config.createMipmaps ? maxMipmapLevels : 0, executor, this.config.additionalMetadata
                );
        }
    }

    private record PendingStitch(AtlasManager.AtlasEntry entry, CompletableFuture<SpriteLoader.Preparations> preparations) {
        public void joinAndUpload(Map<SpriteId, TextureAtlasSprite> result) {
            SpriteLoader.Preparations preparations = this.preparations.join();
            this.entry.atlas.upload(preparations);
            preparations.regions().forEach((spriteId, spriteContents) -> result.put(new SpriteId(this.entry.config.textureId, spriteId), spriteContents));
        }
    }

    public record HotReloadResult(
        int updatedSprites,
        int deletedSprites,
        boolean structural,
        boolean animationChanged,
        boolean modelRebakeRequired,
        boolean requiresFullAtlas
    ) {
        private static final AtlasManager.HotReloadResult EMPTY = new AtlasManager.HotReloadResult(0, 0, false, false, false, false);
    }

    private record PendingHotSprite(
        AtlasManager.AtlasEntry atlasEntry, Identifier spriteId, TextureAtlasSprite existing, @Nullable SpriteContents contents
    ) {
        private void closeContents() {
            if (this.contents != null) {
                this.contents.close();
            }
        }
    }

    private record ResolvedAtlasDiff(
        Map<AtlasManager.AtlasEntry, Map<Identifier, SpriteContents>> replacements,
        Map<AtlasManager.AtlasEntry, Set<Identifier>> deletions
    ) {
    }

    public static class PendingStitchResults {
        private final List<AtlasManager.PendingStitch> pendingStitches;
        private final Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> stitchFuturesById;
        private final CompletableFuture<?> allReadyToUpload;

        private PendingStitchResults(
            List<AtlasManager.PendingStitch> pendingStitches,
            Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> stitchFuturesById,
            CompletableFuture<?> allReadyToUpload
        ) {
            this.pendingStitches = pendingStitches;
            this.stitchFuturesById = stitchFuturesById;
            this.allReadyToUpload = allReadyToUpload;
        }

        public Map<SpriteId, TextureAtlasSprite> joinAndUpload() {
            Map<SpriteId, TextureAtlasSprite> result = new HashMap<>();
            this.pendingStitches.forEach(pendingStitch -> pendingStitch.joinAndUpload(result));
            return result;
        }

        public CompletableFuture<SpriteLoader.Preparations> get(Identifier atlasId) {
            return Objects.requireNonNull(this.stitchFuturesById.get(atlasId));
        }
    }
}
