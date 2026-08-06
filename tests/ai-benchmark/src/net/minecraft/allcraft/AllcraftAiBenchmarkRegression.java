package net.minecraft.allcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class AllcraftAiBenchmarkRegression {
    private AllcraftAiBenchmarkRegression() {
    }

    public static void main(String[] args) throws Exception {
        testDefinitionsAndRouting();
        testPersistentManifestAndGating();
        testAtomicOrderedBatchesAndCapacity();
        testInterruptedBatchRecovery();
        System.out.println("Allcraft AI benchmark regression passed");
    }

    private static void testDefinitionsAndRouting() {
        require(AllcraftAiTestSuites.PHASE_A.size() == 12, "phase A has twelve requests");
        require(AllcraftAiTestSuites.PHASE_B.size() == 4, "phase B has four requests");
        List<AllcraftAiTestSuites.BenchmarkCase> all = new ArrayList<>(AllcraftAiTestSuites.PHASE_A);
        all.addAll(AllcraftAiTestSuites.PHASE_B);
        require(new HashSet<>(all.stream().map(AllcraftAiTestSuites.BenchmarkCase::id).toList()).size() == 16, "case IDs are unique");
        require(AllcraftAiTestSuites.cases("suite-1-a").equals(AllcraftAiTestSuites.PHASE_A), "phase A lookup");
        require(AllcraftAiTestSuites.cases("suite-1-b").equals(AllcraftAiTestSuites.PHASE_B), "phase B lookup");
        require(AllcraftAiTestSuites.cases("unknown").isEmpty(), "unknown suite lookup");
        require(AllcraftPatchServer.TEST_NAMES.containsAll(AllcraftAiTestSuites.TEST_NAMES), "command routing advertises both suites");

        String requests = all.stream().map(AllcraftAiTestSuites.BenchmarkCase::request).reduce("", (left, right) -> left + "\n" + right);
        for (String id : List.of(
            "allcraft:ruby", "allcraft:spring_block", "allcraft:echo_cow", "allcraft:lapis_alchemy_table",
            "allcraft:comet", "allcraft:moonlight_disc"
        )) require(requests.contains(id), "stable resource ID " + id);
        require(AllcraftAiTestSuites.PHASE_B.get(2).id().equals("double-jump-cooldown"), "first conflict case ordered third");
        require(AllcraftAiTestSuites.PHASE_B.get(3).id().equals("double-jump-hunger"), "second conflict case ordered fourth");
    }

    private static void testPersistentManifestAndGating() throws Exception {
        Path world = Files.createTempDirectory("allcraft-ai-suite-manifest-");
        try {
            AllcraftAiTestSuites.SuiteRun run = AllcraftAiTestSuites.newRun(17L);
            for (int index = 0; index < 12; index++) {
                run.phaseAJobs.add(new AllcraftAiTestSuites.JobReference("case-" + index, id(index)));
            }
            run.phaseAState = "running";
            run.observed = new AllcraftAiTestSuites.Progress(12, 5, 1, 6, 19, 22L, 4);
            run.observedJobs.add(new AllcraftAiTestSuites.ObservedJob("case-0", id(0), "finalized", 2, 18L, true, ""));
            AllcraftAiTestSuites.persist(world, run);

            AllcraftAiTestSuites.SuiteRun restored = AllcraftAiTestSuites.loadCurrent(world);
            require(restored != null && restored.runId.equals(run.runId), "suite run ID persists");
            require(restored.baseRevision == 17L, "suite base revision persists");
            require(restored.phaseAJobs.equals(run.phaseAJobs), "phase job IDs persist");
            require(restored.observed.equals(run.observed), "aggregate progress persists");
            require(restored.observedJobs.equals(run.observedJobs), "per-case results persist");
            require(
                AllcraftAiTestSuites.phaseADecision(restored) == AllcraftAiTestSuites.PhaseADecision.ALREADY_LAUNCHED,
                "duplicate phase A launch is rejected"
            );

            List<AllcraftAiJobs.JobSnapshot> finalized = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                finalized.add(new AllcraftAiJobs.JobSnapshot(
                    id(index), "case-" + index, "finalized", 1, 18L + index, true, index + 1L, ""
                ));
            }
            require(
                AllcraftAiTestSuites.phaseBDecision(restored, finalized) == AllcraftAiTestSuites.PhaseBDecision.SHOW_CHECKLIST,
                "first phase B invocation shows checklist"
            );
            restored.phaseBState = "awaiting-preparation";
            require(
                AllcraftAiTestSuites.phaseBDecision(restored, finalized) == AllcraftAiTestSuites.PhaseBDecision.LAUNCH,
                "second phase B invocation confirms preparation"
            );
            List<AllcraftAiJobs.JobSnapshot> incomplete = new ArrayList<>(finalized);
            incomplete.set(0, new AllcraftAiJobs.JobSnapshot(id(0), "case-0", "editing", 1, -1L, false, 1L, ""));
            require(
                AllcraftAiTestSuites.phaseBDecision(restored, incomplete) == AllcraftAiTestSuites.PhaseBDecision.BLOCKED,
                "phase B blocks until every phase A job finalizes"
            );
            restored.phaseBJobs.add(new AllcraftAiTestSuites.JobReference("evolution", id(100)));
            require(
                AllcraftAiTestSuites.phaseBDecision(restored, finalized) == AllcraftAiTestSuites.PhaseBDecision.ALREADY_LAUNCHED,
                "duplicate phase B launch is rejected"
            );
        } finally {
            deleteTree(world);
        }
    }

    private static void testAtomicOrderedBatchesAndCapacity() throws Exception {
        Path world = Files.createTempDirectory("allcraft-ai-suite-batch-");
        try {
            Files.createDirectories(world.resolve("source"));
            Files.writeString(world.resolve("source/baseline.txt"), "baseline\n");
            Files.createDirectories(world.resolve("patches"));
            Files.writeString(world.resolve("patches/manifest.json"), "{\"currentRevision\":0}\n");
            AllcraftSourceRepository.initialize(world, 0L);

            List<AllcraftAiJobs.BatchRequest> requests = AllcraftAiTestSuites.PHASE_A.stream()
                .map(test -> new AllcraftAiJobs.BatchRequest(test.id(), test.request()))
                .toList();
            AllcraftAiJobs.BatchResult result = AllcraftAiJobs.enqueueBatch(world, "suite-run", "a", requests, true);
            require(result.baseRevision() == 0L && result.jobs().size() == 12, "twelve-job batch queued at one base revision");
            for (int index = 0; index < result.jobs().size(); index++) {
                AllcraftAiJobs.BatchJob job = result.jobs().get(index);
                require(job.caseId().equals(requests.get(index).caseId()), "case ordering retained");
                require(job.sequence() == index + 1L, "strict sequence ordering retained");
                Path state = world.resolve("patches/ai/jobs").resolve(job.jobId()).resolve("job.json");
                JsonObject json = JsonParser.parseString(Files.readString(state)).getAsJsonObject();
                require(json.get("suiteRunId").getAsString().equals("suite-run"), "suite run metadata persisted");
                require(json.get("suitePhase").getAsString().equals("a"), "suite phase metadata persisted");
                require(json.get("suiteCaseId").getAsString().equals(job.caseId()), "suite case metadata persisted");
                require(json.get("baseRevision").getAsLong() == 0L, "shared base revision persisted");
            }
            require(!Files.exists(world.resolve("patches/ai/batches")) || directoryEmpty(world.resolve("patches/ai/batches")), "committed journal removed");

            long before = countJobFiles(world);
            boolean duplicateRejected = false;
            try {
                AllcraftAiJobs.enqueueBatch(
                    world,
                    "bad-run",
                    "a",
                    List.of(new AllcraftAiJobs.BatchRequest("same", "one"), new AllcraftAiJobs.BatchRequest("same", "two")),
                    true
                );
            } catch (Exception expected) {
                duplicateRejected = expected.getMessage().contains("Duplicate");
            }
            require(duplicateRejected && countJobFiles(world) == before, "invalid batch persists no jobs");

            List<AllcraftAiJobs.BatchRequest> twenty = new ArrayList<>();
            for (int index = 0; index < 20; index++) twenty.add(new AllcraftAiJobs.BatchRequest("capacity-" + index, "request " + index));
            AllcraftAiJobs.enqueueBatch(world, "capacity", "a", twenty, true);
            boolean capacityRejected = false;
            try {
                AllcraftAiJobs.enqueueBatch(
                    world, "over-capacity", "a", List.of(new AllcraftAiJobs.BatchRequest("overflow", "request")), true
                );
            } catch (Exception expected) {
                capacityRejected = expected.getMessage().contains("capacity");
            }
            require(capacityRejected && countJobFiles(world) == 32L, "global 32-editor demand cap enforced atomically");
        } finally {
            deleteTree(world);
        }
    }

    private static void testInterruptedBatchRecovery() throws Exception {
        Path world = Files.createTempDirectory("allcraft-ai-suite-recovery-");
        try {
            String partial = id(999);
            Path jobDirectory = world.resolve("patches/ai/jobs").resolve(partial);
            Files.createDirectories(jobDirectory);
            Files.writeString(jobDirectory.resolve("job.json"), "{\"partial\":true}\n");
            Path journal = world.resolve("patches/ai/batches/interrupted.json");
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, "{\"format\":1,\"batchId\":\"interrupted\",\"state\":\"preparing\",\"jobs\":[\"" + partial + "\"]}\n");
            require(AllcraftAiJobs.suiteJobs(world, "none", "a").isEmpty(), "recovery exposes no partial batch jobs");
            require(!Files.exists(jobDirectory) && !Files.exists(journal), "pre-commit batch residue rolled back on load");
        } finally {
            deleteTree(world);
        }
    }

    private static long countJobFiles(Path world) throws Exception {
        Path jobs = world.resolve("patches/ai/jobs");
        if (!Files.isDirectory(jobs)) return 0L;
        try (var paths = Files.walk(jobs)) {
            return paths.filter(path -> path.getFileName().toString().equals("job.json")).count();
        }
    }

    private static boolean directoryEmpty(Path path) throws Exception {
        if (!Files.isDirectory(path)) return true;
        try (var children = Files.list(path)) {
            return children.findAny().isEmpty();
        }
    }

    private static String id(int value) {
        return String.format("00000000-0000-0000-0000-%012d", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
