package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Persistent worktree/Exocortex/build scheduler for AI-authored world revisions. */
public final class AllcraftAiJobs {
    public static final int MAX_PARALLEL_EDITORS = 32;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Duration EXO_TIMEOUT = Duration.ofSeconds(30L);
    private static final long POLL_INTERVAL_MILLIS = 5_000L;
    private static final long RETRY_INTERVAL_MILLIS = 10_000L;
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(MAX_PARALLEL_EDITORS, task -> {
        Thread thread = new Thread(task, "Allcraft AI Worktree Worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Path, WorldJobs> WORLDS = new LinkedHashMap<>();

    private AllcraftAiJobs() {
    }

    public static synchronized void initializeWorld(Path worldRoot) throws IOException {
        Path normalized = worldRoot.toAbsolutePath().normalize();
        WorldJobs existing = WORLDS.get(normalized);
        if (existing != null) {
            existing.loaded = true;
            return;
        }
        WorldJobs jobs = new WorldJobs(normalized);
        jobs.load();
        WORLDS.put(normalized, jobs);
    }

    public static int start(CommandSourceStack source, String request) {
        String exactRequest = request == null ? "" : request.trim();
        if (exactRequest.isBlank()) {
            source.sendFailure(Component.literal("Usage: /allcraft ai <request>"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        Path worldRoot = worldRoot(server);
        try {
            WorldJobs world = world(worldRoot);
            if (!AllcraftSourceRepository.isCleanCanonical(worldRoot)) {
                source.sendFailure(Component.literal("Canonical world source has unpublished changes; use /allcraft apply first"));
                return 0;
            }
            JsonObject manifest = readJson(worldRoot.resolve("patches/manifest.json"));
            String id = UUID.randomUUID().toString();
            long sequence = world.nextSequence++;
            long revision = manifest.get("currentRevision").getAsLong();
            Job job = new Job(id, exactRequest, sequence, revision);
            job.baseCommit = AllcraftSourceRepository.canonicalCommit(worldRoot);
            job.branch = AllcraftSourceRepository.branchFor(id);
            job.worktree = worldRoot.resolve("source/.worktrees").resolve(id).toAbsolutePath().normalize().toString();
            job.state = State.TRIGGERED;
            world.jobs.put(id, job);
            world.persist(job);
            source.sendSuccess(
                () -> Component.literal("Queued Allcraft AI job " + shortId(id) + " at world revision " + revision)
                    .withStyle(ChatFormatting.AQUA),
                false
            );
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to queue Allcraft AI job", e);
            source.sendFailure(Component.literal("Failed to queue Allcraft AI job: " + concise(e)));
            return 0;
        }
    }

    public static synchronized void tick(MinecraftServer server) {
        Path root = worldRoot(server);
        WorldJobs world;
        try {
            world = world(root);
        } catch (IOException e) {
            LOGGER.error("Failed to load Allcraft AI jobs", e);
            return;
        }
        for (WorldJobs unloaded : WORLDS.values()) {
            if (unloaded != world && !unloaded.loaded) {
                unloaded.processCompleted(null, false);
                unloaded.dispatchUnloadedMaintenance();
            }
        }
        world.processCompleted(server, true);
        world.dispatch(server);
    }

    public static synchronized void stop(MinecraftServer server) {
        Path root = worldRoot(server);
        WorldJobs world = WORLDS.get(root);
        if (world == null) return;
        world.loaded = false;
        world.generation++;
    }

    public static int status(CommandSourceStack source, String requestedId) {
        try {
            WorldJobs world = world(worldRoot(source.getServer()));
            if (requestedId != null && !requestedId.isBlank()) {
                Job job = world.resolve(requestedId);
                if (job == null) {
                    source.sendFailure(Component.literal("Unknown or ambiguous Allcraft AI job: " + requestedId));
                    return 0;
                }
                source.sendSuccess(() -> Component.literal(world.describe(job)), false);
                return 1;
            }
            List<Job> recent = world.jobs.values().stream()
                .sorted(Comparator.comparingLong((Job job) -> job.sequence).reversed())
                .limit(10)
                .toList();
            if (recent.isEmpty()) {
                source.sendSuccess(() -> Component.literal("No Allcraft AI jobs exist for this world"), false);
                return 0;
            }
            for (Job job : recent) source.sendSuccess(() -> Component.literal(world.describe(job)), false);
            return recent.size();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not read AI job status: " + concise(e)));
            return 0;
        }
    }

    public static int cancel(CommandSourceStack source, String requestedId) {
        MinecraftServer server = source.getServer();
        try {
            WorldJobs world = world(worldRoot(server));
            Job job = world.resolve(requestedId);
            if (job == null) {
                source.sendFailure(Component.literal("Unknown or ambiguous Allcraft AI job: " + requestedId));
                return 0;
            }
            if (job.state == State.FINALIZED || job.state == State.CANCELLED) {
                source.sendFailure(Component.literal("AI job " + shortId(job.id) + " is already " + job.state.id));
                return 0;
            }
            job.cancelled = true;
            job.state = State.CANCELLED;
            job.diagnostics = "Cancelled by an operator";
            job.updatedAt = Instant.now().toString();
            world.persist(job);
            AllcraftPatchServer.cancelRevision(server, job.id, "AI job cancelled by an operator");
            AllcraftPatchServer.releaseRevision(server, job.id);
            world.scheduleCleanup(job, true);
            source.sendSuccess(() -> Component.literal("Cancelled Allcraft AI job " + shortId(job.id)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not cancel AI job: " + concise(e)));
            return 0;
        }
    }

    public static int retry(CommandSourceStack source, String requestedId) {
        try {
            WorldJobs world = world(worldRoot(source.getServer()));
            Job job = world.resolve(requestedId);
            if (job == null) {
                source.sendFailure(Component.literal("Unknown or ambiguous Allcraft AI job: " + requestedId));
                return 0;
            }
            if (job.state != State.FAILED && job.state != State.CONFLICTED) {
                source.sendFailure(Component.literal("AI job " + shortId(job.id) + " is not awaiting repair"));
                return 0;
            }
            job.nextActionAt = 0L;
            job.autoRetry = true;
            world.persist(job);
            source.sendSuccess(() -> Component.literal("Retry queued for Allcraft AI job " + shortId(job.id)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Could not retry AI job: " + concise(e)));
            return 0;
        }
    }

    private static synchronized WorldJobs world(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        WorldJobs result = WORLDS.get(normalized);
        if (result == null) {
            result = new WorldJobs(normalized);
            result.load();
            WORLDS.put(normalized, result);
        }
        return result;
    }

    private static int activeEditors() {
        int count = 0;
        for (WorldJobs world : WORLDS.values()) {
            for (Job job : world.jobs.values()) {
                if ((job.state == State.EDITING && !job.turnComplete)
                    || job.state == State.RETRYING
                    || world.hasPending(job, Action.START)) count++;
            }
        }
        return count;
    }

    private static Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static final class WorldJobs {
        private final Path root;
        private final Path jobsRoot;
        private final Map<String, Job> jobs = new LinkedHashMap<>();
        private final Map<String, Pending> pending = new LinkedHashMap<>();
        private long nextSequence = 1L;
        private volatile boolean loaded = true;
        private long generation;

        private WorldJobs(Path root) {
            this.root = root;
            this.jobsRoot = root.resolve("patches/ai/jobs");
        }

        private void load() throws IOException {
            Files.createDirectories(this.jobsRoot);
            try (var paths = Files.list(this.jobsRoot)) {
                for (Path directory : paths.filter(Files::isDirectory).sorted().toList()) {
                    Path stateFile = directory.resolve("job.json");
                    if (!Files.isRegularFile(stateFile)) continue;
                    try {
                        Job job = Job.fromJson(readJson(stateFile));
                        recover(job);
                        this.jobs.put(job.id, job);
                        this.nextSequence = Math.max(this.nextSequence, job.sequence + 1L);
                        persist(job);
                    } catch (Exception e) {
                        LOGGER.error("Could not recover Allcraft AI job from {}", stateFile, e);
                    }
                }
            }
        }

        private void recover(Job job) throws IOException {
            long currentRevision = readJson(this.root.resolve("patches/manifest.json")).get("currentRevision").getAsLong();
            if ((job.state == State.STAGING || job.state == State.ACTIVATING) && job.targetRevision > 0L) {
                if (currentRevision >= job.targetRevision) {
                    job.state = State.FINALIZED;
                    job.resultRevision = job.targetRevision;
                } else {
                    job.state = State.AWAITING_INTEGRATION;
                    job.diagnostics = "Recovered an interrupted publication; rebuilding against the finalized world revision";
                }
            } else if (job.state == State.RETRYING) {
                job.state = State.EDITING;
            } else if (job.state == State.TRIGGERED) {
                job.state = State.QUEUED;
            }
            job.updatedAt = Instant.now().toString();
        }

        private void processCompleted(MinecraftServer server, boolean includeIntegration) {
            List<Map.Entry<String, Pending>> completed = this.pending.entrySet().stream()
                .filter(entry -> entry.getValue().future().isDone())
                .filter(entry -> includeIntegration || entry.getValue().action() != Action.INTEGRATE)
                .toList();
            for (Map.Entry<String, Pending> entry : completed) {
                this.pending.remove(entry.getKey());
                Job job = this.jobs.get(entry.getKey());
                if (job == null) continue;
                Pending operation = entry.getValue();
                try {
                    Result result = operation.future().join();
                    if (operation.action() == Action.INTEGRATE && operation.generation() != this.generation) {
                        if (result instanceof Integrated integrated) AllcraftRevisionBuilder.discard(integrated.prepared());
                        if (!job.cancelled) {
                            job.state = State.AWAITING_INTEGRATION;
                            job.targetRevision = 0L;
                            job.diagnostics = "World reopened; rebuilding candidate against the current finalized revision";
                            persist(job);
                        }
                        continue;
                    }
                    if (job.cancelled && operation.action() != Action.CLEANUP) {
                        if (result instanceof Integrated integrated) AllcraftRevisionBuilder.discard(integrated.prepared());
                        if (server != null) AllcraftPatchServer.releaseRevision(server, job.id);
                        scheduleCleanup(job, true);
                        continue;
                    }
                    apply(server, job, operation.action(), result);
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (operation.action() == Action.INTEGRATE && operation.generation() != this.generation) {
                        if (!job.cancelled) {
                            job.state = State.AWAITING_INTEGRATION;
                            job.targetRevision = 0L;
                            try {
                                persist(job);
                            } catch (IOException persistenceError) {
                                LOGGER.error("Could not persist interrupted integration {}", job.id, persistenceError);
                            }
                        }
                        continue;
                    }
                    if (job.cancelled) scheduleCleanup(job, true);
                    else onActionFailure(server, job, operation.action(), cause);
                } catch (Exception e) {
                    if (job.cancelled) scheduleCleanup(job, true);
                    else onActionFailure(server, job, operation.action(), e);
                }
            }
        }

        private void apply(MinecraftServer server, Job job, Action action, Result result) throws IOException {
            switch (result) {
                case Started started -> {
                    job.conversationId = started.conversationId();
                    job.minimumMessageCount = 2;
                    job.lastMessageCount = 0;
                    job.state = State.EDITING;
                    job.nextActionAt = System.currentTimeMillis() + POLL_INTERVAL_MILLIS;
                    if (server != null) announce(server, "[Allcraft AI " + shortId(job.id) + "] Exocortex " + job.conversationId + " is editing its private worktree", ChatFormatting.AQUA);
                }
                case Polled polled -> {
                    job.lastMessageCount = Math.max(job.lastMessageCount, polled.info().messageCount());
                    job.title = polled.info().title();
                    if (!polled.info().streaming() && polled.info().messageCount() >= job.minimumMessageCount) {
                        job.turnComplete = true;
                        job.nextActionAt = 0L;
                    } else {
                        job.nextActionAt = System.currentTimeMillis() + POLL_INTERVAL_MILLIS;
                    }
                }
                case Captured captured -> {
                    job.turnComplete = false;
                    if (!captured.result().conflicts().isEmpty()) {
                        fail(job, State.CONFLICTED, "rebase", "Unresolved files: " + String.join(", ", captured.result().conflicts()));
                    } else if (!captured.result().changed() || captured.result().commit() == null) {
                        fail(job, State.FAILED, "inspection", "The AI turn produced no gameplay source or asset changes");
                    } else {
                        job.candidateCommit = captured.result().commit();
                        job.state = State.AWAITING_INTEGRATION;
                        job.diagnostics = "";
                        job.nextActionAt = 0L;
                        if (server != null) announce(server, "[Allcraft AI " + shortId(job.id) + "] candidate queued for sequential integration", ChatFormatting.YELLOW);
                    }
                }
                case Conflict conflict -> {
                    if (server != null) AllcraftPatchServer.releaseRevision(server, job.id);
                    fail(job, State.CONFLICTED, "merge", "Conflicts with the latest world source: " + String.join(", ", conflict.files()));
                }
                case Integrated integrated -> {
                    if (server == null) {
                        AllcraftRevisionBuilder.discard(integrated.prepared());
                        job.state = State.AWAITING_INTEGRATION;
                        break;
                    }
                    job.candidateCommit = integrated.candidateCommit();
                    job.targetRevision = integrated.prepared().revision();
                    job.state = State.STAGING;
                    RevisionListener listener = new RevisionListener(this, job, integrated.candidateCommit());
                    if (!AllcraftPatchServer.publishPrepared(server, integrated.prepared(), listener)) {
                        AllcraftPatchServer.releaseRevision(server, job.id);
                        AllcraftRevisionBuilder.discard(integrated.prepared());
                        job.state = State.AWAITING_INTEGRATION;
                        job.targetRevision = 0L;
                        job.nextActionAt = System.currentTimeMillis() + 1_000L;
                    } else {
                        announce(server, "[Allcraft AI " + shortId(job.id) + "] build passed; staging revision " + job.targetRevision, ChatFormatting.YELLOW);
                    }
                }
                case FeedbackSent feedback -> {
                    job.attempt++;
                    job.minimumMessageCount = feedback.minimumMessageCount();
                    job.state = State.EDITING;
                    job.autoRetry = true;
                    job.nextActionAt = System.currentTimeMillis() + POLL_INTERVAL_MILLIS;
                    if (server != null) announce(server, "[Allcraft AI " + shortId(job.id) + "] diagnostics sent to Exocortex for repair", ChatFormatting.GOLD);
                }
                case Cleaned ignored -> {
                    job.cleanupComplete = true;
                }
            }
            job.updatedAt = Instant.now().toString();
            persist(job);
        }

        private void dispatch(MinecraftServer server) {
            long now = System.currentTimeMillis();
            List<Job> ordered = this.jobs.values().stream().sorted(Comparator.comparingLong(job -> job.sequence)).toList();
            for (Job job : ordered) {
                if (this.pending.containsKey(job.id)) continue;
                if (job.state == State.FINALIZED || job.state == State.CANCELLED) {
                    if (!job.cleanupComplete && now >= job.nextActionAt) scheduleCleanup(job, job.state == State.CANCELLED);
                    continue;
                }
                if (job.cancelled) continue;
                try {
                    if ((job.state == State.TRIGGERED || job.state == State.QUEUED) && activeEditors() < MAX_PARALLEL_EDITORS) {
                        job.state = State.QUEUED;
                        persist(job);
                        schedule(job, Action.START, () -> startEditor(job));
                    } else if (job.state == State.EDITING && job.turnComplete) {
                        schedule(job, Action.CAPTURE, () -> new Captured(AllcraftSourceRepository.commitTurn(
                            Path.of(job.worktree), job.id, job.attempt, job.baseCommit, job.candidateCommit
                        )));
                    } else if (job.state == State.EDITING && now >= job.nextActionAt) {
                        schedule(job, Action.POLL, () -> new Polled(AllcraftAiLauncher.info(
                            AllcraftAiLauncher.EXO_CLI, Path.of(job.worktree), job.conversationId, EXO_TIMEOUT
                        )));
                    } else if ((job.state == State.FAILED || job.state == State.CONFLICTED)
                        && job.autoRetry
                        && now >= job.nextActionAt
                        && activeEditors() < MAX_PARALLEL_EDITORS) {
                        if (job.conversationId == null) {
                            job.state = State.QUEUED;
                            persist(job);
                        } else {
                            job.state = State.RETRYING;
                            persist(job);
                            schedule(job, Action.FEEDBACK, () -> sendFeedback(job));
                        }
                    }
                } catch (Exception e) {
                    onActionFailure(server, job, Action.START, e);
                }
            }

            boolean integrating = ordered.stream().anyMatch(job -> job.state == State.STAGING || job.state == State.ACTIVATING || hasPending(job, Action.INTEGRATE));
            if (!integrating && !AllcraftPatchServer.isRevisionBusy(server) && server.getPlayerList().getPlayerCount() > 0) {
                Job next = ordered.stream().filter(job -> job.state == State.AWAITING_INTEGRATION && !job.cancelled).findFirst().orElse(null);
                if (next != null && AllcraftPatchServer.reserveRevision(server, next.id)) {
                    schedule(next, Action.INTEGRATE, () -> integrate(next));
                }
            }
        }

        private void dispatchUnloadedMaintenance() {
            long now = System.currentTimeMillis();
            for (Job job : this.jobs.values()) {
                if (this.pending.containsKey(job.id)) continue;
                if (job.state == State.FINALIZED || job.state == State.CANCELLED) {
                    if (!job.cleanupComplete && now >= job.nextActionAt) scheduleCleanup(job, job.state == State.CANCELLED);
                } else if (job.state == State.EDITING && !job.turnComplete && now >= job.nextActionAt) {
                    schedule(job, Action.POLL, () -> new Polled(AllcraftAiLauncher.info(
                        AllcraftAiLauncher.EXO_CLI, Path.of(job.worktree), job.conversationId, EXO_TIMEOUT
                    )));
                }
            }
        }

        private Result startEditor(Job job) throws Exception {
            Path worktree = Path.of(job.worktree);
            AllcraftSourceRepository.Worktree created = AllcraftSourceRepository.createWorktree(this.root, job.id, job.baseRevision);
            Path tool = created.path().resolve(AllcraftAiLauncher.TOOL_MODULE_RELATIVE);
            String validation = AllcraftAiLauncher.validate(AllcraftAiLauncher.EXO_CLI, created.path(), tool);
            if (validation != null) throw new IOException(validation);
            Path jobDirectory = this.jobsRoot.resolve(job.id);
            Path handoff = jobDirectory.resolve("conversation-id.txt");
            Path pendingHandoff = jobDirectory.resolve("conversation-id.pending");
            String recoveredConversation = recoverConversationId(handoff, pendingHandoff);
            if (recoveredConversation != null) return new Started(recoveredConversation);

            Path launchLock = jobDirectory.resolve("conversation-launch.lock");
            Files.createDirectories(jobDirectory);
            boolean owner = false;
            try {
                try {
                    Files.writeString(
                        launchLock,
                        Instant.now().toString() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    );
                    owner = true;
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    for (int attempt = 0; attempt < 350; attempt++) {
                        recoveredConversation = recoverConversationId(handoff, pendingHandoff);
                        if (recoveredConversation != null) return new Started(recoveredConversation);
                        Thread.sleep(100L);
                    }
                    if (Files.getLastModifiedTime(launchLock).toMillis() < System.currentTimeMillis() - 60_000L) {
                        Files.deleteIfExists(launchLock);
                    }
                    throw new IOException("Another server instance is still creating this job's Exocortex conversation");
                }

                Path reservedIdFile = jobDirectory.resolve("reserved-conversation-id.txt");
                String reservedId;
                if (Files.isRegularFile(reservedIdFile)) {
                    reservedId = readConversationId(reservedIdFile);
                    try {
                        AllcraftAiLauncher.CliInfo existing = AllcraftAiLauncher.info(
                            AllcraftAiLauncher.EXO_CLI, created.path(), reservedId, EXO_TIMEOUT
                        );
                        if (!existing.streaming() && existing.messageCount() == 0) {
                            AllcraftAiLauncher.continueCli(
                                AllcraftAiLauncher.EXO_CLI,
                                created.path(),
                                reservedId,
                                initialPrompt(job, created.path()),
                                EXO_TIMEOUT
                            );
                        }
                        writeTextAtomically(handoff, reservedId + System.lineSeparator());
                        return new Started(reservedId);
                    } catch (IOException ignored) {
                        // The ID is durably reserved but not accepted by the daemon yet; create it below.
                    }
                } else {
                    reservedId = newConversationId();
                    writeTextAtomically(reservedIdFile, reservedId + System.lineSeparator());
                }
                String prompt = initialPrompt(job, created.path());
                AllcraftAiLauncher.CliResult result = AllcraftAiLauncher.runCli(
                    AllcraftAiLauncher.EXO_CLI,
                    created.path(),
                    tool,
                    prompt,
                    reservedId,
                    pendingHandoff,
                    EXO_TIMEOUT
                );
                moveAtomically(pendingHandoff, handoff);
                return new Started(result.conversationId());
            } finally {
                if (owner) Files.deleteIfExists(launchLock);
            }
        }

        private Result sendFeedback(Job job) throws Exception {
            String prompt = repairPrompt(job);
            AllcraftAiLauncher.continueCli(AllcraftAiLauncher.EXO_CLI, Path.of(job.worktree), job.conversationId, prompt, EXO_TIMEOUT);
            return new FeedbackSent(Math.max(job.lastMessageCount + 2, job.minimumMessageCount + 2));
        }

        private Result integrate(Job job) throws Exception {
            Path worktree = Path.of(job.worktree);
            AllcraftSourceRepository.RebaseResult rebased = AllcraftSourceRepository.rebaseOntoCanonical(this.root, worktree);
            if (rebased.conflicted()) return new Conflict(rebased.conflicts());
            String candidate = rebased.commit();
            AllcraftSourceRepository.verifyCandidate(this.root, worktree, candidate);
            AllcraftRevisionBuilder.PreparedRevision prepared = AllcraftRevisionBuilder.prepare(
                this.root,
                worktree,
                AllcraftRevisionBuilder.Request.production("ai-" + shortId(job.id), job.id)
            );
            return new Integrated(prepared, candidate);
        }

        private void onActionFailure(MinecraftServer server, Job job, Action action, Throwable error) {
            LOGGER.error("Allcraft AI job {} action {} failed", job.id, action, error);
            if (action == Action.INTEGRATE && server != null) AllcraftPatchServer.releaseRevision(server, job.id);
            if (action == Action.POLL) {
                job.nextActionAt = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
                job.diagnostics = "Exocortex status check failed: " + concise(error);
            } else if (action == Action.FEEDBACK) {
                job.state = State.FAILED;
                job.nextActionAt = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
                job.diagnostics = "Could not deliver repair diagnostics: " + concise(error);
            } else if (action == Action.CLEANUP) {
                job.diagnostics = "Cleanup failed: " + concise(error);
                job.nextActionAt = System.currentTimeMillis() + RETRY_INTERVAL_MILLIS;
            } else {
                fail(job, State.FAILED, action.id, concise(error));
            }
            job.updatedAt = Instant.now().toString();
            try {
                persist(job);
            } catch (IOException persistenceError) {
                LOGGER.error("Could not persist failed AI job {}", job.id, persistenceError);
            }
        }

        private void fail(Job job, State state, String stage, String message) {
            job.state = state;
            job.diagnostics = "stage=" + stage + System.lineSeparator() + message;
            job.nextActionAt = System.currentTimeMillis() + 250L;
            job.autoRetry = true;
            Path diagnostics = this.jobsRoot.resolve(job.id).resolve("diagnostics.txt");
            try {
                Files.writeString(
                    diagnostics,
                    "Allcraft AI integration failure\njob=" + job.id + "\nattempt=" + job.attempt + "\n" + job.diagnostics + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (IOException e) {
                LOGGER.warn("Could not write diagnostics for AI job {}", job.id, e);
            }
        }

        private void schedule(Job job, Action action, Task task) {
            if (this.pending.containsKey(job.id)) return;
            CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, WORKERS);
            this.pending.put(job.id, new Pending(action, future, this.generation));
        }

        private void scheduleCleanup(Job job, boolean abortConversation) {
            schedule(job, Action.CLEANUP, () -> {
                if (abortConversation && job.conversationId != null) {
                    try {
                        AllcraftAiLauncher.abort(AllcraftAiLauncher.EXO_CLI, Path.of(job.worktree), job.conversationId, EXO_TIMEOUT);
                    } catch (Exception ignored) {
                    }
                }
                AllcraftSourceRepository.cleanup(this.root, Path.of(job.worktree), job.branch);
                return new Cleaned();
            });
        }

        private boolean hasPending(Job job, Action action) {
            Pending value = this.pending.get(job.id);
            return value != null && value.action() == action;
        }

        private Job resolve(String requested) {
            Job exact = this.jobs.get(requested);
            if (exact != null) return exact;
            List<Job> matches = this.jobs.values().stream().filter(job -> job.id.startsWith(requested)).toList();
            return matches.size() == 1 ? matches.getFirst() : null;
        }

        private String describe(Job job) {
            long queuePosition = job.state == State.AWAITING_INTEGRATION
                ? this.jobs.values().stream().filter(candidate -> candidate.state == State.AWAITING_INTEGRATION && candidate.sequence <= job.sequence).count()
                : 0L;
            String result = "AI " + shortId(job.id) + " " + job.state.id;
            if (queuePosition > 0L) result += " queue=" + queuePosition;
            if (job.conversationId != null) result += " exo:" + job.conversationId;
            if (job.resultRevision >= 0L) result += " revision=" + job.resultRevision;
            if (job.diagnostics != null && !job.diagnostics.isBlank()) result += " — " + oneLine(job.diagnostics);
            return result;
        }

        private void persist(Job job) throws IOException {
            writeJsonAtomically(this.jobsRoot.resolve(job.id).resolve("job.json"), job.toJson());
        }
    }

    private static final class RevisionListener implements AllcraftPatchServer.RevisionListener {
        private final WorldJobs world;
        private final Job job;
        private final String candidateCommit;
        private AllcraftSourceRepository.Promotion promotion;

        private RevisionListener(WorldJobs world, Job job, String candidateCommit) {
            this.world = world;
            this.job = job;
            this.candidateCommit = candidateCommit;
        }

        @Override
        public void phase(String phase) {
            if (this.job.cancelled) return;
            this.job.state = phase.equals("activating") ? State.ACTIVATING : State.STAGING;
            save();
        }

        @Override
        public void beforeCommit(AllcraftRevisionBuilder.PreparedRevision prepared) throws IOException {
            this.promotion = AllcraftSourceRepository.promote(
                this.world.root, this.candidateCommit, prepared.parentRevision(), prepared.revision()
            );
        }

        @Override
        public void finalized(long revision) {
            if (this.job.cancelled) {
                LOGGER.error("Cancelled AI job {} unexpectedly reached finalized revision {}", this.job.id, revision);
                return;
            }
            this.job.state = State.FINALIZED;
            this.job.resultRevision = revision;
            this.job.diagnostics = "";
            this.job.updatedAt = Instant.now().toString();
            save();
            announceCurrent("[Allcraft AI " + shortId(this.job.id) + "] finalized as world revision " + revision, ChatFormatting.GREEN);
            this.world.scheduleCleanup(this.job, false);
        }

        @Override
        public void failed(String reason) {
            if (this.promotion != null) {
                try {
                    AllcraftSourceRepository.rollback(this.world.root, this.promotion);
                } catch (IOException e) {
                    reason += "; canonical source rollback failed: " + concise(e);
                }
            }
            if (this.job.cancelled) {
                this.job.state = State.CANCELLED;
                this.job.diagnostics = "Cancelled by an operator";
                this.world.scheduleCleanup(this.job, true);
            } else {
                this.world.fail(this.job, State.FAILED, "publication", reason);
            }
            this.job.updatedAt = Instant.now().toString();
            save();
        }

        @Override
        public void interrupted(String reason) {
            if (this.job.cancelled) {
                failed(reason);
                return;
            }
            this.job.state = State.AWAITING_INTEGRATION;
            this.job.targetRevision = 0L;
            this.job.diagnostics = "Publication interrupted; candidate retained for automatic restart: " + reason;
            this.job.nextActionAt = 0L;
            this.job.updatedAt = Instant.now().toString();
            save();
        }

        private void save() {
            try {
                this.world.persist(this.job);
            } catch (IOException e) {
                LOGGER.error("Could not persist AI job lifecycle {}", this.job.id, e);
            }
        }

        private void announceCurrent(String message, ChatFormatting color) {
            LOGGER.info(message);
        }
    }

    public enum State {
        TRIGGERED("triggered"),
        QUEUED("queued"),
        EDITING("editing"),
        AWAITING_INTEGRATION("awaiting-integration"),
        CONFLICTED("conflicted"),
        FAILED("failed"),
        RETRYING("retrying"),
        STAGING("staging"),
        ACTIVATING("activating"),
        FINALIZED("finalized"),
        CANCELLED("cancelled");

        private final String id;

        State(String id) {
            this.id = id;
        }

        private static State parse(String value) {
            for (State state : values()) if (state.id.equals(value)) return state;
            throw new IllegalArgumentException("Unknown AI job state " + value);
        }
    }

    private enum Action {
        START("start"), POLL("poll"), CAPTURE("inspect"), INTEGRATE("integrate"), FEEDBACK("feedback"), CLEANUP("cleanup");

        private final String id;

        Action(String id) {
            this.id = id;
        }
    }

    private static final class Job {
        private final String id;
        private final String request;
        private final long sequence;
        private final long baseRevision;
        private String baseCommit;
        private String branch;
        private String worktree;
        private State state = State.TRIGGERED;
        private String conversationId;
        private String title = "";
        private int attempt = 1;
        private int minimumMessageCount;
        private int lastMessageCount;
        private boolean turnComplete;
        private boolean autoRetry = true;
        private boolean cancelled;
        private boolean cleanupComplete;
        private long nextActionAt;
        private long targetRevision;
        private long resultRevision = -1L;
        private String candidateCommit;
        private String diagnostics = "";
        private String createdAt;
        private String updatedAt;

        private Job(String id, String request, long sequence, long baseRevision) {
            this.id = id;
            this.request = request;
            this.sequence = sequence;
            this.baseRevision = baseRevision;
            this.createdAt = Instant.now().toString();
            this.updatedAt = this.createdAt;
        }

        private JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("format", 1);
            result.addProperty("id", this.id);
            result.addProperty("request", this.request);
            result.addProperty("sequence", this.sequence);
            result.addProperty("baseRevision", this.baseRevision);
            add(result, "baseCommit", this.baseCommit);
            add(result, "branch", this.branch);
            add(result, "worktree", this.worktree);
            result.addProperty("state", this.state.id);
            add(result, "conversationId", this.conversationId);
            result.addProperty("title", this.title);
            result.addProperty("attempt", this.attempt);
            result.addProperty("minimumMessageCount", this.minimumMessageCount);
            result.addProperty("lastMessageCount", this.lastMessageCount);
            result.addProperty("turnComplete", this.turnComplete);
            result.addProperty("autoRetry", this.autoRetry);
            result.addProperty("cancelled", this.cancelled);
            result.addProperty("cleanupComplete", this.cleanupComplete);
            result.addProperty("nextActionAt", this.nextActionAt);
            result.addProperty("targetRevision", this.targetRevision);
            result.addProperty("resultRevision", this.resultRevision);
            add(result, "candidateCommit", this.candidateCommit);
            result.addProperty("diagnostics", this.diagnostics);
            result.addProperty("createdAt", this.createdAt);
            result.addProperty("updatedAt", this.updatedAt);
            return result;
        }

        private static Job fromJson(JsonObject object) {
            Job job = new Job(
                object.get("id").getAsString(),
                object.get("request").getAsString(),
                object.get("sequence").getAsLong(),
                object.get("baseRevision").getAsLong()
            );
            job.baseCommit = optional(object, "baseCommit");
            job.branch = optional(object, "branch");
            job.worktree = optional(object, "worktree");
            job.state = State.parse(object.get("state").getAsString());
            job.conversationId = optional(object, "conversationId");
            job.title = optional(object, "title", "");
            job.attempt = integer(object, "attempt", 1);
            job.minimumMessageCount = integer(object, "minimumMessageCount", 0);
            job.lastMessageCount = integer(object, "lastMessageCount", 0);
            job.turnComplete = bool(object, "turnComplete", false);
            job.autoRetry = bool(object, "autoRetry", true);
            job.cancelled = bool(object, "cancelled", false);
            job.cleanupComplete = bool(object, "cleanupComplete", false);
            job.nextActionAt = number(object, "nextActionAt", 0L);
            job.targetRevision = number(object, "targetRevision", 0L);
            job.resultRevision = number(object, "resultRevision", -1L);
            job.candidateCommit = optional(object, "candidateCommit");
            job.diagnostics = optional(object, "diagnostics", "");
            job.createdAt = optional(object, "createdAt", job.createdAt);
            job.updatedAt = optional(object, "updatedAt", job.createdAt);
            return job;
        }
    }

    private sealed interface Result permits Started, Polled, Captured, Conflict, Integrated, FeedbackSent, Cleaned {
    }

    private record Started(String conversationId) implements Result {
    }

    private record Polled(AllcraftAiLauncher.CliInfo info) implements Result {
    }

    private record Captured(AllcraftSourceRepository.TurnResult result) implements Result {
    }

    private record Conflict(List<String> files) implements Result {
    }

    private record Integrated(AllcraftRevisionBuilder.PreparedRevision prepared, String candidateCommit) implements Result {
    }

    private record FeedbackSent(int minimumMessageCount) implements Result {
    }

    private record Cleaned() implements Result {
    }

    private record Pending(Action action, CompletableFuture<Result> future, long generation) {
    }

    @FunctionalInterface
    private interface Task {
        Result run() throws Exception;
    }

    private static String initialPrompt(Job job, Path worktree) {
        return """
            You are implementing one runtime Allcraft world change in a private source worktree.

            Player request:
            %s

            Job ID: %s
            Base world revision: %d
            Authoritative worktree: %s

            Edit only files inside that exact worktree. Do not edit the canonical world source or any other repository. Do not run Git, commit, merge, build, launch Minecraft, or modify .allcraft infrastructure; Allcraft performs those steps after your turn. Use minecraft_glob and minecraft_grep to discover mapped source and assets, then use the scoped paths they return. Implement the request as a production source/asset/data change, including matching client/server logical classes and runtime registry declarations where needed. Do not add a test fixture or merely explain the change. Finish the turn once the worktree contains the complete implementation.
            """.formatted(job.request, job.id, job.baseRevision, worktree);
    }

    private static String repairPrompt(Job job) {
        return """
            Allcraft rejected integration attempt %d for job %s.

            Diagnostics:
            %s

            Repair the implementation in the same worktree: %s
            Edit only that worktree. Do not run Git, build, or merely describe a fix. Resolve every listed conflict/error and finish once the corrected files are ready for automatic integration.
            """.formatted(job.attempt, job.id, job.diagnostics, job.worktree);
    }

    private static void announce(MinecraftServer server, String text, ChatFormatting color) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text).withStyle(color), false);
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 2_000 ? message : message.substring(0, 1_999) + "…";
    }

    private static String oneLine(String value) {
        String result = value.replace('\r', ' ').replace('\n', ' ').trim();
        return result.length() <= 300 ? result : result.substring(0, 299) + "…";
    }

    private static JsonObject readJson(Path path) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft JSON file " + path, e);
        }
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
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String recoverConversationId(Path handoff, Path pending) throws IOException {
        if (Files.isRegularFile(handoff)) return readConversationId(handoff);
        if (!Files.isRegularFile(pending) || Files.size(pending) == 0L) return null;
        String conversationId;
        try {
            conversationId = readConversationId(pending);
        } catch (IOException invalid) {
            Files.move(
                pending,
                pending.resolveSibling(pending.getFileName() + ".invalid-" + System.currentTimeMillis()),
                StandardCopyOption.REPLACE_EXISTING
            );
            return null;
        }
        moveAtomically(pending, handoff);
        return conversationId;
    }

    private static void writeTextAtomically(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        moveAtomically(temporary, path);
    }

    private static String newConversationId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String readConversationId(Path path) throws IOException {
        String value = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (!value.matches("[0-9]+-[a-z0-9]{6}")) throw new IOException("Invalid persisted Exocortex conversation ID");
        return value;
    }

    private static void add(JsonObject object, String name, String value) {
        if (value != null) object.addProperty(name, value);
    }

    private static String optional(JsonObject object, String name) {
        return optional(object, name, null);
    }

    private static String optional(JsonObject object, String name, String fallback) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name).getAsInt() : fallback;
    }

    private static long number(JsonObject object, String name, long fallback) {
        return object.has(name) ? object.get(name).getAsLong() : fallback;
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        return object.has(name) ? object.get(name).getAsBoolean() : fallback;
    }
}
