package net.minecraft.allcraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class AllcraftAiWorktreeRegression {
    private AllcraftAiWorktreeRegression() {
    }

    public static void main(String[] args) throws Exception {
        Path world = Files.createTempDirectory("allcraft-ai-worktrees-");
        try {
            Path source = world.resolve("source");
            Files.createDirectories(source.resolve("client/assets/allcraft/textures/block"));
            Files.writeString(source.resolve("shared.txt"), "base\n");
            Files.writeString(source.resolve("client/assets/allcraft/textures/block/base.txt"), "asset\n");
            AllcraftSourceRepository.initialize(world, 0L);
            require(AllcraftSourceRepository.isCleanCanonical(world), "baseline is clean");

            AllcraftSourceRepository.Worktree first = AllcraftSourceRepository.createWorktree(world, id(1), 0L);
            AllcraftSourceRepository.Worktree second = AllcraftSourceRepository.createWorktree(world, id(2), 0L);
            Files.writeString(first.path().resolve("shared.txt"), "first\n");
            var firstTurn = AllcraftSourceRepository.commitTurn(first.path(), id(1), 1, first.baseCommit(), null);
            require(firstTurn.changed(), "first candidate committed");
            var firstRebase = AllcraftSourceRepository.rebaseOntoCanonical(world, first.path());
            require(!firstRebase.conflicted(), "first candidate rebases");
            AllcraftSourceRepository.verifyCandidate(world, first.path(), firstRebase.commit());
            var firstPromotion = AllcraftSourceRepository.promote(world, firstRebase.commit(), 0L, 1L);
            AllcraftSourceRepository.recordFinalized(world, 0L, 1L, "revision one");
            require(Files.readString(source.resolve("shared.txt")).equals("first\n"), "first candidate promoted");
            AllcraftSourceRepository.cleanup(world, first.path(), first.branch());

            Files.writeString(second.path().resolve("shared.txt"), "second\n");
            var secondTurn = AllcraftSourceRepository.commitTurn(second.path(), id(2), 1, second.baseCommit(), null);
            require(secondTurn.changed(), "second candidate committed");
            var conflicted = AllcraftSourceRepository.rebaseOntoCanonical(world, second.path());
            require(conflicted.conflicted() && conflicted.conflicts().contains("shared.txt"), "merge conflict detected");
            Files.writeString(second.path().resolve("shared.txt"), "first + second\n");
            var repaired = AllcraftSourceRepository.commitTurn(second.path(), id(2), 2, second.baseCommit(), secondTurn.commit());
            require(repaired.changed() && repaired.conflicts().isEmpty(), "agent repair continues rebase");
            AllcraftSourceRepository.verifyCandidate(world, second.path(), repaired.commit());
            AllcraftSourceRepository.promote(world, repaired.commit(), 1L, 2L);
            AllcraftSourceRepository.recordFinalized(world, 1L, 2L, "revision two");
            AllcraftSourceRepository.cleanup(world, second.path(), second.branch());
            require(Files.readString(source.resolve("shared.txt")).equals("first + second\n"), "repaired candidate promoted");

            AllcraftSourceRepository.Worktree rollback = AllcraftSourceRepository.createWorktree(world, id(3), 2L);
            Files.writeString(rollback.path().resolve("shared.txt"), "must roll back\n");
            String rollbackCommit = AllcraftSourceRepository.commitTurn(rollback.path(), id(3), 1, rollback.baseCommit(), null).commit();
            require(
                AllcraftSourceRepository.commitTurn(rollback.path(), id(3), 1, rollback.baseCommit(), null).changed(),
                "candidate commit is recovered after a crash before job-state persistence"
            );
            require(
                !AllcraftSourceRepository.commitTurn(rollback.path(), id(3), 1, rollback.baseCommit(), rollbackCommit).changed(),
                "known candidate without a repair is not treated as new work"
            );
            var promotion = AllcraftSourceRepository.promote(world, rollbackCommit, 2L, 3L);
            AllcraftSourceRepository.rollback(world, promotion);
            require(Files.readString(source.resolve("shared.txt")).equals("first + second\n"), "failed activation restores canonical source");
            AllcraftSourceRepository.cleanup(world, rollback.path(), rollback.branch());

            AllcraftSourceRepository.Worktree infrastructure = AllcraftSourceRepository.createWorktree(world, id(4), 2L);
            Files.createDirectories(infrastructure.path().resolve(".allcraft"));
            Files.writeString(infrastructure.path().resolve(".allcraft/agent-owned.txt"), "forbidden\n");
            Files.writeString(infrastructure.path().resolve("shared.txt"), "gameplay plus forbidden infrastructure\n");
            String infrastructureCommit = AllcraftSourceRepository.commitTurn(
                infrastructure.path(), id(4), 1, infrastructure.baseCommit(), null
            ).commit();
            boolean infrastructureRejected = false;
            try {
                AllcraftSourceRepository.verifyCandidate(world, infrastructure.path(), infrastructureCommit);
            } catch (Exception expected) {
                infrastructureRejected = expected.getMessage().contains("infrastructure");
            }
            require(infrastructureRejected, "worktree infrastructure edit rejected");
            AllcraftSourceRepository.cleanup(world, infrastructure.path(), infrastructure.branch());

            AllcraftSourceRepository.Worktree interruptedCreation = AllcraftSourceRepository.createWorktree(world, id(5), 2L);
            new ProcessBuilder("git", "-C", source.toString(), "worktree", "remove", "--force", interruptedCreation.path().toString())
                .inheritIO()
                .start()
                .waitFor();
            require(!Files.exists(interruptedCreation.path()), "simulated interrupted worktree has no checkout");
            AllcraftSourceRepository.Worktree recoveredCreation = AllcraftSourceRepository.createWorktree(world, id(5), 2L);
            require(Files.isRegularFile(recoveredCreation.path().resolve("shared.txt")), "existing AI branch checkout recovered");
            AllcraftSourceRepository.cleanup(world, recoveredCreation.path(), recoveredCreation.branch());

            AllcraftSourceRepository.Worktree symlink = AllcraftSourceRepository.createWorktree(world, id(6), 2L);
            Files.delete(symlink.path().resolve("shared.txt"));
            Files.createSymbolicLink(symlink.path().resolve("shared.txt"), Path.of("client/assets/allcraft/textures/block/base.txt"));
            boolean symlinkRejected = false;
            try {
                String symlinkCommit = AllcraftSourceRepository.commitTurn(
                    symlink.path(), id(6), 1, symlink.baseCommit(), null
                ).commit();
                AllcraftSourceRepository.verifyCandidate(world, symlink.path(), symlinkCommit);
            } catch (Exception expected) {
                symlinkRejected = expected.getMessage().contains("symbolic links");
            }
            require(symlinkRejected, "regular-file to symlink replacement rejected");
            AllcraftSourceRepository.cleanup(world, symlink.path(), symlink.branch());

            List<AllcraftSourceRepository.Worktree> worktrees = new ArrayList<>();
            try (var executor = Executors.newFixedThreadPool(32)) {
                var futures = new ArrayList<java.util.concurrent.Future<AllcraftSourceRepository.Worktree>>();
                for (int index = 10; index < 42; index++) {
                    String job = id(index);
                    futures.add(executor.submit(() -> AllcraftSourceRepository.createWorktree(world, job, 2L)));
                }
                for (var future : futures) worktrees.add(future.get());
            }
            require(worktrees.size() == AllcraftAiJobs.MAX_PARALLEL_EDITORS, "32 linked worktrees supported");
            for (AllcraftSourceRepository.Worktree worktree : worktrees) {
                require(Files.isRegularFile(worktree.path().resolve("shared.txt")), "worktree contains complete source");
                AllcraftSourceRepository.cleanup(world, worktree.path(), worktree.branch());
            }

            // Simulate a crash after source promotion but before the world manifest advanced.
            AllcraftSourceRepository.Worktree interrupted = AllcraftSourceRepository.createWorktree(world, id(50), 2L);
            Files.writeString(interrupted.path().resolve("shared.txt"), "interrupted\n");
            String interruptedCommit = AllcraftSourceRepository.commitTurn(
                interrupted.path(), id(50), 1, interrupted.baseCommit(), null
            ).commit();
            AllcraftSourceRepository.promote(world, interruptedCommit, 2L, 3L);
            AllcraftSourceRepository.initialize(world, 2L);
            require(Files.readString(source.resolve("shared.txt")).equals("first + second\n"), "restart restores manifest-selected source revision");
            AllcraftSourceRepository.cleanup(world, interrupted.path(), interrupted.branch());

            require(firstPromotion.parentRevision() == 0L, "promotion records parent revision");
            System.out.println("Allcraft AI worktree regression passed");
        } finally {
            deleteTree(world);
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
