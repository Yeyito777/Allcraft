# AI Worktree Pipeline TODO

## Core invariant

An AI job may edit only its private world-source worktree. Canonical world source advances only after the candidate has merged cleanly, built successfully, passed validation, and entered Allcraft's normal transactional revision lifecycle.

- [ ] 1. Define the persistent AI job model and state machine: trigger, queued, editing, awaiting integration, conflicted/failed, retrying, staging, activating, finalized, cancelled.
- [ ] 2. Give every world source a Git-backed revision history and create ignored linked worktrees at `saves/<world>/source/.worktrees/<job-uuid>`.
- [ ] 3. Add a scheduler allowing up to 32 concurrent AI worktrees server-wide while keeping each world's integration jobs in one ordered queue.
- [ ] 4. Implement the AI trigger that creates the branch/worktree and invokes Exocortex with the request, world identity, base revision, and exact worktree path.
- [ ] 5. Implement `minecraft_glob` as a stable semantic discovery tool for blocks, items, entities, particles, sounds, models, textures, recipes, registries, and other game content without requiring the agent to know mapped paths.
- [ ] 6. Implement `minecraft_grep` as a stable semantic search tool for source and assets, including textures, sounds, particle textures, block/entity UVs, entity textures, models, and resource references; return resource IDs, source paths, and useful provenance.
- [ ] 7. Expose those scoped tools to the Exocortex job and verify that every edit is confined to its assigned worktree.
- [ ] 8. When an AI turn ends, inspect and validate its changes, reject an empty or malformed result, and enqueue the candidate for integration automatically.
- [ ] 9. Implement the sequential integration worker: update the candidate against the latest canonical source, detect merge conflicts, and preserve linear parent-revision ordering.
- [ ] 10. Build and validate the candidate in a private integration checkout before changing canonical source; never leave the authoritative source broken after a failed build.
- [ ] 11. On a conflict, compiler error, contract error, or validation failure, send structured diagnostics back to the same Exocortex conversation, preserve its worktree, and automatically repeat the edit → integration loop.
- [ ] 12. After successful validation, atomically promote the source commit and hand its prepared client/server artifacts to the existing stage → READY → ACTIVATE → APPLIED → COMMIT → FINALIZE lifecycle.
- [ ] 13. Keep integration serialized through revision finalization—not merely through the Git merge—so two jobs cannot publish artifacts with the same parent revision.
- [ ] 14. If distributed staging or activation aborts, keep canonical source on the last finalized commit, report the failure to the AI job, and permit a corrected retry.
- [ ] 15. Clean up finalized/cancelled worktrees and branches, retain failed worktrees for repair, and recover queued/running jobs safely after a server restart.
- [ ] 16. Add status, cancellation, retry, and diagnostics commands/API for players and operators, including queue position and the resulting world revision.
- [ ] 17. Test semantic glob/grep coverage, one successful request, build-error repair, merge-conflict repair, activation rollback, restart recovery, and 32 concurrent worktrees with strictly sequential finalized revisions.
- [ ] 18. Add an end-to-end test where an AI-generated source/asset/registry change is built once on the server, synchronized, and activated on the integrated server and connected clients without client compilation.
- [ ] 19. Document the trigger, job lifecycle, tool contract, concurrency rules, failure behavior, and worktree layout in `docs/`.
- [ ] 20. Compile the complete trees, run the JVM/fixture/integration suites, manually exercise the workflow in Minecraft, then commit and push the completed pipeline.
