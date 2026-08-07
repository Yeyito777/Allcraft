package net.minecraft.allcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.slf4j.Logger;

/** Applies arbitrary class-file overlays as reversible revision transactions. */
public final class AllcraftRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Class<?>, byte[]> BASE_DEFINITIONS = new IdentityHashMap<>();
    private static final Map<Class<?>, byte[]> CURRENT_DEFINITIONS = new IdentityHashMap<>();
    private static final Map<String, byte[]> TOMBSTONES = new HashMap<>();
    private static final Set<String> ADDED_CLASSES = new LinkedHashSet<>();
    private static final List<Transaction> ACTIVE_REVISIONS = new ArrayList<>();
    private static final List<Transaction> PENDING_COMMITS = new ArrayList<>();
    private static final List<JarFile> APPENDED_ARTIFACTS = new ArrayList<>();
    private static final Map<String, Transaction> INTEGRATED_SERVER_PUBLICATIONS = new HashMap<>();

    private AllcraftRuntime() {
    }

    /** Reads, verifies, and snapshots an artifact without changing any loaded class. */
    public static synchronized Transaction stage(Path artifact, String expectedSha256) throws Exception {
        Path normalized = artifact.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Runtime artifact does not exist: " + normalized);
        }
        String actualSha256 = sha256(normalized);
        if (!actualSha256.equals(expectedSha256)) {
            throw new IOException("Runtime artifact SHA-256 mismatch for " + normalized.getFileName());
        }

        JsonObject descriptor = readDescriptor(normalized);
        Map<String, byte[]> classes = readClasses(normalized, "", false);
        Map<String, byte[]> tombstones = readClasses(normalized, "META-INF/allcraft-tombstones/", true);
        Map<String, byte[]> parentDefinitions = readClasses(normalized, "META-INF/allcraft-previous/", true);
        Set<String> addedClasses = Set.copyOf(strings(descriptor, "addedClasses"));
        Set<String> deletedClasses = Set.copyOf(strings(descriptor, "deletedClasses"));
        Map<String, String> sharedClasses = sharedClasses(descriptor);
        Hooks hooks = Hooks.from(descriptor.has("hooks") ? descriptor.getAsJsonObject("hooks") : new JsonObject());
        List<String> entrypoints = strings(descriptor, "entrypoints");
        verifyClasses(classes);
        verifyClasses(tombstones);
        verifyClasses(parentDefinitions);
        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        ClassLoader gameLoader = AllcraftRuntime.class.getClassLoader();
        Map<String, Class<?>> loaded = loadedClasses(instrumentation, gameLoader);
        validateLifecycle(classes, hooks, entrypoints, gameLoader);
        validateSharedContract(descriptor, classes, parentDefinitions, addedClasses, sharedClasses);
        Map<String, byte[]> previous = new LinkedHashMap<>();
        for (String name : classes.keySet()) {
            Class<?> type = loaded.get(name);
            if (type != null) {
                if (!instrumentation.isModifiableClass(type)) {
                    throw new IllegalStateException("JVM refuses to redefine " + type.getName());
                }
                previous.put(name, currentDefinition(type));
            }
        }
        MigrationContext context = new MigrationContext(
            descriptor.has("worldId") ? descriptor.get("worldId").getAsString() : "unknown",
            descriptor.has("side") ? descriptor.get("side").getAsString() : "unknown",
            descriptor.has("parentRevision") ? descriptor.get("parentRevision").getAsLong() : -1L,
            descriptor.has("revision") ? descriptor.get("revision").getAsLong() : -1L,
            descriptor.has("patchId") ? descriptor.get("patchId").getAsString() : normalized.getFileName().toString(),
            normalized
        );
        return new Transaction(
            normalized,
            expectedSha256,
            descriptor,
            classes,
            tombstones,
            parentDefinitions,
            addedClasses,
            deletedClasses,
            sharedClasses,
            hooks,
            entrypoints,
            previous,
            context
        );
    }

    /** Compatibility entrypoint: stage, publish, migrate, and finalize one transaction. */
    public static synchronized ApplyResult apply(Path artifact, String expectedSha256) throws Exception {
        return apply(artifact, expectedSha256, null);
    }

    /** Replays an artifact against a selected live world registry layer. */
    public static synchronized ApplyResult apply(
        Path artifact, String expectedSha256, net.minecraft.core.RegistryAccess registryAccess
    ) throws Exception {
        Transaction transaction = stage(artifact, expectedSha256);
        if (registryAccess != null) {
            transaction.registryAccess(registryAccess);
        }
        try {
            ApplyResult result = transaction.publish();
            transaction.finish();
            transaction.seal();
            return result;
        } catch (Exception | Error failure) {
            try {
                transaction.rollback();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /** Leaves the active world's code while retaining executable definitions for world-added classes. */
    public static synchronized void resetToBase() throws Exception {
        for (int index = PENDING_COMMITS.size() - 1; index >= 0; index--) {
            rollback(PENDING_COMMITS.get(index));
        }
        PENDING_COMMITS.clear();
        for (int index = ACTIVE_REVISIONS.size() - 1; index >= 0; index--) {
            Transaction committed = ACTIVE_REVISIONS.get(index);
            committed.sealed = false;
            rollback(committed, true);
        }
        ACTIVE_REVISIONS.clear();

        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        List<ClassDefinition> definitions = new ArrayList<>();
        Map<Class<?>, byte[]> next = new IdentityHashMap<>();
        for (Map.Entry<Class<?>, byte[]> entry : BASE_DEFINITIONS.entrySet()) {
            if (instrumentation.isModifiableClass(entry.getKey()) && !Arrays.equals(entry.getValue(), CURRENT_DEFINITIONS.get(entry.getKey()))) {
                definitions.add(new ClassDefinition(entry.getKey(), entry.getValue()));
                next.put(entry.getKey(), entry.getValue());
            }
        }
        if (!definitions.isEmpty()) {
            instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            CURRENT_DEFINITIONS.putAll(next);
            LOGGER.info("Reconciled {} base runtime class(es) while retaining world-added definitions safely", definitions.size());
        }
    }

    /** Runs a dedicated artifact rollback hook after a process crash, without replaying migration. */
    public static synchronized void recoverRollback(Path artifact, String expectedSha256) throws Exception {
        Transaction transaction = stage(artifact, expectedSha256);
        if (transaction.hooks.rollback.isEmpty()) {
            return;
        }
        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        ClassLoader loader = AllcraftRuntime.class.getClassLoader();
        JarFile appended = new JarFile(transaction.artifact.toFile(), false);
        instrumentation.appendToSystemClassLoaderSearch(appended);
        APPENDED_ARTIFACTS.add(appended);
        TOMBSTONES.putAll(transaction.tombstones);
        Map<String, Class<?>> loaded = loadedClasses(instrumentation, loader);
        List<ClassDefinition> hookDefinitions = new ArrayList<>();
        List<ClassDefinition> restoreDefinitions = new ArrayList<>();
        for (String className : transaction.hooks.rollback) {
            byte[] desired = transaction.classes.get(className);
            if (desired == null) {
                throw new IOException("Crash-recovery rollback hook bytes are missing for " + className);
            }
            Class<?> type = loaded.get(className);
            if (type == null) {
                type = Class.forName(className, false, loader);
            }
            byte[] current = transaction.addedClasses.contains(className)
                ? CURRENT_DEFINITIONS.get(type)
                : transaction.parentDefinitions.get(className);
            if (current != null && !Arrays.equals(current, desired)) {
                restoreDefinitions.add(new ClassDefinition(type, current));
            }
            hookDefinitions.add(new ClassDefinition(type, desired));
        }
        instrumentation.redefineClasses(hookDefinitions.toArray(ClassDefinition[]::new));
        try {
            invokeHooks(transaction.hooks.rollback, "allcraftRollback", transaction.context);
        } finally {
            if (!restoreDefinitions.isEmpty()) {
                instrumentation.redefineClasses(restoreDefinitions.toArray(ClassDefinition[]::new));
            }
        }
        LOGGER.warn("Ran crash-recovery rollback hooks for transaction {}", transaction.context.patchId);
    }

    private static synchronized ApplyResult publish(Transaction transaction) throws Exception {
        long startedAt = System.nanoTime();
        long gcBefore = gcCount();
        long gcMillisBefore = gcMillis();
        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        if (!instrumentation.isRedefineClassesSupported()) {
            throw new IllegalStateException("This JVM does not support class redefinition");
        }
        ClassLoader loader = AllcraftRuntime.class.getClassLoader();
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ClassDefinition> earlyDefinitions = new ArrayList<>();
        List<ClassDefinition> definitions = new ArrayList<>();
        Map<Class<?>, byte[]> changed = new IdentityHashMap<>();
        Transaction integratedServerOwner = integratedServerOwner(transaction);
        JarFile appended = null;
        long redefineMillis = 0L;
        try {
            TOMBSTONES.putAll(transaction.tombstones);

            // Appending only extends class lookup. It does not override any class already present in
            // Minecraft's base JAR. The artifact's explicit added-class set lets us distinguish
            // genuine additions from previously-unloaded base classes, which must be loaded from
            // the parent revision and then redefined below.
            if (!transaction.addedClasses.isEmpty()) {
                appended = new JarFile(transaction.artifact.toFile(), false);
                instrumentation.appendToSystemClassLoaderSearch(appended);
                APPENDED_ARTIFACTS.add(appended);
                Map<String, Class<?>> beforePrepare = loadedClasses(instrumentation, loader);
                for (String className : transaction.addedClasses) {
                    if (integratedServerOwner != null && integratedServerOwner.classes.containsKey(className)) {
                        continue;
                    }
                    if (beforePrepare.containsKey(className) || ADDED_CLASSES.contains(className)) {
                        continue;
                    }
                    Class<?> introduced = Class.forName(className, false, loader);
                    transaction.introducedClasses.add(className);
                    added.add(className);
                    ADDED_CLASSES.add(className);
                    CURRENT_DEFINITIONS.put(introduced, transaction.classes.get(className));
                }
            }

            // Preparation is allowed to allocate/checkpoint state but cannot observe redefined game
            // classes yet. Its own implementation (and rollback implementation) is infrastructure:
            // publish those hook classes first so a tombstoned hook can run again after world switch.
            transaction.prepared = true;
            Map<String, Class<?>> hookLoaded = loadedClasses(instrumentation, loader);
            Set<String> earlyHookNames = new LinkedHashSet<>(transaction.hooks.prepare);
            earlyHookNames.addAll(transaction.hooks.rollback);
            Map<Class<?>, byte[]> earlyChanged = new IdentityHashMap<>();
            for (String className : earlyHookNames) {
                if (integratedServerOwner != null && integratedServerOwner.classes.containsKey(className)) {
                    continue;
                }
                byte[] desired = transaction.classes.get(className);
                Class<?> type = hookLoaded.get(className);
                if (desired == null || type == null) {
                    continue;
                }
                byte[] current;
                if (ADDED_CLASSES.contains(className)) {
                    current = CURRENT_DEFINITIONS.get(type);
                    if (current == null) {
                        current = transaction.stagedPrevious.get(className);
                    }
                    if (current == null) {
                        current = desired;
                    }
                } else {
                    rememberBaseDefinition(type);
                    current = CURRENT_DEFINITIONS.get(type);
                }
                if (Arrays.equals(current, desired)) {
                    continue;
                }
                byte[] expectedParent = transaction.parentDefinitions.get(className);
                if (expectedParent != null && !Arrays.equals(current, expectedParent)) {
                    throw new IllegalStateException("Prepare hook " + className + " does not match the artifact parent revision");
                }
                transaction.publishedPrevious.putIfAbsent(className, current);
                earlyDefinitions.add(new ClassDefinition(type, desired));
                earlyChanged.put(type, desired);
            }
            if (!earlyDefinitions.isEmpty()) {
                long redefineStart = System.nanoTime();
                instrumentation.redefineClasses(earlyDefinitions.toArray(ClassDefinition[]::new));
                redefineMillis += elapsedMillis(redefineStart);
                CURRENT_DEFINITIONS.putAll(earlyChanged);
                for (ClassDefinition definition : earlyDefinitions) {
                    transaction.publishedClasses.add(definition.getDefinitionClass().getName());
                }
                transaction.published = true;
            }
            AllcraftRegistries.run(
                transaction.registryTransaction,
                () -> invokeHooks(transaction.hooks.prepare, "allcraftPrepare", transaction.context)
            );

            Map<String, Class<?>> loaded = loadedClasses(instrumentation, loader);
            for (Map.Entry<String, byte[]> entry : transaction.classes.entrySet()) {
                String className = entry.getKey();
                if (integratedServerOwner != null && integratedServerOwner.classes.containsKey(className)) {
                    // An integrated client and its server share one JVM/class identity. The server
                    // transaction owns overlapping definitions; the client still publishes its
                    // side-only hooks/resources but must never overwrite or later roll back those
                    // server definitions with a second independently compiled class body.
                    skipped.add(className);
                    continue;
                }
                if (transaction.deletedClasses.contains(className)) {
                    // The ordinary JVM cannot unload one class, and replacing every method with a
                    // throwing tombstone corrupts live menus/entities/tasks that still own it. A
                    // deletion therefore retires future source/registry reachability while keeping
                    // the last executable definition resident until process exit or name reuse.
                    skipped.add(className);
                    continue;
                }
                Class<?> type = loaded.get(className);
                boolean declaredAddition = transaction.addedClasses.contains(className);
                if (type == null) {
                    type = Class.forName(className, false, loader);
                    loaded.put(className, type);
                    if (declaredAddition) {
                        transaction.introducedClasses.add(className);
                        added.add(className);
                        ADDED_CLASSES.add(className);
                        CURRENT_DEFINITIONS.put(type, entry.getValue());
                        continue;
                    }
                }

                byte[] current;
                if (ADDED_CLASSES.contains(className)) {
                    current = CURRENT_DEFINITIONS.get(type);
                    if (current == null) {
                        current = transaction.stagedPrevious.get(className);
                    }
                    if (current == null) {
                        current = transaction.parentDefinitions.get(className);
                    }
                    if (current == null) {
                        throw new IOException("Cannot capture current definition for added class " + className);
                    }
                } else {
                    rememberBaseDefinition(type);
                    current = CURRENT_DEFINITIONS.get(type);
                }
                byte[] expectedParent = transaction.parentDefinitions.get(className);
                if (expectedParent != null && !Arrays.equals(current, expectedParent) && !Arrays.equals(current, entry.getValue())) {
                    throw new IllegalStateException(
                        "Runtime definition for " + className + " does not match artifact parent revision " + transaction.context.fromRevision
                    );
                }
                if (Arrays.equals(current, entry.getValue())) {
                    skipped.add(className);
                    continue;
                }
                if (!instrumentation.isModifiableClass(type)) {
                    throw new IllegalStateException("JVM refuses to redefine " + type.getName());
                }
                transaction.publishedPrevious.putIfAbsent(className, current);
                definitions.add(new ClassDefinition(type, entry.getValue()));
                changed.put(type, entry.getValue());
            }

            if (!definitions.isEmpty()) {
                long redefineStart = System.nanoTime();
                instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
                redefineMillis += elapsedMillis(redefineStart);
                CURRENT_DEFINITIONS.putAll(changed);
                for (ClassDefinition definition : definitions) {
                    transaction.publishedClasses.add(definition.getDefinitionClass().getName());
                }
            }
            transaction.published = true;
            AllcraftRegistries.run(
                transaction.registryTransaction,
                () -> invokeHooks(transaction.hooks.migrate, "allcraftMigrate", transaction.context)
            );
            AllcraftRegistries.run(transaction.registryTransaction, () -> invokeEntrypoints(transaction.entrypoints, loader));
            transaction.registryTransaction.closePublication();
            if ("server".equals(transaction.context.side)) {
                INTEGRATED_SERVER_PUBLICATIONS.put(transaction.context.patchId, transaction);
            }
        } catch (Exception | Error failure) {
            // Appended search paths are process-lifetime state by Instrumentation contract. Keep
            // the JarFile open and let rollback tombstone any introduced class that was loaded.
            throw failure;
        }

        List<String> redefined = Stream.concat(earlyDefinitions.stream(), definitions.stream())
            .map(value -> value.getDefinitionClass().getName())
            .distinct()
            .sorted()
            .toList();
        added.sort(String::compareTo);
        skipped.sort(String::compareTo);
        ApplyResult result = new ApplyResult(
            redefined.size(),
            added.size(),
            skipped.size(),
            transaction.deletedClasses.size(),
            List.copyOf(transaction.entrypoints),
            elapsedMillis(startedAt),
            redefineMillis,
            Math.max(0L, gcCount() - gcBefore),
            Math.max(0L, gcMillis() - gcMillisBefore)
        );
        transaction.result = result;
        LOGGER.info(
            "Published Allcraft transaction {}: {} redefined, {} added, {} retired, {} ms total",
            transaction.context.patchId,
            result.redefinedClasses,
            result.addedClasses,
            result.retiredClasses,
            result.totalMillis
        );
        return result;
    }

    private static synchronized void finish(Transaction transaction) throws Exception {
        if (!transaction.published || transaction.finished) {
            return;
        }
        AllcraftRegistries.run(
            transaction.registryTransaction,
            () -> invokeHooks(transaction.hooks.commit, "allcraftCommit", transaction.context)
        );
        transaction.finished = true;
        PENDING_COMMITS.add(transaction);
    }

    private static synchronized void seal(Transaction transaction) {
        if (!transaction.finished || transaction.rolledBack || transaction.sealed) {
            return;
        }
        ACTIVE_REVISIONS.add(transaction);
        PENDING_COMMITS.remove(transaction);
        transaction.sealed = true;
    }

    private static synchronized void rollback(Transaction transaction) throws Exception {
        rollback(transaction, false);
    }

    private static synchronized void rollback(Transaction transaction, boolean retainRegistryAdditions) throws Exception {
        if ((!transaction.prepared && !transaction.published) || transaction.rolledBack || transaction.sealed) {
            return;
        }
        List<Throwable> errors = new ArrayList<>();
        try {
            AllcraftRegistries.run(
                transaction.registryTransaction,
                () -> invokeHooks(transaction.hooks.rollback, "allcraftRollback", transaction.context)
            );
        } catch (Throwable failure) {
            errors.add(failure);
        }
        try {
            if (retainRegistryAdditions) {
                transaction.registryTransaction.rollbackRetainingAdditions();
            } else {
                transaction.registryTransaction.rollback();
            }
        } catch (Throwable failure) {
            errors.add(failure);
        }
        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        ClassLoader loader = AllcraftRuntime.class.getClassLoader();
        Map<String, Class<?>> loaded = loadedClasses(instrumentation, loader);
        List<ClassDefinition> definitions = new ArrayList<>();
        Map<Class<?>, byte[]> restored = new IdentityHashMap<>();
        for (Map.Entry<String, byte[]> entry : transaction.publishedPrevious.entrySet()) {
            if (!transaction.publishedClasses.contains(entry.getKey())) continue;
            Class<?> type = loaded.get(entry.getKey());
            if (type != null) {
                definitions.add(new ClassDefinition(type, entry.getValue()));
                restored.put(type, entry.getValue());
            }
        }
        // Introduced classes deliberately remain executable. Registry/cache undo removes future
        // reachability; residency is the only generally safe choice when arbitrary Java references
        // may still point at instances, lambdas, method handles, or active stack frames.
        try {
            if (!definitions.isEmpty()) {
                instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
                CURRENT_DEFINITIONS.putAll(restored);
            }
        } catch (Throwable failure) {
            errors.add(failure);
        }
        transaction.rolledBack = true;
        PENDING_COMMITS.remove(transaction);
        INTEGRATED_SERVER_PUBLICATIONS.remove(transaction.context.patchId, transaction);
        if (!errors.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("Allcraft transaction rollback was incomplete");
            errors.forEach(failure::addSuppressed);
            throw failure;
        }
        LOGGER.warn("Rolled back Allcraft transaction {}", transaction.context.patchId);
    }

    private static Transaction integratedServerOwner(Transaction transaction) {
        if (!"client".equals(transaction.context.side)) {
            return null;
        }
        Transaction owner = INTEGRATED_SERVER_PUBLICATIONS.get(transaction.context.patchId);
        if (owner == null
            || owner.rolledBack
            || !owner.published
            || !owner.context.worldId.equals(transaction.context.worldId)
            || owner.context.toRevision != transaction.context.toRevision) {
            return null;
        }
        return owner;
    }

    private static void verifyClasses(Map<String, byte[]> classes) throws IOException {
        ClassFile parser = ClassFile.of();
        Map<String, ClassModel> models = new LinkedHashMap<>();
        java.util.Set<java.lang.constant.ClassDesc> interfaces = new java.util.HashSet<>();
        Map<java.lang.constant.ClassDesc, java.lang.constant.ClassDesc> superclasses = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            byte[] bytes = entry.getValue();
            if (bytes.length < 4 || bytes[0] != (byte)0xCA || bytes[1] != (byte)0xFE || bytes[2] != (byte)0xBA || bytes[3] != (byte)0xBE) {
                throw new IOException("Invalid class-file magic for " + entry.getKey());
            }
            ClassModel model = parser.parse(bytes);
            models.put(entry.getKey(), model);
            java.lang.constant.ClassDesc descriptor = model.thisClass().asSymbol();
            if (model.flags().has(java.lang.reflect.AccessFlag.INTERFACE)) {
                interfaces.add(descriptor);
            } else {
                model.superclass().ifPresent(parent -> superclasses.put(descriptor, parent.asSymbol()));
            }
        }
        java.lang.classfile.ClassHierarchyResolver resolver = java.lang.classfile.ClassHierarchyResolver.of(interfaces, superclasses)
            .orElse(java.lang.classfile.ClassHierarchyResolver.defaultResolver());
        ClassFile verifier = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        for (Map.Entry<String, ClassModel> entry : models.entrySet()) {
            List<java.lang.VerifyError> errors = verifier.verify(entry.getValue());
            if (!errors.isEmpty()) {
                throw new IOException("Class-file verification failed for " + entry.getKey() + ": " + errors.getFirst());
            }
        }
    }

    private static void validateLifecycle(
        Map<String, byte[]> classes, Hooks hooks, List<String> entrypoints, ClassLoader loader
    ) throws IOException {
        validateHookMethods(classes, hooks.prepare, "allcraftPrepare");
        validateHookMethods(classes, hooks.migrate, "allcraftMigrate");
        validateHookMethods(classes, hooks.commit, "allcraftCommit");
        validateHookMethods(classes, hooks.rollback, "allcraftRollback");
        for (String className : entrypoints) {
            byte[] bytes = classes.get(className);
            if (bytes != null && !hasMethod(bytes, "allcraftActivate", "()V")) {
                throw new IOException("Staged entrypoint " + className + " has no static-compatible allcraftActivate() method");
            }
        }
        Set<String> declared = new LinkedHashSet<>();
        declared.addAll(hooks.prepare);
        declared.addAll(hooks.migrate);
        declared.addAll(hooks.commit);
        declared.addAll(hooks.rollback);
        declared.addAll(entrypoints);
        for (String className : declared) {
            if (classes.containsKey(className)) continue;
            try {
                Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IOException("Staged lifecycle declares unavailable class " + className, e);
            }
        }
    }

    private static Map<String, String> sharedClasses(JsonObject descriptor) throws IOException {
        if (!descriptor.has("sharedClasses")) {
            return Map.of();
        }
        Map<String, String> result = new java.util.TreeMap<>();
        try {
            descriptor.getAsJsonObject("sharedClasses").entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (RuntimeException e) {
            throw new IOException("Invalid shared logical class contract", e);
        }
        return Map.copyOf(result);
    }

    private static void validateSharedContract(
        JsonObject descriptor,
        Map<String, byte[]> classes,
        Map<String, byte[]> parentDefinitions,
        Set<String> addedClasses,
        Map<String, String> sharedClasses
    ) throws IOException {
        if (!descriptor.has("sharedContract")) {
            return; // Immutable artifacts produced before canonical shared contracts remain replayable.
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        new java.util.TreeMap<>(sharedClasses).forEach((name, hash) -> {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(hash.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
        });
        String actualContract = HexFormat.of().formatHex(digest.digest());
        String expectedContract = descriptor.get("sharedContract").getAsString();
        if (!actualContract.equals(expectedContract)) {
            throw new IOException("Shared logical class contract digest mismatch");
        }
        for (Map.Entry<String, String> entry : sharedClasses.entrySet()) {
            byte[] bytes = classes.get(entry.getKey());
            if (bytes == null) {
                bytes = parentDefinitions.get(entry.getKey());
            }
            if (addedClasses.contains(entry.getKey()) && bytes == null) {
                throw new IOException("Added shared logical class bytes are missing for " + entry.getKey());
            }
            if (bytes != null && !sha256(bytes).equals(entry.getValue())) {
                throw new IOException("Shared logical class bytes do not match the contract for " + entry.getKey());
            }
        }
    }

    private static void validateHookMethods(Map<String, byte[]> classes, List<String> classNames, String methodName) throws IOException {
        String contextDescriptor = "(Lnet/minecraft/allcraft/AllcraftRuntime$MigrationContext;)V";
        for (String className : classNames) {
            byte[] bytes = classes.get(className);
            if (bytes != null && !hasMethod(bytes, methodName, "()V") && !hasMethod(bytes, methodName, contextDescriptor)) {
                throw new IOException("Staged lifecycle class " + className + " has no " + methodName + " method");
            }
        }
    }

    private static boolean hasMethod(byte[] bytes, String name, String descriptor) {
        ClassModel model = ClassFile.of().parse(bytes);
        return model.methods()
            .stream()
            .anyMatch(
                method -> method.methodName().stringValue().equals(name)
                    && method.methodType().stringValue().equals(descriptor)
                    && (method.flags().flagsMask() & ClassFile.ACC_STATIC) != 0
            );
    }

    private static byte[] currentDefinition(Class<?> type) throws IOException {
        byte[] current = CURRENT_DEFINITIONS.get(type);
        if (current != null) {
            return current;
        }
        rememberBaseDefinition(type);
        return CURRENT_DEFINITIONS.get(type);
    }

    private static void rememberBaseDefinition(Class<?> type) throws IOException {
        if (BASE_DEFINITIONS.containsKey(type) || ADDED_CLASSES.contains(type.getName())) {
            return;
        }
        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        try (InputStream input = loader == null ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Cannot capture installed definition for " + type.getName());
            }
            byte[] base = input.readAllBytes();
            BASE_DEFINITIONS.put(type, base);
            CURRENT_DEFINITIONS.putIfAbsent(type, base);
        }
    }

    private static void invokeHooks(List<String> classes, String methodName, MigrationContext context) throws Exception {
        ClassLoader loader = AllcraftRuntime.class.getClassLoader();
        for (String className : classes) {
            Class<?> type = Class.forName(className, true, loader);
            Method method;
            Object[] arguments;
            try {
                method = type.getDeclaredMethod(methodName, MigrationContext.class);
                arguments = new Object[]{context};
            } catch (NoSuchMethodException noContextMethod) {
                method = type.getDeclaredMethod(methodName);
                arguments = new Object[0];
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalStateException(className + "." + methodName + " must be static");
            }
            invoke(method, arguments);
        }
    }

    private static void invokeEntrypoints(List<String> entrypoints, ClassLoader loader) throws Exception {
        for (String className : entrypoints) {
            Class<?> type = Class.forName(className, true, loader);
            Method method = type.getDeclaredMethod("allcraftActivate");
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                throw new IllegalStateException(className + ".allcraftActivate must be a static no-argument method");
            }
            invoke(method, new Object[0]);
        }
    }

    private static void invoke(Method method, Object[] arguments) throws Exception {
        try {
            method.setAccessible(true);
            method.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static JsonObject readDescriptor(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            JarEntry entry = jar.getJarEntry("META-INF/allcraft-patch.json");
            if (entry == null) {
                return new JsonObject();
            }
            try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("Invalid runtime artifact descriptor in " + artifact.getFileName(), e);
            }
        }
    }

    private static Map<String, byte[]> readClasses(Path artifact, String prefix, boolean prefixedOnly) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            for (JarEntry entry : jar.stream().filter(value -> !value.isDirectory()).sorted(Comparator.comparing(JarEntry::getName)).toList()) {
                String name = entry.getName();
                boolean selected = prefixedOnly
                    ? name.startsWith(prefix) && name.endsWith(".class")
                    : name.endsWith(".class") && !name.startsWith("META-INF/");
                if (!selected) {
                    continue;
                }
                String classEntry = prefixedOnly ? name.substring(prefix.length()) : name;
                String className = classEntry.substring(0, classEntry.length() - 6).replace('/', '.');
                try (InputStream input = jar.getInputStream(entry)) {
                    classes.put(className, input.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static List<String> strings(JsonObject descriptor, String property) {
        if (!descriptor.has(property)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : descriptor.getAsJsonArray(property)) {
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }

    private static Map<String, Class<?>> loadedClasses(Instrumentation instrumentation, ClassLoader gameLoader) {
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (candidate.getClassLoader() == gameLoader) {
                loaded.putIfAbsent(candidate.getName(), candidate);
            }
        }
        return loaded;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, collector.getCollectionCount());
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, collector.getCollectionTime());
        }
        return total;
    }

    public static final class Transaction {
        private final Path artifact;
        private final String sha256;
        private final JsonObject descriptor;
        private final Map<String, byte[]> classes;
        private final Map<String, byte[]> tombstones;
        private final Map<String, byte[]> parentDefinitions;
        private final Set<String> addedClasses;
        private final Set<String> deletedClasses;
        private final Map<String, String> sharedClasses;
        private final Hooks hooks;
        private final List<String> entrypoints;
        private final Map<String, byte[]> stagedPrevious;
        private final Map<String, byte[]> publishedPrevious = new LinkedHashMap<>();
        private final Set<String> publishedClasses = new LinkedHashSet<>();
        private final Set<String> introducedClasses = new LinkedHashSet<>();
        private final MigrationContext context;
        private final AllcraftRegistries.Transaction registryTransaction;
        private boolean prepared;
        private boolean published;
        private boolean finished;
        private boolean rolledBack;
        private boolean sealed;
        private ApplyResult result;

        private Transaction(
            Path artifact,
            String sha256,
            JsonObject descriptor,
            Map<String, byte[]> classes,
            Map<String, byte[]> tombstones,
            Map<String, byte[]> parentDefinitions,
            Set<String> addedClasses,
            Set<String> deletedClasses,
            Map<String, String> sharedClasses,
            Hooks hooks,
            List<String> entrypoints,
            Map<String, byte[]> previous,
            MigrationContext context
        ) {
            this.artifact = artifact;
            this.sha256 = sha256;
            this.descriptor = descriptor;
            this.classes = classes;
            this.tombstones = tombstones;
            this.parentDefinitions = parentDefinitions;
            this.addedClasses = addedClasses;
            this.deletedClasses = deletedClasses;
            this.sharedClasses = sharedClasses;
            this.hooks = hooks;
            this.entrypoints = entrypoints;
            this.stagedPrevious = new LinkedHashMap<>(previous);
            this.context = context;
            this.registryTransaction = AllcraftRegistries.transaction(
                context.worldId(), context.side(), context.toRevision(), context.patchId()
            );
            this.registryTransaction.sharedClasses(sharedClasses.keySet(), classes.keySet(), descriptor.has("sharedContract"));
        }

        public synchronized ApplyResult publish() throws Exception {
            return AllcraftRuntime.publish(this);
        }

        public synchronized void finish() throws Exception {
            AllcraftRuntime.finish(this);
        }

        public synchronized void rollback() throws Exception {
            AllcraftRuntime.rollback(this);
        }

        public synchronized void seal() {
            AllcraftRuntime.seal(this);
        }

        public boolean published() {
            return this.published;
        }

        public boolean started() {
            return this.prepared || this.published;
        }

        public JsonObject descriptor() {
            return this.descriptor.deepCopy();
        }

        public MigrationContext context() {
            return this.context;
        }

        public synchronized void expectRegistryPlan(String plan) {
            this.registryTransaction.expect(plan);
        }

        public synchronized void registryAccess(net.minecraft.core.RegistryAccess registryAccess) {
            this.registryTransaction.registryAccess(registryAccess);
        }

        public synchronized String registryPlan() {
            return this.registryTransaction.planJson();
        }

        public synchronized boolean hasRegistryMutations() {
            return this.registryTransaction.changed();
        }

        public synchronized int registryMutationCount() {
            return this.registryTransaction.mutationCount();
        }
    }

    public static final class MigrationContext {
        private final String worldId;
        private final String side;
        private final long fromRevision;
        private final long toRevision;
        private final String patchId;
        private final Path artifact;
        private final Path checkpointFile;
        private final Map<String, Object> checkpoints = new LinkedHashMap<>();

        private MigrationContext(String worldId, String side, long fromRevision, long toRevision, String patchId, Path artifact) {
            this.worldId = worldId;
            this.side = side;
            this.fromRevision = fromRevision;
            this.toRevision = toRevision;
            this.patchId = patchId;
            this.artifact = artifact;
            this.checkpointFile = artifact.resolveSibling(artifact.getFileName() + ".migration.json");
        }

        public String worldId() {
            return this.worldId;
        }

        public String side() {
            return this.side;
        }

        public long fromRevision() {
            return this.fromRevision;
        }

        public long toRevision() {
            return this.toRevision;
        }

        public String patchId() {
            return this.patchId;
        }

        public Path artifact() {
            return this.artifact;
        }

        public Path checkpointFile() {
            return this.checkpointFile;
        }

        public void checkpoint(String key, Object value) {
            this.checkpoints.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T checkpoint(String key, Class<T> type) {
            Object value = this.checkpoints.get(key);
            return value == null ? null : (T)type.cast(value);
        }

        /** Stores a crash-safe string checkpoint next to this immutable artifact. */
        public synchronized void persistCheckpoint(String key, String value) throws IOException {
            JsonObject root = Files.isRegularFile(this.checkpointFile)
                ? JsonParser.parseString(Files.readString(this.checkpointFile, StandardCharsets.UTF_8)).getAsJsonObject()
                : new JsonObject();
            root.addProperty("format", 1);
            root.addProperty("worldId", this.worldId);
            root.addProperty("side", this.side);
            root.addProperty("patchId", this.patchId);
            JsonObject values = root.has("values") ? root.getAsJsonObject("values") : new JsonObject();
            values.addProperty(key, value);
            root.add("values", values);
            writeCheckpointAtomically(this.checkpointFile, root.toString() + System.lineSeparator());
        }

        /** Reads a checkpoint written by a live or crash-recovery invocation of this revision. */
        public synchronized String persistedCheckpoint(String key) throws IOException {
            if (!Files.isRegularFile(this.checkpointFile)) {
                return null;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(this.checkpointFile, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject values = root.has("values") ? root.getAsJsonObject("values") : new JsonObject();
                return values.has(key) ? values.get(key).getAsString() : null;
            } catch (RuntimeException e) {
                throw new IOException("Invalid migration checkpoint " + this.checkpointFile, e);
            }
        }
    }

    private static void writeCheckpointAtomically(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            contents,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record ApplyResult(
        int redefinedClasses,
        int addedClasses,
        int unchangedClasses,
        int retiredClasses,
        List<String> invokedEntrypoints,
        long totalMillis,
        long redefineMillis,
        long gcCollections,
        long gcMillis
    ) {
        public String summary() {
            return this.redefinedClasses
                + " redefined, "
                + this.addedClasses
                + " added, "
                + this.retiredClasses
                + " retired, "
                + this.unchangedClasses
                + " unchanged, "
                + this.invokedEntrypoints.size()
                + " activated in "
                + this.totalMillis
                + " ms";
        }
    }

    private record Hooks(List<String> prepare, List<String> migrate, List<String> commit, List<String> rollback) {
        private static Hooks from(JsonObject object) {
            return new Hooks(strings(object, "prepare"), strings(object, "migrate"), strings(object, "commit"), strings(object, "rollback"));
        }
    }

}
