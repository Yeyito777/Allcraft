package allcraft.registrytest;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.allcraft.AllcraftRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public final class RegistryEvolutionRegression {
    private static final ResourceKey<Registry<Value>> REGISTRY_KEY = ResourceKey.createRegistryKey(
        Identifier.fromNamespaceAndPath("allcraft", "regression")
    );
    private static final ResourceKey<Value> BASE = ResourceKey.create(
        REGISTRY_KEY, Identifier.fromNamespaceAndPath("allcraft", "base")
    );
    static final ResourceKey<Value> DYNAMIC = ResourceKey.create(
        REGISTRY_KEY, Identifier.fromNamespaceAndPath("allcraft", "dynamic")
    );

    public static void main(String[] args) throws Exception {
        MappedRegistry<Value> server = registry();
        MappedRegistry<Value> client = registry();

        AllcraftRegistries.Transaction serverRevision = AllcraftRegistries.transaction("world", "server", 1L, "server-patch");
        Value[] serverValues = evolve(server, serverRevision);
        String plan = serverRevision.planJson();
        require(plan.contains("allcraft:dynamic"), "server plan did not contain the dynamic key");

        AllcraftRegistries.Transaction clientRevision = AllcraftRegistries.transaction("world", "client", 1L, "client-patch");
        clientRevision.expect(plan);
        Value[] clientValues = evolve(client, clientRevision);
        require(server.getId(serverValues[1]) == client.getId(clientValues[1]), "server/client replacement IDs differ");
        require(serverRevision.mutationCount() > 0 && clientRevision.mutationCount() > 0, "mutations were not journaled");

        clientRevision.rollback();
        serverRevision.rollback();
        assertBaseOnly(server);
        assertBaseOnly(client);

        testIntrusiveRegistration();
        testCommittedRetirementAndReplay();
        testDynamicRegistryAccess();
        testPlanConflict();
        testCanonicalFactoryOwnership();
        System.out.println("PASS registry-evolution");
    }

    private static Value[] evolve(MappedRegistry<Value> registry, AllcraftRegistries.Transaction transaction) throws Exception {
        Value first = new Value("first");
        Value replacement = new Value("replacement");
        AllcraftRegistries.run(transaction, () -> {
            Value registered = AllcraftRegistries.register(registry, DYNAMIC, first);
            require(registered == first, "new registration did not publish its value");
            int id = registry.getId(first);
            require(id == 1, "dynamic registration did not receive the first stable ID");
            require(AllcraftRegistries.registerLazy(registry, DYNAMIC, () -> new Value("duplicate")) == first, "ensure was not idempotent");
            require(AllcraftRegistries.replace(registry, DYNAMIC, replacement) == replacement, "replacement failed");
            require(registry.getId(replacement) == id, "replacement changed the wire ID");
            AllcraftRegistries.retire(registry, DYNAMIC);
            require(AllcraftRegistries.isRetired(registry, DYNAMIC), "retirement marker missing");
            AllcraftRegistries.reactivate(registry, DYNAMIC);
            require(!AllcraftRegistries.isRetired(registry, DYNAMIC), "reactivation failed");
            require(AllcraftRegistries.remove(registry, DYNAMIC) == replacement, "hard removal returned the wrong value");
            require(!registry.containsKey(DYNAMIC), "hard removal left the key visible");
        });
        transaction.closePublication();
        return new Value[]{first, replacement};
    }

    private static void testIntrusiveRegistration() throws Exception {
        ResourceKey<Registry<IntrusiveValue>> registryKey = ResourceKey.createRegistryKey(
            Identifier.fromNamespaceAndPath("allcraft", "intrusive_regression")
        );
        ResourceKey<IntrusiveValue> key = ResourceKey.create(
            registryKey, Identifier.fromNamespaceAndPath("allcraft", "dynamic")
        );
        MappedRegistry<IntrusiveValue> registry = new MappedRegistry<>(registryKey, Lifecycle.stable(), true);
        registry.bindAllTagsToEmpty();
        registry.freeze();
        AllcraftRegistries.Transaction transaction = AllcraftRegistries.transaction("world", "server", 2L, "intrusive");
        AllcraftRegistries.run(transaction, () -> {
            IntrusiveValue value = new IntrusiveValue(registry);
            registry.register(key, value, RegistrationInfo.BUILT_IN);
            require(value.holder.is(key), "late intrusive holder was not bound");
        });
        transaction.closePublication();
        transaction.rollback();
        require(registry.size() == 0, "intrusive rollback leaked an entry");
    }

    private static void testCommittedRetirementAndReplay() throws Exception {
        MappedRegistry<Value> registry = registry();
        AllcraftRegistries.Transaction committed = AllcraftRegistries.transaction("world", "server", 3L, "committed");
        Value dynamic = new Value("committed");
        AllcraftRegistries.run(committed, () -> AllcraftRegistries.register(registry, DYNAMIC, dynamic));
        String plan = committed.planJson();
        int id = registry.getId(dynamic);
        committed.closePublication();
        committed.rollbackRetainingAdditions();
        require(registry.containsKey(DYNAMIC), "committed world exit physically removed a published key");
        require(AllcraftRegistries.isRetired(registry, DYNAMIC), "committed world exit did not retire its published key");
        require(registry.getId(dynamic) == id, "committed world exit changed a published ID");

        AllcraftRegistries.Transaction replay = AllcraftRegistries.transaction("world", "client", 3L, "replay");
        replay.expect(plan);
        AllcraftRegistries.run(
            replay,
            () -> require(AllcraftRegistries.registerLazy(registry, DYNAMIC, () -> new Value("wrong")) == dynamic, "replay replaced tombstone identity")
        );
        replay.closePublication();
        require(!AllcraftRegistries.isRetired(registry, DYNAMIC), "replay did not reactivate the tombstone");
        replay.rollback();
        require(AllcraftRegistries.isRetired(registry, DYNAMIC), "replay rollback did not restore retirement state");
    }

    private static void testPlanConflict() throws Exception {
        MappedRegistry<Value> registry = registry();
        AllcraftRegistries.Transaction transaction = AllcraftRegistries.transaction("world", "client", 3L, "conflict");
        transaction.expect("[{\"operation\":\"ensure\",\"registry\":\"allcraft:regression\",\"key\":\"allcraft:other\",\"id\":1}]");
        boolean failed = false;
        try {
            AllcraftRegistries.run(transaction, () -> AllcraftRegistries.register(registry, DYNAMIC, new Value("wrong")));
        } catch (IllegalStateException expected) {
            failed = true;
        }
        require(failed, "mismatched client registry plan was accepted");
        transaction.rollback();
        assertBaseOnly(registry);
    }

    private static void testDynamicRegistryAccess() throws Exception {
        MappedRegistry<Value> dynamic = new MappedRegistry<>(REGISTRY_KEY, Lifecycle.stable());
        dynamic.bindAllTagsToEmpty();
        dynamic.freeze();
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(dynamic));
        AllcraftRegistries.Transaction transaction = AllcraftRegistries.transaction("dynamic-world", "server", 4L, "dynamic");
        transaction.registryAccess(access);
        Value value = new Value("dynamic-layer");
        AllcraftRegistries.run(
            transaction,
            () -> require(
                AllcraftRegistries.registerLazy(REGISTRY_KEY, DYNAMIC, () -> value) == value,
                "dynamic registry layer did not publish the supplied value"
            )
        );
        transaction.closePublication();
        require(access.lookupOrThrow(REGISTRY_KEY).getValue(DYNAMIC) == value, "dynamic registry access did not expose the published value");
        transaction.rollback();
        require(!dynamic.containsKey(DYNAMIC), "dynamic registry rollback leaked a key");
    }

    private static void testCanonicalFactoryOwnership() throws Exception {
        MappedRegistry<Value> registry = registry();
        AllcraftRegistries.Transaction rejected = AllcraftRegistries.transaction("world", "server", 5L, "side-factory");
        rejected.sharedClasses(
            Set.of(SharedOwner.class.getName()),
            Set.of(SharedOwner.class.getName(), SideFactory.class.getName()),
            true
        );
        boolean failed = false;
        try {
            AllcraftRegistries.run(rejected, () -> SharedOwner.register(registry, SideFactory.factory()));
        } catch (IllegalStateException expected) {
            failed = expected.getMessage().contains("side-only class");
        }
        require(failed, "a side-owned logical registry factory bypassed the shared contract");
        rejected.rollback();

        AllcraftRegistries.Transaction accepted = AllcraftRegistries.transaction("world", "server", 6L, "shared-factory");
        accepted.sharedClasses(
            Set.of(SharedOwner.class.getName()),
            Set.of(SharedOwner.class.getName(), SideFactory.class.getName()),
            true
        );
        AllcraftRegistries.run(accepted, () -> SharedOwner.register(registry, SharedOwner.factory()));
        accepted.closePublication();
        require(registry.containsKey(DYNAMIC), "canonical shared logical factory was rejected");
        accepted.rollback();
    }

    private static MappedRegistry<Value> registry() {
        MappedRegistry<Value> registry = new MappedRegistry<>(REGISTRY_KEY, Lifecycle.stable());
        Registry.register(registry, BASE, new Value("base"));
        registry.bindAllTagsToEmpty();
        registry.freeze();
        return registry;
    }

    private static void assertBaseOnly(MappedRegistry<Value> registry) {
        require(registry.size() == 1, "rollback did not restore registry size");
        require(registry.containsKey(BASE), "rollback removed the base entry");
        require(!registry.containsKey(DYNAMIC), "rollback retained the dynamic entry");
        require(registry.byId(0) != null && registry.byId(1) == null, "rollback did not restore the ID table");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    record Value(String name) {
    }

    private static final class IntrusiveValue {
        private final Holder.Reference<IntrusiveValue> holder;

        private IntrusiveValue(MappedRegistry<IntrusiveValue> registry) {
            this.holder = registry.createIntrusiveHolder(this);
        }
    }
}

final class SharedOwner {
    static Supplier<RegistryEvolutionRegression.Value> factory() {
        return () -> new RegistryEvolutionRegression.Value("shared");
    }

    static void register(
        MappedRegistry<RegistryEvolutionRegression.Value> registry,
        Supplier<RegistryEvolutionRegression.Value> factory
    ) {
        AllcraftRegistries.registerLazy(registry, RegistryEvolutionRegression.DYNAMIC, factory);
    }
}

final class SideFactory {
    static Supplier<RegistryEvolutionRegression.Value> factory() {
        return () -> new RegistryEvolutionRegression.Value("side");
    }
}
