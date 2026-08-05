package allcraft.generalitytest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.minecraft.allcraft.AllcraftRevisionBuilder;
import net.minecraft.allcraft.AllcraftRuntime;

/** End-to-end production-pipeline regression without starting the Minecraft renderer. */
public final class CodeGeneralityRegression {
    private CodeGeneralityRegression() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Usage: CodeGeneralityRegression <javac> <base-jar> <work-dir>");
        }
        Path javac = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path baseJar = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path work = Path.of(arguments[2]).toAbsolutePath().normalize();
        deleteTree(work);
        Files.createDirectories(work);
        System.setProperty("allcraft.javac", javac.toString());
        System.setProperty("allcraft.baseJar", baseJar.toString());

        testSharedContractGuards(work);

        Path firstWorld = world(work.resolve("world-a"));
        AllcraftRevisionBuilder.initializeBaseline(firstWorld);

        // AI integration builds from a private checkout without mutating authoritative source.
        Path privateSource = work.resolve("private-candidate-source");
        copyTree(firstWorld.resolve("source"), privateSource);
        Path privateClass = privateSource.resolve("client/allcraft/generality/PrivateCandidate.java");
        Files.createDirectories(privateClass.getParent());
        Files.writeString(
            privateClass,
            "package allcraft.generality; public final class PrivateCandidate { public static int value() { return 7; } }\n",
            StandardCharsets.UTF_8
        );
        AllcraftRevisionBuilder.PreparedRevision privateBuild = AllcraftRevisionBuilder.prepare(
            firstWorld, privateSource, AllcraftRevisionBuilder.Request.production("private-candidate")
        );
        require(
            privateBuild.client().addedClasses().contains("allcraft.generality.PrivateCandidate"),
            "private source checkout was not compiled"
        );
        require(
            !Files.exists(firstWorld.resolve("source/client/allcraft/generality/PrivateCandidate.java")),
            "private build mutated canonical source"
        );
        AllcraftRevisionBuilder.commit(privateBuild);
        AllcraftRevisionBuilder.rollbackCommit(privateBuild);
        require(
            JsonParser.parseString(Files.readString(firstWorld.resolve("patches/revisions/current-source.json")))
                    .getAsJsonObject()
                    .get("revision")
                    .getAsLong()
                == 0L,
            "aborted pre-manifest source snapshot did not restore its parent"
        );

        writeVersionOne(firstWorld.resolve("source"), "a");
        AllcraftRevisionBuilder.PreparedRevision revisionOne = AllcraftRevisionBuilder.prepare(
            firstWorld, AllcraftRevisionBuilder.Request.production("generic-v1")
        );
        assertArtifact(
            revisionOne.clientArtifactPath(),
            1L,
            List.of("allcraft.generality.Probe", "allcraft.generality.ProbeConsumer", "allcraft.generality.RevisionOneHooks"),
            List.of()
        );
        assertArtifact(revisionOne.serverArtifactPath(), 1L, List.of("allcraft.generality.ServerProbe"), List.of());
        require(jarContains(revisionOne.clientArtifactPath(), "assets/allcraft/allcraft-test/one.txt"), "client resource was misclassified");
        require(jarContains(revisionOne.serverArtifactPath(), "data/allcraft/allcraft-test/one.json"), "server data was misclassified");
        AllcraftRuntime.Transaction first = AllcraftRuntime.stage(revisionOne.clientArtifactPath(), revisionOne.clientSha256());
        AllcraftRuntime.ApplyResult firstResult = first.publish();
        require(firstResult.addedClasses() >= 3, "revision one did not add all arbitrary client classes");
        require("v1-a".equals(callProbe()), "revision one method was not installed");
        require(probeValue() == 11, "revision one live/static migration did not run");
        first.finish();
        commit(firstWorld, revisionOne);
        first.seal();
        require("committed-v1".equals(System.getProperty("allcraft.generality.phase")), "revision one commit hook did not run");

        writeVersionTwo(firstWorld.resolve("source"), "a");
        AllcraftRevisionBuilder.PreparedRevision revisionTwo = AllcraftRevisionBuilder.prepare(
            firstWorld, AllcraftRevisionBuilder.Request.production("generic-v2")
        );
        assertArtifact(revisionTwo.clientArtifactPath(), 2L, List.of("allcraft.generality.RevisionTwoHooks"), List.of());
        require(
            revisionTwo.client().compiledSources().contains("client/allcraft/generality/ProbeConsumer.java"),
            "reverse dependency closure did not recompile an unchanged dependent source"
        );
        require(
            revisionTwo.movedFiles().contains(
                "client/assets/allcraft/allcraft-test/one.txt -> client/assets/allcraft/allcraft-test/two.txt"
            ),
            "resource move was not detected"
        );
        AllcraftRevisionBuilder.PreparedRevision cached = AllcraftRevisionBuilder.prepare(
            firstWorld, AllcraftRevisionBuilder.Request.production("generic-v2-cache")
        );
        require(cached.client().cacheHit(), "content-addressed client compiler cache did not hit");
        AllcraftRevisionBuilder.discard(cached);
        AllcraftRuntime.Transaction second = AllcraftRuntime.stage(revisionTwo.clientArtifactPath(), revisionTwo.clientSha256());
        second.publish();
        require("v2-a:structural".equals(callProbe()), "method/field structural evolution was not installed");
        require(probeValue() == 22, "revision two migration did not run");
        second.finish();
        commit(firstWorld, revisionTwo);
        second.seal();
        Object liveProbe = probeClass().getConstructor().newInstance();
        require("live-v2-a".equals(callLiveProbe(liveProbe)), "revision two live object was not created");

        // A malformed arbitrary edit must fail before an artifact is activated or a snapshot advances.
        Path broken = firstWorld.resolve("source/client/allcraft/generality/Broken.java");
        Files.writeString(broken, "package allcraft.generality; public class Broken { this is not Java; }\n", StandardCharsets.UTF_8);
        boolean compileFailed = false;
        try {
            AllcraftRevisionBuilder.prepare(firstWorld, AllcraftRevisionBuilder.Request.production("compile-failure"));
        } catch (Exception expected) {
            compileFailed = expected.getMessage() != null && expected.getMessage().contains("compilation failed");
        }
        require(compileFailed, "invalid arbitrary source did not fail compilation");
        require(currentRevision(firstWorld) == 2L, "failed compilation advanced the world revision");
        Files.delete(broken);

        // A migration failure must restore class definitions and the migration's own state checkpoint.
        writeFailingMigration(firstWorld.resolve("source"));
        AllcraftRevisionBuilder.PreparedRevision failing = AllcraftRevisionBuilder.prepare(
            firstWorld, AllcraftRevisionBuilder.Request.production("migration-failure")
        );
        AllcraftRuntime.Transaction failed = AllcraftRuntime.stage(failing.clientArtifactPath(), failing.clientSha256());
        boolean migrationFailed = false;
        try {
            failed.publish();
        } catch (Exception expected) {
            migrationFailed = expected.getMessage() != null && expected.getMessage().contains("intentional migration failure");
            failed.rollback();
        }
        require(migrationFailed, "intentional migration failure did not propagate");
        require("v2-a:structural".equals(callProbe()) && probeValue() == 22, "failed migration did not roll back completely");
        require(currentRevision(firstWorld) == 2L, "failed migration advanced the manifest");
        AllcraftRuntime.recoverRollback(failing.clientArtifactPath(), failing.clientSha256());
        require("yes".equals(System.getProperty("allcraft.generality.crashRollback")), "crash-recovery rollback hook did not run");
        AllcraftRevisionBuilder.discard(failing);
        Files.delete(firstWorld.resolve("source/client/allcraft/generality/FailingHooks.java"));
        writeHookConfig(firstWorld.resolve("source"), "allcraft.generality.RevisionTwoHooks");

        // Removing world classes retires source reachability without destroying executable bodies
        // that may still be owned by live objects, lambdas, callbacks, or stack frames.
        deleteGeneralitySources(firstWorld.resolve("source"));
        deleteAdditionalSources(firstWorld.resolve("source"));
        Files.delete(firstWorld.resolve("source/allcraft-revision.json"));
        AllcraftRevisionBuilder.PreparedRevision deletion = AllcraftRevisionBuilder.prepare(
            firstWorld, AllcraftRevisionBuilder.Request.production("generic-delete")
        );
        assertArtifact(
            deletion.clientArtifactPath(),
            3L,
            List.of(),
            List.of(
                "allcraft.generality.Probe",
                "allcraft.generality.ProbeConsumer",
                "allcraft.generality.RevisionOneHooks",
                "allcraft.generality.RevisionTwoHooks"
            )
        );
        AllcraftRuntime.Transaction retired = AllcraftRuntime.stage(deletion.clientArtifactPath(), deletion.clientSha256());
        require(retired.publish().retiredClasses() >= 3, "deleted classes were not retired");
        require("v2-a:structural".equals(callProbe()), "logical retirement destroyed a still-live class definition");
        require("live-v2-a".equals(callLiveProbe(liveProbe)), "class retirement destroyed a live object's method body");
        retired.finish();
        commit(firstWorld, deletion);
        retired.seal();

        // Leaving the world rolls committed revisions back in reverse order, then another world's
        // identically-named additions can replace the safely retained process-lifetime definition.
        AllcraftRuntime.resetToBase();
        require(callProbe().startsWith("v1-a"), "world exit destroyed a world-only class still reachable by live Java references");
        require("live-v1-a".equals(callLiveProbe(liveProbe)), "world reset destroyed a retained live object");
        Path secondWorld = world(work.resolve("world-b"));
        AllcraftRevisionBuilder.initializeBaseline(secondWorld);
        writeVersionOne(secondWorld.resolve("source"), "b");
        AllcraftRevisionBuilder.PreparedRevision other = AllcraftRevisionBuilder.prepare(
            secondWorld, AllcraftRevisionBuilder.Request.production("other-world")
        );
        AllcraftRuntime.Transaction otherTransaction = AllcraftRuntime.stage(other.clientArtifactPath(), other.clientSha256());
        otherTransaction.publish();
        require("v1-b".equals(callProbe()), "world switch did not reconcile a previously retired class");
        otherTransaction.finish();
        commit(secondWorld, other);
        otherTransaction.seal();
        AllcraftRuntime.resetToBase();

        // Reopening the first world replays its immutable artifacts deterministically, including
        // historical hook implementations and logical class retirement.
        replay(firstWorld);
        require("v2-a:structural".equals(callProbe()), "reconnect replay did not restore safe logical retirement");
        AllcraftRuntime.resetToBase();

        System.out.println("PASS code-generality: differ, shared contracts, cache, structural DCEVM, migrations, rollback, safe retirement, world switch, replay");
    }

    private static void testSharedContractGuards(Path work) throws Exception {
        Path missingLifecycle = world(work.resolve("missing-lifecycle"));
        AllcraftRevisionBuilder.initializeBaseline(missingLifecycle);
        Files.writeString(
            missingLifecycle.resolve("source/allcraft-revision.json"),
            "{\"client\":{\"migrate\":[\"allcraft.missing.NeverCommitted\"]}}\n",
            StandardCharsets.UTF_8
        );
        boolean missingRejected = false;
        try {
            AllcraftRevisionBuilder.prepare(missingLifecycle, AllcraftRevisionBuilder.Request.production("missing-lifecycle"));
        } catch (Exception expected) {
            missingRejected = expected.getMessage() != null && expected.getMessage().contains("unavailable class");
        }
        require(missingRejected, "missing lifecycle class was allowed to depend on aborted JVM residue");

        Path divergent = world(work.resolve("contract-divergent"));
        AllcraftRevisionBuilder.initializeBaseline(divergent);
        Path divergentClient = divergent.resolve("source/client/allcraft/contract/DivergentClient.java");
        Path divergentServer = divergent.resolve("source/server/allcraft/contract/DivergentServer.java");
        Files.createDirectories(divergentClient.getParent());
        Files.createDirectories(divergentServer.getParent());
        Files.writeString(
            divergentClient,
            "package allcraft.contract; import net.minecraft.allcraft.AllcraftRegistries; "
                + "public final class DivergentClient { public static void mutate() { AllcraftRegistries.registry(null); } }\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            divergentServer,
            "package allcraft.contract; import net.minecraft.allcraft.AllcraftRegistries; "
                + "public final class DivergentServer { public static void mutate() { AllcraftRegistries.registry(null); } }\n",
            StandardCharsets.UTF_8
        );
        boolean divergenceRejected = false;
        try {
            AllcraftRevisionBuilder.prepare(divergent, AllcraftRevisionBuilder.Request.production("divergent-types"));
        } catch (Exception expected) {
            divergenceRejected = expected.getMessage() != null && expected.getMessage().contains("Side-only class");
        }
        require(divergenceRejected, "side-divergent registry logic was not rejected before artifact staging");

        Path canonical = world(work.resolve("contract-canonical"));
        AllcraftRevisionBuilder.initializeBaseline(canonical);
        Path shared = canonical.resolve("source/shared/allcraft/contract/CanonicalLogical.java");
        Files.createDirectories(shared.getParent());
        Files.writeString(
            shared,
            "package allcraft.contract; public final class CanonicalLogical { public static String identity() { return \"shared\"; } }\n",
            StandardCharsets.UTF_8
        );
        AllcraftRevisionBuilder.PreparedRevision prepared = AllcraftRevisionBuilder.prepare(
            canonical, AllcraftRevisionBuilder.Request.production("shared-contract")
        );
        AllcraftRuntime.stage(prepared.clientArtifactPath(), prepared.clientSha256());
        Path tampered = canonical.resolve("patches/artifacts/client/tampered-contract.jar");
        tamperSharedContract(prepared.clientArtifactPath(), tampered);
        boolean tamperRejected = false;
        try {
            AllcraftRuntime.stage(tampered, sha256(tampered));
        } catch (Exception expected) {
            tamperRejected = expected.getMessage() != null && expected.getMessage().contains("contract digest mismatch");
        }
        require(tamperRejected, "tampered shared logical class contract reached runtime staging");
        AllcraftRevisionBuilder.discard(prepared);
    }

    private static void tamperSharedContract(Path source, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        try (JarFile input = new JarFile(source.toFile()); JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination))) {
            for (JarEntry entry : input.stream().filter(value -> !value.isDirectory()).toList()) {
                byte[] bytes = input.getInputStream(entry).readAllBytes();
                if (entry.getName().equals("META-INF/allcraft-patch.json")) {
                    JsonObject descriptor = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    descriptor.addProperty("sharedContract", "0".repeat(64));
                    bytes = (descriptor + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                }
                output.putNextEntry(new JarEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Path world(Path root) throws Exception {
        Files.createDirectories(root.resolve("source/client"));
        Files.createDirectories(root.resolve("source/server"));
        Files.createDirectories(root.resolve("patches/artifacts/client"));
        Files.createDirectories(root.resolve("patches/artifacts/server"));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", 1);
        manifest.addProperty("serverId", UUID.randomUUID().toString());
        manifest.addProperty("worldId", UUID.randomUUID().toString());
        manifest.addProperty("baseVersion", "regression");
        manifest.addProperty("currentRevision", 0);
        manifest.add("patches", new JsonArray());
        writeJson(root.resolve("patches/manifest.json"), manifest);
        return root;
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void writeVersionOne(Path source, String world) throws Exception {
        Path javaRoot = source.resolve("client/allcraft/generality");
        Files.createDirectories(javaRoot);
        Files.writeString(
            javaRoot.resolve("Probe.java"),
            """
            package allcraft.generality;
            public class Probe {
                public static int value = 1;
                public static String message() { return "v1-%s"; }
                public String liveMessage() { return "live-v1-%s"; }
            }
            """.formatted(world, world),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            javaRoot.resolve("ProbeConsumer.java"),
            """
            package allcraft.generality;
            public final class ProbeConsumer {
                public static String consume() { return Probe.message(); }
            }
            """,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            javaRoot.resolve("RevisionOneHooks.java"),
            """
            package allcraft.generality;
            import net.minecraft.allcraft.AllcraftRuntime.MigrationContext;
            public final class RevisionOneHooks {
                public static void allcraftPrepare(MigrationContext context) throws Exception {
                    context.persistCheckpoint("probe", Integer.toString(Probe.value));
                }
                public static void allcraftMigrate(MigrationContext context) { Probe.value = 11; }
                public static void allcraftCommit(MigrationContext context) { System.setProperty("allcraft.generality.phase", "committed-v1"); }
                public static void allcraftRollback(MigrationContext context) throws Exception {
                    String value = context.persistedCheckpoint("probe");
                    if (value != null) Probe.value = Integer.parseInt(value);
                }
            }
            """,
            StandardCharsets.UTF_8
        );
        Path serverRoot = source.resolve("server/allcraft/generality");
        Files.createDirectories(serverRoot);
        Files.writeString(
            serverRoot.resolve("ServerProbe.java"),
            "package allcraft.generality; public final class ServerProbe { public static String side() { return \"server\"; } }\n",
            StandardCharsets.UTF_8
        );
        Path clientResource = source.resolve("client/assets/allcraft/allcraft-test/one.txt");
        Files.createDirectories(clientResource.getParent());
        Files.writeString(clientResource, "resource-move\n", StandardCharsets.UTF_8);
        Path serverData = source.resolve("server/data/allcraft/allcraft-test/one.json");
        Files.createDirectories(serverData.getParent());
        Files.writeString(serverData, "{\"revision\":1}\n", StandardCharsets.UTF_8);
        writeHookConfig(source, "allcraft.generality.RevisionOneHooks");
    }

    private static void writeVersionTwo(Path source, String world) throws Exception {
        Path javaRoot = source.resolve("client/allcraft/generality");
        Files.writeString(
            javaRoot.resolve("Probe.java"),
            """
            package allcraft.generality;
            public class Probe {
                public static int value = 11;
                public static String addedField = "structural";
                public static String message() { return "v2-%s:" + addedField; }
                public String liveMessage() { return "live-v2-%s"; }
                public static long addedMethod(long input) { return input * 2L; }
            }
            """.formatted(world, world),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            javaRoot.resolve("RevisionTwoHooks.java"),
            """
            package allcraft.generality;
            import net.minecraft.allcraft.AllcraftRuntime.MigrationContext;
            public final class RevisionTwoHooks {
                public static void allcraftPrepare(MigrationContext context) throws Exception {
                    context.persistCheckpoint("probe", Integer.toString(Probe.value));
                }
                public static void allcraftMigrate(MigrationContext context) { Probe.value = 22; Probe.addedField = "structural"; }
                public static void allcraftCommit(MigrationContext context) { }
                public static void allcraftRollback(MigrationContext context) throws Exception {
                    String value = context.persistedCheckpoint("probe");
                    if (value != null) Probe.value = Integer.parseInt(value);
                }
            }
            """,
            StandardCharsets.UTF_8
        );
        Path oldResource = source.resolve("client/assets/allcraft/allcraft-test/one.txt");
        Path movedResource = source.resolve("client/assets/allcraft/allcraft-test/two.txt");
        Files.createDirectories(movedResource.getParent());
        Files.move(oldResource, movedResource);
        Files.writeString(source.resolve("server/data/allcraft/allcraft-test/one.json"), "{\"revision\":2}\n", StandardCharsets.UTF_8);
        writeHookConfig(source, "allcraft.generality.RevisionTwoHooks");
    }

    private static void writeFailingMigration(Path source) throws Exception {
        Path hook = source.resolve("client/allcraft/generality/FailingHooks.java");
        Files.writeString(
            hook,
            """
            package allcraft.generality;
            import net.minecraft.allcraft.AllcraftRuntime.MigrationContext;
            public final class FailingHooks {
                public static void allcraftPrepare(MigrationContext context) throws Exception {
                    context.persistCheckpoint("probe", Integer.toString(Probe.value));
                }
                public static void allcraftMigrate(MigrationContext context) {
                    Probe.value = 999;
                    throw new IllegalStateException("intentional migration failure");
                }
                public static void allcraftCommit(MigrationContext context) { }
                public static void allcraftRollback(MigrationContext context) throws Exception {
                    Probe.value = Integer.parseInt(context.persistedCheckpoint("probe"));
                    System.setProperty("allcraft.generality.crashRollback", "yes");
                }
            }
            """,
            StandardCharsets.UTF_8
        );
        writeHookConfig(source, "allcraft.generality.FailingHooks");
    }

    private static void writeHookConfig(Path source, String hook) throws Exception {
        JsonObject phase = new JsonObject();
        for (String name : List.of("prepare", "migrate", "commit", "rollback")) {
            JsonArray values = new JsonArray();
            values.add(hook);
            phase.add(name, values);
        }
        JsonObject root = new JsonObject();
        root.add("client", phase);
        writeJson(source.resolve("allcraft-revision.json"), root);
    }

    private static void deleteGeneralitySources(Path source) throws Exception {
        Path root = source.resolve("client/allcraft/generality");
        if (Files.isDirectory(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void deleteAdditionalSources(Path source) throws Exception {
        for (Path root : List.of(source.resolve("server/allcraft/generality"), source.resolve("client/assets"), source.resolve("server/data"))) {
            if (!Files.exists(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void commit(Path world, AllcraftRevisionBuilder.PreparedRevision prepared) throws Exception {
        AllcraftRevisionBuilder.commit(prepared);
        Path path = world.resolve("patches/manifest.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.addProperty("currentRevision", prepared.revision());
        JsonObject patch = new JsonObject();
        patch.addProperty("revision", prepared.revision());
        patch.addProperty("patchId", prepared.patchId());
        patch.addProperty("clientSha256", prepared.clientSha256());
        patch.addProperty("serverSha256", prepared.serverSha256());
        manifest.getAsJsonArray("patches").add(patch);
        writeJson(path, manifest);
    }

    private static void replay(Path world) throws Exception {
        JsonObject manifest = JsonParser.parseString(
            Files.readString(world.resolve("patches/manifest.json"), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        for (var element : manifest.getAsJsonArray("patches")) {
            JsonObject patch = element.getAsJsonObject();
            String stem = String.format("%08d-%s.jar", patch.get("revision").getAsLong(), patch.get("patchId").getAsString());
            Path artifact = world.resolve("patches/artifacts/client").resolve(stem);
            AllcraftRuntime.apply(artifact, patch.get("clientSha256").getAsString());
        }
    }

    private static void assertArtifact(Path artifact, long revision, List<String> added, List<String> deleted) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            JsonObject descriptor = JsonParser.parseString(
                new String(jar.getInputStream(jar.getJarEntry("META-INF/allcraft-patch.json")).readAllBytes(), StandardCharsets.UTF_8)
            ).getAsJsonObject();
            require(descriptor.get("revision").getAsLong() == revision, "artifact revision mismatch");
            for (String className : added) {
                require(contains(descriptor.getAsJsonArray("addedClasses"), className), "missing added-class declaration " + className);
            }
            for (String className : deleted) {
                require(contains(descriptor.getAsJsonArray("deletedClasses"), className), "missing deleted-class declaration " + className);
                require(
                    jar.getJarEntry("META-INF/allcraft-tombstones/" + className.replace('.', '/') + ".class") != null,
                    "missing tombstone for " + className
                );
            }
        }
    }

    private static boolean contains(JsonArray values, String expected) {
        for (var value : values) {
            if (expected.equals(value.getAsString())) return true;
        }
        return false;
    }

    private static boolean jarContains(Path artifact, String entry) throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            return jar.getJarEntry(entry) != null;
        }
    }

    private static String callProbe() throws Exception {
        return (String)probeClass().getMethod("message").invoke(null);
    }

    private static int probeValue() throws Exception {
        return probeClass().getField("value").getInt(null);
    }

    private static String callLiveProbe(Object probe) throws Exception {
        return (String)probeClass().getMethod("liveMessage").invoke(probe);
    }

    private static Class<?> probeClass() throws Exception {
        return Class.forName("allcraft.generality.Probe", false, CodeGeneralityRegression.class.getClassLoader());
    }

    private static long currentRevision(Path world) throws Exception {
        return JsonParser.parseString(Files.readString(world.resolve("patches/manifest.json"), StandardCharsets.UTF_8))
            .getAsJsonObject()
            .get("currentRevision")
            .getAsLong();
    }

    private static void writeJson(Path path, JsonObject value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
