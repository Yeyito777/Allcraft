package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/** Transactional runtime mutation support for Minecraft's otherwise frozen registries. */
public final class AllcraftRegistries {
    private static final Gson GSON = new Gson();
    private static final ThreadLocal<Transaction> ACTIVE = new ThreadLocal<>();

    private AllcraftRegistries() {
    }

    public static Transaction transaction(String worldId, String side, long revision, String patchId) {
        return new Transaction(worldId, side, revision, patchId);
    }

    public static boolean mutationAllowed() {
        Transaction transaction = ACTIVE.get();
        return transaction != null && (transaction.replaying || !transaction.publicationClosed);
    }

    public static <T> T run(Transaction transaction, ThrowingSupplier<T> action) throws Exception {
        Objects.requireNonNull(transaction, "transaction");
        Transaction previous = ACTIVE.get();
        if (previous != null && previous != transaction) {
            throw new IllegalStateException("Nested Allcraft registry transactions are not allowed");
        }
        ACTIVE.set(transaction);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    public static void run(Transaction transaction, ThrowingRunnable action) throws Exception {
        run(transaction, () -> {
            action.run();
            return null;
        });
    }

    /** Records a reversible non-registry cache mutation in the active revision. */
    public static void recordUndo(String description, Runnable undo) {
        recordUndo(description, undo, null);
    }

    /**
     * Records storage that must remain allocated after leaving a committed world. Registry keys and
     * numeric IDs are process-lifetime identities, so committed additions become retired tombstones
     * instead of being physically removed. Failed/uncommitted revisions still run the normal undo.
     */
    public static void recordRetainedUndo(String description, Runnable undo, Runnable retire) {
        recordUndo(description, undo, Objects.requireNonNull(retire, "retire"));
    }

    private static void recordUndo(String description, Runnable undo, Runnable retire) {
        Transaction transaction = ACTIVE.get();
        if (transaction != null && !transaction.replaying) {
            transaction.undo.add(new Undo(description, Objects.requireNonNull(undo, "undo"), retire));
            transaction.mutationCount++;
        }
    }

    /** Returns an existing value or constructs and registers a new one under the active revision. */
    public static <T> T registerLazy(Registry<T> registry, ResourceKey<T> key, Supplier<? extends T> factory) {
        Optional<T> existing = registry.getOptional(key);
        if (existing.isPresent()) {
            claim(registry, key, registry.getId(existing.get()), "ensure");
            mapped(registry).allcraftEnsureActive(key);
            onEnsured(key, existing.get());
            return existing.get();
        }
        T value = factory.get();
        Holder.Reference<T> holder = ((WritableRegistry<T>)registry).register(key, value, RegistrationInfo.BUILT_IN);
        return holder.value();
    }

    public static <T> T register(Registry<T> registry, ResourceKey<T> key, T value) {
        Optional<T> existing = registry.getOptional(key);
        if (existing.isPresent()) {
            claim(registry, key, registry.getId(existing.get()), "ensure");
            mapped(registry).allcraftEnsureActive(key);
            onEnsured(key, existing.get());
            return existing.get();
        }
        return ((WritableRegistry<T>)registry).register(key, value, RegistrationInfo.BUILT_IN).value();
    }

    /** Resolves a registry from the active world registry layer. */
    public static <T> Registry<T> registry(ResourceKey<? extends Registry<? extends T>> registryKey) {
        Transaction transaction = ACTIVE.get();
        if (transaction == null || transaction.registryAccess == null) {
            throw new IllegalStateException("Dynamic registry access requires an active Allcraft revision transaction");
        }
        return transaction.registryAccess.lookupOrThrow(registryKey);
    }

    /** Adds or reactivates a value in the active world's layered registry access. */
    public static <T> T registerLazy(
        ResourceKey<? extends Registry<? extends T>> registryKey, ResourceKey<T> key, Supplier<? extends T> factory
    ) {
        return registerLazy(registry(registryKey), key, factory);
    }

    public static <T> T replace(Registry<T> registry, ResourceKey<T> key, T value) {
        return mapped(registry).allcraftReplace(key, value, RegistrationInfo.BUILT_IN);
    }

    /** Hard removal is reversible, but callers must first migrate every live value using the entry. */
    public static <T> T remove(Registry<T> registry, ResourceKey<T> key) {
        return mapped(registry).allcraftRemove(key);
    }

    /** Marks an entry retired while retaining its key, value, holder, and wire ID for compatibility. */
    public static <T> void retire(Registry<T> registry, ResourceKey<T> key) {
        mapped(registry).allcraftRetire(key);
    }

    public static <T> void reactivate(Registry<T> registry, ResourceKey<T> key) {
        mapped(registry).allcraftReactivate(key);
    }

    public static boolean isRetired(Registry<?> registry, ResourceKey<?> key) {
        return registry instanceof MappedRegistry<?> mapped && mapped.allcraftIsRetired(key);
    }

    private static <T> MappedRegistry<T> mapped(Registry<T> registry) {
        if (registry instanceof MappedRegistry<T> mapped) {
            return mapped;
        }
        throw new IllegalArgumentException("Registry does not support Allcraft evolution: " + registry.key());
    }

    /** Called by MappedRegistry before assigning a published numeric ID. */
    public static int claim(Registry<?> registry, ResourceKey<?> key, int proposedId, String operation) {
        return claim(registry.key().identifier().toString(), key.identifier().toString(), proposedId, operation);
    }

    private static int claim(String registry, String key, int proposedId, String operation) {
        Transaction transaction = ACTIVE.get();
        if (transaction == null || transaction.replaying) {
            throw new IllegalStateException("Frozen registry mutation requires an active Allcraft revision transaction");
        }
        PlanEntry actual = new PlanEntry(operation, registry, key, proposedId);
        if (transaction.expectedPlan != null) {
            if (transaction.expectedIndex >= transaction.expectedPlan.size()) {
                throw new IllegalStateException("Client registry transaction produced an unexpected mutation " + actual);
            }
            PlanEntry expected = transaction.expectedPlan.get(transaction.expectedIndex++);
            if (!expected.operation.equals(operation) || !expected.registry.equals(registry) || !expected.key.equals(key)) {
                throw new IllegalStateException("Registry plan mismatch: expected " + expected + " but received " + actual);
            }
            return expected.id;
        }
        transaction.plan.add(actual);
        return proposedId;
    }

    /** Completes secondary tables that vanilla normally builds only during bootstrap. */
    public static void onRegistered(Registry<?> registry, ResourceKey<?> key, Object value, boolean replacement) {
        if (value instanceof Block block) {
            int stateIndex = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int proposed = Block.BLOCK_STATE_REGISTRY.allcraftNextId();
                int id = claim("allcraft:block_state", key.identifier() + "#" + stateIndex++, proposed, "ensure");
                Block.BLOCK_STATE_REGISTRY.addMapping(state, id);
                state.initCache();
            }
        } else if (value instanceof Fluid fluid) {
            int stateIndex = 0;
            for (FluidState state : fluid.getStateDefinition().getPossibleStates()) {
                int proposed = Fluid.FLUID_STATE_REGISTRY.allcraftNextId();
                int id = claim("allcraft:fluid_state", key.identifier() + "#" + stateIndex++, proposed, "ensure");
                Fluid.FLUID_STATE_REGISTRY.addMapping(state, id);
            }
        }
        if (value instanceof BlockItem blockItem) {
            Item previous = Item.BY_BLOCK.put(blockItem.getBlock(), blockItem);
            recordUndo("restore block-item map for " + key.identifier(), () -> {
                if (previous == null) {
                    Item.BY_BLOCK.remove(blockItem.getBlock(), blockItem);
                } else {
                    Item.BY_BLOCK.put(blockItem.getBlock(), previous);
                }
            });
        }
    }

    /** Consumes auxiliary ID-plan entries when an integrated client shares the server's registry object. */
    public static void onEnsured(ResourceKey<?> key, Object value) {
        if (value instanceof Item) {
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.reactivate(key);
        }
        if (value instanceof Block block) {
            int stateIndex = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                int assigned = claim("allcraft:block_state", key.identifier() + "#" + stateIndex++, id, "ensure");
                if (assigned != id) {
                    throw new IllegalStateException("Block-state ID plan mismatch for " + key);
                }
            }
        } else if (value instanceof Fluid fluid) {
            int stateIndex = 0;
            for (FluidState state : fluid.getStateDefinition().getPossibleStates()) {
                int id = Fluid.FLUID_STATE_REGISTRY.getId(state);
                int assigned = claim("allcraft:fluid_state", key.identifier() + "#" + stateIndex++, id, "ensure");
                if (assigned != id) {
                    throw new IllegalStateException("Fluid-state ID plan mismatch for " + key);
                }
            }
        }
    }

    /** Rebuilds item and other holder components after a registry mutation. */
    public static void refreshComponents(RegistryAccess registries) {
        for (DataComponentInitializers.PendingComponents<?> pending : BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)) {
            pending.apply();
        }
    }

    /** Hash of every built-in registry key/ID plus auxiliary block/fluid state IDs. */
    public static String fingerprint() {
        return fingerprint(null);
    }

    /** Hashes built-ins and every registry visible through the selected live world layer. */
    public static String fingerprint(RegistryAccess access) {
        return fingerprint(access, null);
    }

    /** Hashes built-ins plus only dynamic registries named by the synchronized mutation plan. */
    public static String fingerprint(RegistryAccess access, String planJson) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<Registry<?>> registries = new ArrayList<>();
        Set<String> selectedDynamicRegistries = null;
        if (planJson != null) {
            selectedDynamicRegistries = new java.util.HashSet<>();
            JsonArray plan = JsonParser.parseString(planJson.isBlank() ? "[]" : planJson).getAsJsonArray();
            for (JsonElement element : plan) {
                selectedDynamicRegistries.add(element.getAsJsonObject().get("registry").getAsString());
            }
        }
        Set<String> selected = selectedDynamicRegistries;
        Set<Registry<?>> identities = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        BuiltInRegistries.REGISTRY.forEach(value -> {
            if (identities.add((Registry<?>)value)) {
                registries.add((Registry<?>)value);
            }
        });
        if (access != null) {
            access.registries().forEach(entry -> {
                if ((selected == null || selected.contains(entry.key().identifier().toString())) && identities.add(entry.value())) {
                    registries.add(entry.value());
                }
            });
        }
        registries.sort(java.util.Comparator.comparing(value -> value.key().identifier().toString()));
        for (Registry<?> registry : registries) {
            update(digest, registry.key().identifier().toString());
            registry.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().identifier().toString())).forEach(entry -> {
                update(digest, entry.getKey().identifier().toString());
                update(digest, Integer.toString(id(registry, entry.getValue())));
                update(digest, Boolean.toString(isRetired(registry, entry.getKey())));
                if (entry.getValue() instanceof Block block) {
                    for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                        update(digest, Integer.toString(Block.BLOCK_STATE_REGISTRY.getId(state)));
                    }
                } else if (entry.getValue() instanceof Fluid fluid) {
                    for (FluidState state : fluid.getStateDefinition().getPossibleStates()) {
                        update(digest, Integer.toString(Fluid.FLUID_STATE_REGISTRY.getId(state)));
                    }
                }
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @SuppressWarnings("unchecked")
    private static int id(Registry<?> registry, Object value) {
        return ((Registry<Object>)registry).getId(value);
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
    }

    public static final class Transaction {
        private final String worldId;
        private final String side;
        private final long revision;
        private final String patchId;
        private final List<Undo> undo = new ArrayList<>();
        private final List<PlanEntry> plan = new ArrayList<>();
        private List<PlanEntry> expectedPlan;
        private int expectedIndex;
        private int mutationCount;
        private RegistryAccess registryAccess;
        private boolean replaying;
        private boolean publicationClosed;
        private boolean rolledBack;

        private Transaction(String worldId, String side, long revision, String patchId) {
            this.worldId = worldId;
            this.side = side;
            this.revision = revision;
            this.patchId = patchId;
        }

        public synchronized void registryAccess(RegistryAccess registryAccess) {
            if (this.publicationClosed || this.rolledBack) {
                throw new IllegalStateException("Registry access was configured after transaction publication");
            }
            this.registryAccess = Objects.requireNonNull(registryAccess, "registryAccess");
        }

        public synchronized void expect(String json) {
            if (!this.plan.isEmpty() || this.publicationClosed) {
                throw new IllegalStateException("Registry plan was configured after publication began");
            }
            JsonArray array = JsonParser.parseString(json == null || json.isBlank() ? "[]" : json).getAsJsonArray();
            List<PlanEntry> expected = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                expected.add(
                    new PlanEntry(
                        object.get("operation").getAsString(),
                        object.get("registry").getAsString(),
                        object.get("key").getAsString(),
                        object.get("id").getAsInt()
                    )
                );
            }
            this.expectedPlan = List.copyOf(expected);
        }

        public synchronized void closePublication() {
            if (this.expectedPlan != null && this.expectedIndex != this.expectedPlan.size()) {
                throw new IllegalStateException(
                    "Client consumed " + this.expectedIndex + " of " + this.expectedPlan.size() + " registry-plan entries"
                );
            }
            this.publicationClosed = true;
        }

        public synchronized String planJson() {
            JsonArray array = new JsonArray();
            for (PlanEntry entry : this.expectedPlan == null ? this.plan : this.expectedPlan) {
                JsonObject object = new JsonObject();
                object.addProperty("operation", entry.operation);
                object.addProperty("registry", entry.registry);
                object.addProperty("key", entry.key);
                object.addProperty("id", entry.id);
                array.add(object);
            }
            return GSON.toJson(array);
        }

        public synchronized boolean changed() {
            return this.mutationCount > 0;
        }

        public synchronized int mutationCount() {
            return this.mutationCount;
        }

        public synchronized void rollback() {
            this.rollback(false);
        }

        /** Retires process-lifetime registry IDs while undoing every ordinary live-cache mutation. */
        public synchronized void rollbackRetainingAdditions() {
            this.rollback(true);
        }

        private void rollback(boolean retainAdditions) {
            if (this.rolledBack) {
                return;
            }
            this.replaying = true;
            Transaction previous = ACTIVE.get();
            ACTIVE.set(this);
            List<Throwable> failures = new ArrayList<>();
            try {
                for (int index = this.undo.size() - 1; index >= 0; index--) {
                    try {
                        Undo undo = this.undo.get(index);
                        Runnable action = retainAdditions && undo.retire != null ? undo.retire : undo.action;
                        action.run();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }
            } finally {
                if (previous == null) {
                    ACTIVE.remove();
                } else {
                    ACTIVE.set(previous);
                }
                this.replaying = false;
                this.rolledBack = true;
            }
            if (!failures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("Registry rollback failed for " + this.patchId);
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        @Override
        public String toString() {
            return "RegistryTransaction{" + this.worldId + "/" + this.side + "@" + this.revision + ", patch=" + this.patchId + "}";
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Undo(String description, Runnable action, Runnable retire) {
    }

    private record PlanEntry(String operation, String registry, String key, int id) {
    }
}
