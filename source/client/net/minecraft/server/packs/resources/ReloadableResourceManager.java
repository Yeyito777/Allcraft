package net.minecraft.server.packs.resources;

import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.Unit;
import org.slf4j.Logger;

public class ReloadableResourceManager implements AutoCloseable, ResourceManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private CloseableResourceManager resources;
    private final List<PreparableReloadListener> listeners = Lists.newArrayList();
    private final PackType type;

    public ReloadableResourceManager(PackType type) {
        this.type = type;
        this.resources = new MultiPackResourceManager(type, List.of());
    }

    @Override
    public void close() {
        this.resources.close();
    }

    public void registerReloadListener(PreparableReloadListener listener) {
        this.listeners.add(listener);
    }

    public ReloadInstance createReload(
        Executor backgroundExecutor, Executor mainThreadExecutor, CompletableFuture<Unit> initialTask, List<PackResources> resourcePacks
    ) {
        LOGGER.info("Reloading ResourceManager: {}", LogUtils.defer(() -> resourcePacks.stream().map(PackResources::packId).collect(Collectors.joining(", "))));
        this.replacePacks(resourcePacks);
        return SimpleReloadInstance.create(this.resources, this.listeners, backgroundExecutor, mainThreadExecutor, initialTask, LOGGER.isDebugEnabled());
    }

    /** Replaces only the resource lookup stack; callers can then update affected consumers incrementally. */
    public void allcraftReplacePacks(List<PackResources> resourcePacks) {
        LOGGER.info(
            "Updating ResourceManager packs: {}",
            LogUtils.defer(() -> resourcePacks.stream().map(PackResources::packId).collect(Collectors.joining(", ")))
        );
        this.replacePacks(resourcePacks);
    }

    /** Runs a dependency-selected listener set against the already-installed resource stack. */
    public ReloadInstance allcraftCreateReload(
        Executor backgroundExecutor,
        Executor mainThreadExecutor,
        CompletableFuture<Unit> initialTask,
        List<PreparableReloadListener> selectedListeners
    ) {
        return SimpleReloadInstance.create(
            this.resources, selectedListeners, backgroundExecutor, mainThreadExecutor, initialTask, LOGGER.isDebugEnabled()
        );
    }

    /** Returns registered listeners of the requested types in vanilla registration order. */
    public List<PreparableReloadListener> allcraftListeners(Class<?>... types) {
        return this.listeners.stream().filter(listener -> {
            for (Class<?> type : types) {
                if (type.isInstance(listener)) {
                    return true;
                }
            }
            return false;
        }).toList();
    }

    /** Full-listener fallback against the already-installed resource stack. */
    public ReloadInstance allcraftCreateFullReload(
        Executor backgroundExecutor, Executor mainThreadExecutor, CompletableFuture<Unit> initialTask
    ) {
        return this.allcraftCreateReload(backgroundExecutor, mainThreadExecutor, initialTask, this.listeners);
    }

    private void replacePacks(List<PackResources> resourcePacks) {
        CloseableResourceManager replacement = new MultiPackResourceManager(this.type, resourcePacks);
        CloseableResourceManager previous = this.resources;
        this.resources = replacement;
        previous.close();
    }

    @Override
    public Optional<Resource> getResource(Identifier location) {
        return this.resources.getResource(location);
    }

    @Override
    public Set<String> getNamespaces() {
        return this.resources.getNamespaces();
    }

    @Override
    public List<Resource> getResourceStack(Identifier location) {
        return this.resources.getResourceStack(location);
    }

    @Override
    public Map<Identifier, Resource> listResources(String directory, Predicate<Identifier> filenameFilter) {
        return this.resources.listResources(directory, filenameFilter);
    }

    @Override
    public Map<Identifier, List<Resource>> listResourceStacks(String directory, Predicate<Identifier> filter) {
        return this.resources.listResourceStacks(directory, filter);
    }

    @Override
    public Stream<PackResources> listPacks() {
        return this.resources.listPacks();
    }
}
