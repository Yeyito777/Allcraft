package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Definitions and orchestration for expensive, real-agent gameplay benchmarks. */
public final class AllcraftAiTestSuites {
    public static final String PHASE_A_NAME = "suite-1-a";
    public static final String PHASE_B_NAME = "suite-1-b";
    public static final List<String> TEST_NAMES = List.of(PHASE_A_NAME, PHASE_B_NAME);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SUITE_ID = "suite-1";

    static final List<BenchmarkCase> PHASE_A = List.of(
        new BenchmarkCase("double-jump", """
            Add a double-jump mechanic. A survival player may press jump once while airborne to jump again. It resets after touching the ground. Play a subtle original sound and use an original particle texture when the second jump happens.
            """),
        new BenchmarkCase("blink", """
            Add a keybind named Blink, bound to B by default. Pressing it teleports the player up to five blocks forward if the destination is safe. The server must authorize the teleport. Give it a three-second cooldown and show the remaining cooldown above the hotbar.
            """),
        new BenchmarkCase("ruby-item", """
            Add an item with the exact resource ID allcraft:ruby. Give it the translated English name Ruby, an item model, a crafting recipe, and creative inventory presence. Craft it from one diamond surrounded by redstone. Give it an original red gemstone texture rather than referencing a vanilla texture.
            """),
        new BenchmarkCase("spring-block", """
            Add a block with the exact resource ID allcraft:spring_block. It should be craftable, obtainable in creative mode, drop itself when broken, and launch entities upward when they land on it. Give it a distinctive original model and texture rather than referencing vanilla block textures.
            """),
        new BenchmarkCase("echo-cow", """
            Add a mob with the exact resource ID allcraft:echo_cow and the translated name Echo Cow. It should behave like a cow, have twice as much health, periodically emit note particles, drop an additional amethyst shard, and be summonable with /summon allcraft:echo_cow. Give it an original cow-like model and texture rather than reusing the cow renderer or texture.
            """),
        new BenchmarkCase("lapis-alchemy-table", """
            Add a block with the exact resource ID allcraft:lapis_alchemy_table and the translated name Lapis Alchemy Table. Give it its own menu and screen, with original block, item, and menu textures. It should accept one lapis lazuli and one iron ingot and produce one diamond after five seconds. It must preserve its inventory when the menu closes and when the world is saved and reopened.
            """),
        new BenchmarkCase("comet-particle", """
            Add a particle type with the exact resource ID allcraft:comet that can be spawned using the normal /particle command. It should move forward while leaving a short sparkling trail. Give it original particle sprites rather than referencing vanilla sprite textures.
            """),
        new BenchmarkCase("moonlight-disc", """
            Add a craftable music disc item with the exact resource ID allcraft:moonlight_disc and the translated name Moonlight Disc. It should work in jukeboxes and play its own original music track. Give it its own original item model and texture.
            """),
        new BenchmarkCase("flying-boats", """
            Allow occupied boats to fly. While riding a boat, holding jump should make it ascend, and holding sneak should make it descend. The server must remain authoritative and other players must see the boat moving correctly.
            """),
        new BenchmarkCase("no-new-worldgen", """
            Disable terrain generation for chunks that have never been generated before. Already-generated chunks must remain fully usable and unchanged. The game must not hang or crash when a player approaches the edge of generated terrain.
            """),
        new BenchmarkCase("zombie-invasion", """
            At midnight every third Minecraft day, begin a zombie invasion near each survival player. Announce it in chat, spawn three waves over one minute, and scale the number and strength of zombies with the world's age. Do not start another invasion while one is active, and preserve the schedule across a save and reopen.
            """),
        new BenchmarkCase("dirt-makeover", """
            Change dirt so that its placed block, inventory model, map appearance, and break particles use a distinctive original diamond-inspired appearance without referencing the vanilla diamond-block model or texture. Rename it to Diamond Dirt in English. The change must remain after F3+T, across distant chunks, and after leaving and rejoining the world.
            """)
    );

    static final List<BenchmarkCase> PHASE_B = List.of(
        new BenchmarkCase("lapis-table-evolution", """
            Upgrade the existing allcraft:lapis_alchemy_table feature. It should now accept one lapis lazuli and one gold ingot and produce two diamonds after three seconds. Update its menu to display progress. Existing placed tables and their stored inventories must continue working without being replaced or losing data.
            """),
        new BenchmarkCase("moonlight-disc-removal", """
            Remove the existing allcraft:moonlight_disc feature completely. Existing Moonlight Disc stacks in players, containers, and jukeboxes must become vanilla Music Disc 13 rather than disappearing or crashing the world. Remove obsolete code and resources as well.
            """),
        new BenchmarkCase("double-jump-cooldown", """
            Change the existing double-jump feature so that it has a two-second cooldown. Show a brief action-bar message if the player attempts it during the cooldown. Preserve all existing double-jump behavior and effects.
            """),
        new BenchmarkCase("double-jump-hunger", """
            Change the existing double-jump feature so that it consumes one hunger point and cannot be used when the player has six or fewer hunger points. Preserve all existing double-jump behavior and effects, including the two-second cooldown if another concurrent revision adds one.
            """)
    );

    private AllcraftAiTestSuites() {
    }

    public static int start(CommandSourceStack source, String phase) {
        if (!TEST_NAMES.contains(phase)) {
            source.sendFailure(Component.literal("Unknown Allcraft AI benchmark phase " + phase));
            return 0;
        }
        if (source.getServer().getPlayerList().getPlayerCount() == 0) {
            source.sendFailure(Component.literal("At least one client must be connected to run the AI benchmark"));
            return 0;
        }
        try {
            return phase.equals(PHASE_A_NAME) ? startPhaseA(source) : startPhaseB(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not start " + phase + ": " + concise(e)));
            return 0;
        }
    }

    static void jobChanged(Path worldRoot, String suiteRunId) {
        if (suiteRunId == null) return;
        try {
            SuiteRun run = loadCurrent(worldRoot);
            if (run == null || !run.runId.equals(suiteRunId)) return;
            reconcile(worldRoot, run);
            refresh(worldRoot, run);
            persist(worldRoot, run);
        } catch (IOException e) {
            LOGGER.error("Could not refresh Allcraft AI benchmark suite {}", suiteRunId, e);
        }
    }

    private static int startPhaseA(CommandSourceStack source) throws IOException {
        Path root = worldRoot(source.getServer());
        SuiteRun run = loadCurrent(root);
        if (run != null) {
            reconcile(root, run);
            refresh(root, run);
            persist(root, run);
            if (phaseADecision(run) == PhaseADecision.ALREADY_LAUNCHED) {
                report(source, run, "Phase A was already launched; use a fresh world for another suite-1 run");
                return 0;
            }
        } else {
            run = newRun(readWorldRevision(root));
        }

        run.phaseAState = "launching";
        if (run.phaseAStartedAt == null) run.phaseAStartedAt = Instant.now().toString();
        persist(root, run);
        AllcraftAiJobs.BatchResult batch;
        try {
            batch = AllcraftAiJobs.enqueueBatch(
                root,
                run.runId,
                "a",
                requests(PHASE_A),
                true
            );
        } catch (IOException failure) {
            run.phaseAState = "launch-failed";
            persist(root, run);
            throw failure;
        }
        appendJobs(run.phaseAJobs, batch.jobs());
        run.phaseAState = "running";
        refresh(root, run);
        persist(root, run);
        String runId = run.runId;
        source.sendSuccess(
            () -> Component.literal(
                "Started suite-1-a run " + shortId(runId) + ": 12 Sol/low AI editors queued at revision " + batch.baseRevision()
            ).withStyle(ChatFormatting.AQUA),
            false
        );
        reportJobs(source, run.phaseAJobs);
        return run.phaseAJobs.size();
    }

    private static int startPhaseB(CommandSourceStack source) throws IOException {
        Path root = worldRoot(source.getServer());
        SuiteRun run = loadCurrent(root);
        if (run == null) {
            source.sendFailure(Component.literal("Run /allcraft test suite-1-a first"));
            return 0;
        }
        reconcile(root, run);
        refresh(root, run);
        List<AllcraftAiJobs.JobSnapshot> phaseA = run.phaseAJobs.isEmpty()
            ? List.of()
            : AllcraftAiJobs.jobSnapshots(root, run.phaseAJobs.stream().map(JobReference::jobId).toList());
        PhaseBDecision decision = phaseBDecision(run, phaseA);
        if (decision == PhaseBDecision.BLOCKED) {
            persist(root, run);
            report(source, run, "Phase A must finalize all 12 jobs before phase B");
            return 0;
        }
        if (decision == PhaseBDecision.ALREADY_LAUNCHED) {
            persist(root, run);
            report(source, run, "Phase B was already launched");
            return 0;
        }
        if (decision == PhaseBDecision.SHOW_CHECKLIST) {
            run.phaseBState = "awaiting-preparation";
            persist(root, run);
            source.sendSuccess(
                () -> Component.literal("suite-1-b preparation checklist for run " + shortId(run.runId) + ":")
                    .withStyle(ChatFormatting.GOLD),
                false
            );
            source.sendSuccess(() -> Component.literal("1. Keep two clients connected."), false);
            source.sendSuccess(() -> Component.literal("2. Place and use allcraft:lapis_alchemy_table; leave ingredients stored inside."), false);
            source.sendSuccess(() -> Component.literal("3. Put allcraft:moonlight_disc in a player inventory, a container, and a jukebox."), false);
            source.sendSuccess(
                () -> Component.literal("Run /allcraft test suite-1-b again after preparation to confirm and launch all four jobs."),
                false
            );
            return 1;
        }

        run.phaseBState = "launching";
        run.phaseBStartedAt = Instant.now().toString();
        persist(root, run);
        AllcraftAiJobs.BatchResult batch;
        try {
            batch = AllcraftAiJobs.enqueueBatch(
                root,
                run.runId,
                "b",
                requests(PHASE_B),
                true
            );
        } catch (IOException failure) {
            run.phaseBState = "awaiting-preparation";
            persist(root, run);
            throw failure;
        }
        appendJobs(run.phaseBJobs, batch.jobs());
        run.phaseBState = "running";
        refresh(root, run);
        persist(root, run);
        source.sendSuccess(
            () -> Component.literal(
                "Started suite-1-b run " + shortId(run.runId) + ": four evolution jobs queued; both double-jump edits share the same base revision "
                    + batch.baseRevision()
            ).withStyle(ChatFormatting.AQUA),
            false
        );
        reportJobs(source, run.phaseBJobs);
        return run.phaseBJobs.size();
    }

    static PhaseADecision phaseADecision(SuiteRun run) {
        return run == null || run.phaseAJobs.isEmpty() ? PhaseADecision.LAUNCH : PhaseADecision.ALREADY_LAUNCHED;
    }

    static PhaseBDecision phaseBDecision(SuiteRun run, List<AllcraftAiJobs.JobSnapshot> phaseA) {
        if (run.phaseAJobs.size() != PHASE_A.size()
            || phaseA.size() != PHASE_A.size()
            || !phaseA.stream().allMatch(AllcraftAiJobs.JobSnapshot::finalized)) return PhaseBDecision.BLOCKED;
        if (!run.phaseBJobs.isEmpty()) return PhaseBDecision.ALREADY_LAUNCHED;
        return run.phaseBState.equals("awaiting-preparation") ? PhaseBDecision.LAUNCH : PhaseBDecision.SHOW_CHECKLIST;
    }

    static List<BenchmarkCase> cases(String phase) {
        return switch (phase) {
            case PHASE_A_NAME -> PHASE_A;
            case PHASE_B_NAME -> PHASE_B;
            default -> List.of();
        };
    }

    private static List<AllcraftAiJobs.BatchRequest> requests(List<BenchmarkCase> cases) {
        return cases.stream().map(test -> new AllcraftAiJobs.BatchRequest(test.id(), test.request())).toList();
    }

    private static void appendJobs(List<JobReference> destination, List<AllcraftAiJobs.BatchJob> jobs) {
        for (AllcraftAiJobs.BatchJob job : jobs) destination.add(new JobReference(job.caseId(), job.jobId()));
    }

    private static void reconcile(Path root, SuiteRun run) throws IOException {
        reconcilePhase(root, run.runId, "a", run.phaseAJobs, PHASE_A.size());
        reconcilePhase(root, run.runId, "b", run.phaseBJobs, PHASE_B.size());
    }

    private static void reconcilePhase(
        Path root, String runId, String phase, List<JobReference> destination, int expected
    ) throws IOException {
        if (!destination.isEmpty()) return;
        List<AllcraftAiJobs.JobSnapshot> recovered = AllcraftAiJobs.suiteJobs(root, runId, phase);
        if (recovered.isEmpty()) return;
        if (recovered.size() != expected) {
            throw new IOException("Suite phase " + phase + " has an incomplete persisted AI batch: " + recovered.size() + "/" + expected);
        }
        for (AllcraftAiJobs.JobSnapshot job : recovered) destination.add(new JobReference(job.caseId(), job.jobId()));
    }

    private static void refresh(Path root, SuiteRun run) throws IOException {
        List<JobReference> references = new ArrayList<>(run.phaseAJobs);
        references.addAll(run.phaseBJobs);
        List<AllcraftAiJobs.JobSnapshot> jobs = references.isEmpty()
            ? List.of()
            : AllcraftAiJobs.jobSnapshots(root, references.stream().map(JobReference::jobId).toList());
        int finalized = 0;
        int failed = 0;
        int attempts = 0;
        int cleaned = 0;
        long highestRevision = -1L;
        for (AllcraftAiJobs.JobSnapshot job : jobs) {
            if (job.finalized()) finalized++;
            if (job.terminalFailure() || job.state().equals("failed") || job.state().equals("conflicted")) failed++;
            attempts += job.attempt();
            if (job.cleanupComplete()) cleaned++;
            highestRevision = Math.max(highestRevision, job.resultRevision());
        }
        run.observedJobs.clear();
        for (AllcraftAiJobs.JobSnapshot job : jobs) {
            run.observedJobs.add(new ObservedJob(
                job.caseId(), job.jobId(), job.state(), job.attempt(), job.resultRevision(), job.cleanupComplete(), job.diagnostics()
            ));
        }
        run.observed = new Progress(
            jobs.size(), finalized, failed, jobs.size() - finalized - (int) jobs.stream().filter(AllcraftAiJobs.JobSnapshot::terminalFailure).count(),
            attempts, highestRevision, cleaned
        );
        if (!run.phaseAJobs.isEmpty()) {
            List<AllcraftAiJobs.JobSnapshot> phase = AllcraftAiJobs.jobSnapshots(
                root, run.phaseAJobs.stream().map(JobReference::jobId).toList()
            );
            run.phaseATiming = phaseTiming(phase);
            run.phaseAState = phase.stream().allMatch(AllcraftAiJobs.JobSnapshot::finalized)
                ? "finalized"
                : phase.stream().anyMatch(AllcraftAiJobs.JobSnapshot::terminalFailure) ? "failed" : "running";
        }
        if (!run.phaseBJobs.isEmpty()) {
            List<AllcraftAiJobs.JobSnapshot> phase = AllcraftAiJobs.jobSnapshots(
                root, run.phaseBJobs.stream().map(JobReference::jobId).toList()
            );
            run.phaseBTiming = phaseTiming(phase);
            run.phaseBState = phase.stream().allMatch(AllcraftAiJobs.JobSnapshot::finalized)
                ? "finalized"
                : phase.stream().anyMatch(AllcraftAiJobs.JobSnapshot::terminalFailure) ? "failed" : "running";
        }
    }

    static PhaseTiming phaseTiming(List<AllcraftAiJobs.JobSnapshot> jobs) {
        int completed = 0;
        long totalMillis = 0L;
        long maxMillis = 0L;
        for (AllcraftAiJobs.JobSnapshot job : jobs) {
            if (!job.finalized() || job.createdAt() == null || job.finalizedAt() == null) continue;
            long elapsed = Math.max(
                0L,
                Instant.parse(job.finalizedAt()).toEpochMilli() - Instant.parse(job.createdAt()).toEpochMilli()
            );
            completed++;
            totalMillis = Math.addExact(totalMillis, elapsed);
            maxMillis = Math.max(maxMillis, elapsed);
        }
        return completed == 0
            ? PhaseTiming.empty()
            : new PhaseTiming(completed, totalMillis / completed, maxMillis);
    }

    private static void report(CommandSourceStack source, SuiteRun run, String prefix) {
        Progress value = run.observed;
        source.sendSuccess(
            () -> Component.literal(
                prefix + ": run=" + shortId(run.runId) + " A=" + run.phaseAState + " B=" + run.phaseBState
                    + " finalized=" + value.finalized + "/" + value.total + " failed=" + value.failed
                    + " attempts=" + value.attempts + " highestRevision=" + value.highestRevision
                    + " A-time=" + formatTiming(run.phaseATiming) + " B-time=" + formatTiming(run.phaseBTiming)
            ),
            false
        );
    }

    private static String formatTiming(PhaseTiming timing) {
        if (timing.completedTasks == 0) return "n/a";
        return "avg " + formatDuration(timing.averageCompletionMillis) + ", max " + formatDuration(timing.maxCompletionMillis);
    }

    private static String formatDuration(long millis) {
        long seconds = (millis + 500L) / 1_000L;
        long hours = seconds / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        long remainder = seconds % 60L;
        if (hours > 0L) return hours + "h" + minutes + "m" + remainder + "s";
        if (minutes > 0L) return minutes + "m" + remainder + "s";
        return remainder + "s";
    }

    private static void reportJobs(CommandSourceStack source, List<JobReference> jobs) {
        source.sendSuccess(
            () -> Component.literal(
                jobs.stream().map(job -> job.caseId() + "=" + shortId(job.jobId())).collect(java.util.stream.Collectors.joining(", "))
            ),
            false
        );
    }

    private static Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static long readWorldRevision(Path root) throws IOException {
        Path manifest = root.resolve("patches/manifest.json");
        try {
            return JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                .getAsJsonObject().get("currentRevision").getAsLong();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft world manifest " + manifest, e);
        }
    }

    private static String shortId(String value) {
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 499) + "…";
    }

    record BenchmarkCase(String id, String request) {
        BenchmarkCase {
            id = id.trim();
            request = request.strip();
            if (id.isEmpty() || request.isEmpty()) throw new IllegalArgumentException("Benchmark cases require an ID and request");
        }
    }

    static SuiteRun newRun(long baseRevision) {
        return new SuiteRun(UUID.randomUUID().toString(), baseRevision, Instant.now().toString());
    }

    static SuiteRun loadCurrent(Path worldRoot) throws IOException {
        Path path = currentManifest(worldRoot);
        if (!Files.isRegularFile(path)) return null;
        try {
            return SuiteRun.fromJson(JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft AI benchmark suite manifest " + path, e);
        }
    }

    static void persist(Path worldRoot, SuiteRun run) throws IOException {
        run.updatedAt = Instant.now().toString();
        writeJsonAtomically(currentManifest(worldRoot), run.toJson());
    }

    static void archiveCurrent(Path worldRoot, SuiteRun run) throws IOException {
        Path current = currentManifest(worldRoot);
        if (!Files.isRegularFile(current)) return;
        Path archive = current.getParent().resolve("archive").resolve(run.runId + ".json");
        Files.createDirectories(archive.getParent());
        try {
            Files.move(current, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(current, archive);
        }
    }

    static Path currentManifest(Path worldRoot) {
        return worldRoot.resolve("patches/ai/suites").resolve(SUITE_ID).resolve("current.json");
    }

    private static void writeJsonAtomically(Path path, JsonObject object) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            GSON.toJson(object) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class SuiteRun {
        final String runId;
        final long baseRevision;
        final String createdAt;
        String phaseAState = "not-started";
        String phaseBState = "not-started";
        String phaseAStartedAt;
        String phaseBStartedAt;
        String updatedAt;
        final List<JobReference> phaseAJobs = new ArrayList<>();
        final List<JobReference> phaseBJobs = new ArrayList<>();
        final List<ObservedJob> observedJobs = new ArrayList<>();
        Progress observed = Progress.empty();
        PhaseTiming phaseATiming = PhaseTiming.empty();
        PhaseTiming phaseBTiming = PhaseTiming.empty();

        SuiteRun(String runId, long baseRevision, String createdAt) {
            this.runId = runId;
            this.baseRevision = baseRevision;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("format", 1);
            result.addProperty("suite", SUITE_ID);
            result.addProperty("runId", this.runId);
            result.addProperty("baseRevision", this.baseRevision);
            result.addProperty("createdAt", this.createdAt);
            result.addProperty("updatedAt", this.updatedAt);
            result.addProperty("phaseAState", this.phaseAState);
            result.addProperty("phaseBState", this.phaseBState);
            if (this.phaseAStartedAt != null) result.addProperty("phaseAStartedAt", this.phaseAStartedAt);
            if (this.phaseBStartedAt != null) result.addProperty("phaseBStartedAt", this.phaseBStartedAt);
            result.add("phaseAJobs", jobsToJson(this.phaseAJobs));
            result.add("phaseBJobs", jobsToJson(this.phaseBJobs));
            result.add("observed", this.observed.toJson());
            result.add("phaseATiming", this.phaseATiming.toJson());
            result.add("phaseBTiming", this.phaseBTiming.toJson());
            JsonArray caseResults = new JsonArray();
            for (ObservedJob job : this.observedJobs) caseResults.add(job.toJson());
            result.add("caseResults", caseResults);
            return result;
        }

        static SuiteRun fromJson(JsonObject object) {
            if (object.get("format").getAsInt() != 1 || !SUITE_ID.equals(object.get("suite").getAsString())) {
                throw new IllegalArgumentException("Unsupported AI benchmark suite manifest");
            }
            SuiteRun run = new SuiteRun(
                object.get("runId").getAsString(),
                object.get("baseRevision").getAsLong(),
                object.get("createdAt").getAsString()
            );
            run.updatedAt = object.get("updatedAt").getAsString();
            run.phaseAState = object.get("phaseAState").getAsString();
            run.phaseBState = object.get("phaseBState").getAsString();
            if (object.has("phaseAStartedAt")) run.phaseAStartedAt = object.get("phaseAStartedAt").getAsString();
            if (object.has("phaseBStartedAt")) run.phaseBStartedAt = object.get("phaseBStartedAt").getAsString();
            readJobs(object.getAsJsonArray("phaseAJobs"), run.phaseAJobs);
            readJobs(object.getAsJsonArray("phaseBJobs"), run.phaseBJobs);
            if (object.has("observed")) run.observed = Progress.fromJson(object.getAsJsonObject("observed"));
            if (object.has("phaseATiming")) run.phaseATiming = PhaseTiming.fromJson(object.getAsJsonObject("phaseATiming"));
            if (object.has("phaseBTiming")) run.phaseBTiming = PhaseTiming.fromJson(object.getAsJsonObject("phaseBTiming"));
            if (object.has("caseResults")) {
                for (var element : object.getAsJsonArray("caseResults")) {
                    run.observedJobs.add(ObservedJob.fromJson(element.getAsJsonObject()));
                }
            }
            return run;
        }

        private static JsonArray jobsToJson(List<JobReference> jobs) {
            JsonArray result = new JsonArray();
            for (JobReference job : jobs) {
                JsonObject object = new JsonObject();
                object.addProperty("case", job.caseId());
                object.addProperty("jobId", job.jobId());
                result.add(object);
            }
            return result;
        }

        private static void readJobs(JsonArray array, List<JobReference> destination) {
            for (var element : array) {
                JsonObject object = element.getAsJsonObject();
                destination.add(new JobReference(object.get("case").getAsString(), object.get("jobId").getAsString()));
            }
        }
    }

    record JobReference(String caseId, String jobId) {
    }

    enum PhaseBDecision {
        BLOCKED,
        SHOW_CHECKLIST,
        LAUNCH,
        ALREADY_LAUNCHED
    }

    enum PhaseADecision {
        LAUNCH,
        ALREADY_LAUNCHED
    }

    record ObservedJob(
        String caseId,
        String jobId,
        String state,
        int attempt,
        long resultRevision,
        boolean cleanupComplete,
        String diagnostics
    ) {
        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("case", this.caseId);
            result.addProperty("jobId", this.jobId);
            result.addProperty("state", this.state);
            result.addProperty("attempt", this.attempt);
            result.addProperty("resultRevision", this.resultRevision);
            result.addProperty("cleanupComplete", this.cleanupComplete);
            result.addProperty("diagnostics", this.diagnostics == null ? "" : this.diagnostics);
            return result;
        }

        static ObservedJob fromJson(JsonObject object) {
            return new ObservedJob(
                object.get("case").getAsString(),
                object.get("jobId").getAsString(),
                object.get("state").getAsString(),
                object.get("attempt").getAsInt(),
                object.get("resultRevision").getAsLong(),
                object.get("cleanupComplete").getAsBoolean(),
                object.get("diagnostics").getAsString()
            );
        }
    }

    record Progress(int total, int finalized, int failed, int active, int attempts, long highestRevision, int cleaned) {
        static Progress empty() {
            return new Progress(0, 0, 0, 0, 0, -1L, 0);
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("total", this.total);
            result.addProperty("finalized", this.finalized);
            result.addProperty("failed", this.failed);
            result.addProperty("active", this.active);
            result.addProperty("attempts", this.attempts);
            result.addProperty("highestRevision", this.highestRevision);
            result.addProperty("cleaned", this.cleaned);
            return result;
        }

        static Progress fromJson(JsonObject object) {
            return new Progress(
                object.get("total").getAsInt(),
                object.get("finalized").getAsInt(),
                object.get("failed").getAsInt(),
                object.get("active").getAsInt(),
                object.get("attempts").getAsInt(),
                object.get("highestRevision").getAsLong(),
                object.get("cleaned").getAsInt()
            );
        }
    }

    record PhaseTiming(int completedTasks, long averageCompletionMillis, long maxCompletionMillis) {
        static PhaseTiming empty() {
            return new PhaseTiming(0, 0L, 0L);
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("completedTasks", this.completedTasks);
            result.addProperty("averageCompletionMillis", this.averageCompletionMillis);
            result.addProperty("maxCompletionMillis", this.maxCompletionMillis);
            return result;
        }

        static PhaseTiming fromJson(JsonObject object) {
            return new PhaseTiming(
                object.get("completedTasks").getAsInt(),
                object.get("averageCompletionMillis").getAsLong(),
                object.get("maxCompletionMillis").getAsLong()
            );
        }
    }
}
