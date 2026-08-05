package net.minecraft.allcraft;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Git-backed ownership and promotion rules for one world's authoritative source tree. */
public final class AllcraftSourceRepository {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(15L);
    private static final String CANONICAL_BRANCH = "allcraft/main";
    private static final String AI_BRANCH_PREFIX = "allcraft/ai/";

    private AllcraftSourceRepository() {
    }

    public static synchronized void initialize(Path worldRoot, long revision) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("Allcraft world source is missing: " + sourceRoot);
        }
        if (!Files.isDirectory(sourceRoot.resolve(".git"))) {
            git(sourceRoot, "init", "-b", CANONICAL_BRANCH);
            configure(sourceRoot);
            configureSharedObjects(sourceRoot);
            ignoreWorktrees(sourceRoot);
            git(sourceRoot, "add", "-A");
            git(sourceRoot, "commit", "-m", "Allcraft source baseline revision " + revision);
        } else {
            configure(sourceRoot);
            ignoreWorktrees(sourceRoot);
            String branch = git(sourceRoot, "branch", "--show-current").output().trim();
            if (!CANONICAL_BRANCH.equals(branch)) {
                throw new IOException("World source must have " + CANONICAL_BRANCH + " checked out, found " + branch);
            }
        }

        String revisionRef = revisionRef(revision);
        if (refExists(sourceRoot, revisionRef)) {
            String expected = git(sourceRoot, "rev-parse", revisionRef).output().trim();
            String current = head(sourceRoot);
            if (!expected.equals(current) || !isClean(sourceRoot)) {
                LOGGER.warn("Restoring canonical world source to finalized revision {}", revision);
                git(sourceRoot, "reset", "--hard", expected);
                git(sourceRoot, "clean", "-fd", "--exclude=.worktrees/");
            }
        } else {
            if (!isClean(sourceRoot)) {
                git(sourceRoot, "add", "-A");
                git(sourceRoot, "commit", "-m", "Allcraft recovered source revision " + revision);
            }
            updateRef(sourceRoot, revisionRef, head(sourceRoot));
        }
        git(sourceRoot, "worktree", "prune");
    }

    public static synchronized Worktree createWorktree(Path worldRoot, String jobId, long baseRevision) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        requireCleanCanonical(sourceRoot);
        String baseRef = revisionRef(baseRevision);
        if (!refExists(sourceRoot, baseRef)) {
            throw new IOException("Missing finalized source ref for world revision " + baseRevision);
        }
        String baseCommit = git(sourceRoot, "rev-parse", baseRef).output().trim();
        Path worktree = sourceRoot.resolve(".worktrees").resolve(jobId).toAbsolutePath().normalize();
        String branch = AI_BRANCH_PREFIX + jobId;
        if (Files.exists(worktree)) {
            GitResult existing = gitAllowFailure(worktree, "rev-parse", "--show-toplevel");
            String existingBranch = existing.exitCode() == 0 ? git(worktree, "branch", "--show-current").output().trim() : "";
            if (existing.exitCode() == 0
                && Path.of(existing.output()).toAbsolutePath().normalize().equals(worktree)
                && branch.equals(existingBranch)) {
                return new Worktree(worktree, branch, baseCommit);
            }
            throw new IOException("AI worktree path exists but is not a valid linked worktree: " + worktree);
        }
        Files.createDirectories(worktree.getParent());
        git(sourceRoot, "worktree", "prune");
        if (refExists(sourceRoot, "refs/heads/" + branch)) {
            git(sourceRoot, "worktree", "add", worktree.toString(), branch);
        } else {
            git(sourceRoot, "worktree", "add", "-b", branch, worktree.toString(), baseCommit);
        }
        return new Worktree(worktree, branch, baseCommit);
    }

    public static synchronized TurnResult commitTurn(
        Path worktree, String jobId, int attempt, String baseCommit, String previousCandidateCommit
    ) throws IOException {
        if (rebaseInProgress(worktree)) {
            List<String> conflicts = conflictedFiles(worktree);
            List<String> unresolvedMarkers = new ArrayList<>();
            for (String conflict : conflicts) {
                Path file = worktree.resolve(conflict);
                if (Files.isRegularFile(file)) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (text.contains("<<<<<<<") || text.contains("=======") || text.contains(">>>>>>>")) {
                        unresolvedMarkers.add(conflict);
                    }
                }
            }
            if (!unresolvedMarkers.isEmpty()) return new TurnResult(null, unresolvedMarkers, false);
            git(worktree, Map.of("GIT_EDITOR", "true"), "add", "-A");
            GitResult continued = gitAllowFailure(worktree, Map.of("GIT_EDITOR", "true"), "rebase", "--continue");
            if (continued.exitCode() != 0) {
                conflicts = conflictedFiles(worktree);
                if (!conflicts.isEmpty()) {
                    return new TurnResult(null, conflicts, false);
                }
                throw new IOException("Could not continue candidate rebase: " + continued.output());
            }
            return new TurnResult(head(worktree), List.of(), true);
        }

        String status = git(worktree, "status", "--porcelain=v1", "--untracked-files=all").output();
        if (status.isBlank()) {
            String current = head(worktree);
            if (baseCommit != null
                && !current.equals(baseCommit)
                && (previousCandidateCommit == null || !current.equals(previousCandidateCommit))) {
                return new TurnResult(current, List.of(), true);
            }
            return new TurnResult(null, List.of(), false);
        }
        rejectUnsupportedEntries(worktree);
        git(worktree, "add", "-A");
        GitResult staged = gitAllowFailure(worktree, "diff", "--cached", "--quiet");
        if (staged.exitCode() == 0) {
            return new TurnResult(null, List.of(), false);
        }
        git(worktree, "commit", "-m", "Allcraft AI candidate " + jobId + " attempt " + attempt);
        return new TurnResult(head(worktree), List.of(), true);
    }

    public static synchronized RebaseResult rebaseOntoCanonical(Path worldRoot, Path worktree) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        requireCleanCanonical(sourceRoot);
        if (rebaseInProgress(worktree)) {
            List<String> conflicts = conflictedFiles(worktree);
            return new RebaseResult(null, conflicts, !conflicts.isEmpty());
        }
        GitResult result = gitAllowFailure(worktree, "rebase", canonicalRef());
        if (result.exitCode() != 0) {
            List<String> conflicts = conflictedFiles(worktree);
            if (!conflicts.isEmpty()) {
                return new RebaseResult(null, conflicts, true);
            }
            throw new IOException("Could not update AI candidate onto canonical source: " + result.output());
        }
        return new RebaseResult(head(worktree), List.of(), false);
    }

    public static synchronized void verifyCandidate(Path worldRoot, Path worktree, String candidateCommit) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        requireCleanCanonical(sourceRoot);
        GitResult ancestor = gitAllowFailure(sourceRoot, "merge-base", "--is-ancestor", canonicalRef(), candidateCommit);
        if (ancestor.exitCode() != 0) {
            throw new IOException("AI candidate is not based on the latest canonical source commit");
        }
        String changed = git(worktree, "diff", "--name-only", canonicalRef() + ".." + candidateCommit).output();
        List<String> changedPaths = changed.lines()
            .map(String::trim)
            .filter(path -> !path.isEmpty())
            .toList();
        List<String> infrastructure = changedPaths.stream()
            .filter(path -> path.equals(".gitignore") || path.startsWith(".allcraft/"))
            .toList();
        if (!infrastructure.isEmpty()) {
            throw new IOException("AI candidate modified Allcraft worktree infrastructure: " + String.join(", ", infrastructure));
        }
        List<String> gameplay = changedPaths;
        if (gameplay.isEmpty()) {
            throw new IOException("The AI turn did not produce any gameplay source or asset changes");
        }
        rejectUnsupportedEntries(worktree);
    }

    public static synchronized Promotion promote(Path worldRoot, String candidateCommit, long parentRevision, long revision)
        throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        requireCleanCanonical(sourceRoot);
        String parentCommit = git(sourceRoot, "rev-parse", revisionRef(parentRevision)).output().trim();
        if (!head(sourceRoot).equals(parentCommit)) {
            throw new IOException("Canonical source no longer matches parent revision " + parentRevision);
        }
        GitResult ancestor = gitAllowFailure(sourceRoot, "merge-base", "--is-ancestor", parentCommit, candidateCommit);
        if (ancestor.exitCode() != 0) {
            throw new IOException("Candidate cannot fast-forward canonical source revision " + parentRevision);
        }
        updateRef(sourceRoot, revisionRef(revision), candidateCommit);
        git(sourceRoot, "reset", "--hard", candidateCommit);
        return new Promotion(parentCommit, candidateCommit, parentRevision, revision);
    }

    public static synchronized Promotion recordFinalized(
        Path worldRoot, long parentRevision, long revision, String message
    ) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        String parentCommit = git(sourceRoot, "rev-parse", revisionRef(parentRevision)).output().trim();
        if (!isClean(sourceRoot)) {
            git(sourceRoot, "add", "-A");
            git(sourceRoot, "commit", "-m", message);
        }
        String committed = head(sourceRoot);
        updateRef(sourceRoot, revisionRef(revision), committed);
        return new Promotion(parentCommit, committed, parentRevision, revision);
    }

    public static synchronized void rollback(Path worldRoot, Promotion promotion) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        git(sourceRoot, "reset", "--hard", promotion.parentCommit());
        gitAllowFailure(sourceRoot, "update-ref", "-d", revisionRef(promotion.revision()));
    }

    public static synchronized void cleanup(Path worldRoot, Path worktree, String branch) throws IOException {
        Path sourceRoot = sourceRoot(worldRoot);
        gitAllowFailure(sourceRoot, "worktree", "remove", "--force", worktree.toString());
        gitAllowFailure(sourceRoot, "branch", "-D", branch);
        git(sourceRoot, "worktree", "prune");
    }

    public static synchronized boolean isCleanCanonical(Path worldRoot) throws IOException {
        return isClean(sourceRoot(worldRoot));
    }

    public static synchronized String canonicalCommit(Path worldRoot) throws IOException {
        return head(sourceRoot(worldRoot));
    }

    public static String branchFor(String jobId) {
        return AI_BRANCH_PREFIX + jobId;
    }

    private static Path sourceRoot(Path worldRoot) {
        return worldRoot.toAbsolutePath().normalize().resolve("source");
    }

    private static void configure(Path sourceRoot) throws IOException {
        git(sourceRoot, "config", "user.name", "Allcraft AI");
        git(sourceRoot, "config", "user.email", "allcraft@localhost");
    }

    private static void configureSharedObjects(Path sourceRoot) throws IOException {
        String configured = System.getProperty("allcraft.sourceRoot");
        if (configured == null || configured.isBlank()) return;
        Path installedSource = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(installedSource)) return;
        GitResult common = gitAllowFailure(installedSource, "rev-parse", "--git-common-dir");
        if (common.exitCode() != 0 || common.output().isBlank()) return;
        Path commonDirectory = Path.of(common.output());
        if (!commonDirectory.isAbsolute()) commonDirectory = installedSource.resolve(commonDirectory).normalize();
        Path objects = commonDirectory.resolve("objects").toAbsolutePath().normalize();
        if (!Files.isDirectory(objects)) return;
        Path alternates = sourceRoot.resolve(".git/objects/info/alternates");
        Files.createDirectories(alternates.getParent());
        Files.writeString(alternates, objects.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void ignoreWorktrees(Path sourceRoot) throws IOException {
        Path exclude = sourceRoot.resolve(".git/info/exclude");
        Files.createDirectories(exclude.getParent());
        String existing = Files.isRegularFile(exclude) ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
        if (!existing.lines().anyMatch("/.worktrees/"::equals)) {
            String prefix = existing.isEmpty() || existing.endsWith("\n") ? existing : existing + System.lineSeparator();
            Files.writeString(exclude, prefix + "/.worktrees/" + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isClean(Path repository) throws IOException {
        return git(repository, "status", "--porcelain=v1", "--untracked-files=all").output().isBlank();
    }

    private static void requireCleanCanonical(Path sourceRoot) throws IOException {
        if (!isClean(sourceRoot)) {
            throw new IOException("Canonical world source has uncommitted changes; publish or revert them before starting AI integration");
        }
    }

    private static String head(Path repository) throws IOException {
        return git(repository, "rev-parse", "HEAD").output().trim();
    }

    private static boolean refExists(Path sourceRoot, String ref) throws IOException {
        return gitAllowFailure(sourceRoot, "show-ref", "--verify", "--quiet", ref).exitCode() == 0;
    }

    private static void updateRef(Path sourceRoot, String ref, String commit) throws IOException {
        git(sourceRoot, "update-ref", ref, commit);
    }

    private static String canonicalRef() {
        return "refs/heads/" + CANONICAL_BRANCH;
    }

    private static String revisionRef(long revision) {
        return "refs/allcraft/revisions/" + revision;
    }

    private static boolean rebaseInProgress(Path worktree) throws IOException {
        String gitDir = git(worktree, "rev-parse", "--git-dir").output().trim();
        Path directory = Path.of(gitDir);
        if (!directory.isAbsolute()) directory = worktree.resolve(directory);
        return Files.isDirectory(directory.resolve("rebase-merge")) || Files.isDirectory(directory.resolve("rebase-apply"));
    }

    private static List<String> conflictedFiles(Path worktree) throws IOException {
        String output = git(worktree, "diff", "--name-only", "--diff-filter=U").output();
        return output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static void rejectUnsupportedEntries(Path worktree) throws IOException {
        String raw = git(worktree, "diff", "--raw", canonicalRef()).output();
        if (raw.lines().anyMatch(line -> {
            String[] fields = line.split("\\s+", 6);
            return fields.length >= 2
                && (fields[0].contains("120000")
                    || fields[0].contains("160000")
                    || fields[1].contains("120000")
                    || fields[1].contains("160000"));
        })) {
            throw new IOException("AI candidates may not add symbolic links or nested Git repositories");
        }
    }

    private static GitResult git(Path directory, String... arguments) throws IOException {
        return git(directory, Map.of(), arguments);
    }

    private static GitResult git(Path directory, Map<String, String> environment, String... arguments) throws IOException {
        GitResult result = gitAllowFailure(directory, environment, arguments);
        if (result.exitCode() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + " failed: " + result.output());
        }
        return result;
    }

    private static GitResult gitAllowFailure(Path directory, String... arguments) throws IOException {
        return gitAllowFailure(directory, Map.of(), arguments);
    }

    private static GitResult gitAllowFailure(Path directory, Map<String, String> environment, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Path outputFile = Files.createTempFile("allcraft-git-", ".log");
        builder.redirectOutput(outputFile.toFile());
        Process process = builder.start();
        byte[] output;
        try {
            boolean exited = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("git command timed out after " + GIT_TIMEOUT.toMinutes() + " minutes");
            }
            output = Files.readAllBytes(outputFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for git", e);
        } finally {
            Files.deleteIfExists(outputFile);
        }
        return new GitResult(process.exitValue(), new String(output, StandardCharsets.UTF_8).trim());
    }

    public record Worktree(Path path, String branch, String baseCommit) {
    }

    public record TurnResult(String commit, List<String> conflicts, boolean changed) {
    }

    public record RebaseResult(String commit, List<String> conflicts, boolean conflicted) {
    }

    public record Promotion(String parentCommit, String candidateCommit, long parentRevision, long revision) {
    }

    private record GitResult(int exitCode, String output) {
    }
}
