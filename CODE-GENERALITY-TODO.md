# Code Generality TODO

> Scope: make arbitrary world-source patches use one production pipeline. Registry evolution remains main TODO item **3.5**.

- [x] **1. General revision differ.** Compare authoritative world source revisions `N` and `N+1` and automatically detect added, modified, moved, and deleted Java sources and resources without test-name-specific logic.
- [x] **2. Side and output classification.** Determine client, server, and shared compilation inputs from the source layout, and generate separate client/server outputs automatically.
- [x] **3. Incremental dependency compiler.** Compile the changed source dependency closure in the external bundled-JDK process, persist content-addressed results, and invalidate only affected cache entries.
- [x] **4. General artifact builder.** Emit revisioned client/server artifacts containing added and modified classes, deleted-class declarations, resources, resource deletions, optional activation/migration code, parent revision, and hashes.
- [x] **5. Automatic class retirement.** Track each world's active class set and generate tombstone definitions when a previously active class disappears, because loaded JVM classes cannot be physically unloaded individually.
- [x] **6. World-switch and rollback class reconciliation.** On rollback, reconnect, or world change, redefine modified base classes to the selected revision and retire classes that exist only in the world being left.
- [x] **7. Artifact-level state migrations.** Support optional arbitrary migration code for live instances, static state, caches, and persisted data, with revision context and without requiring every Minecraft subsystem to implement a new interface.
- [x] **8. Migration and activation ordering.** Define deterministic `prepare`, class publication, state migration, resource publication, and `commit` phases so new code never observes half-migrated state.
- [ ] **9. Pre-activation staging.** Validate and stage class bytes, resources, shaders, models, server data, and migration plans before clients report ready; clients must still never run `javac`.
- [ ] **10. Tick-synchronized atomic commit.** Change `READY` to mean fully staged, then publish server code, client code, resources, and migrated state at the agreed tick with only bounded safepoint/commit work remaining.
- [x] **11. Transactional rollback.** Preserve previous class definitions and state checkpoints so any class, resource, server-data, entrypoint, or migration failure restores the complete previous revision rather than leaving a partial patch active.
- [x] **12. Crash-safe revision recovery.** Persist transaction phase and migration/version markers so reopening a world can finish or roll back an interrupted revision deterministically.
- [x] **13. Move all tests onto the general pipeline.** Reimplement existing `/allcraft test ...` cases as ordinary source-revision fixtures submitted through the same differ, compiler, artifact builder, staging, activation, retirement, migration, and rollback path used by arbitrary AI patches.
- [x] **14. Generality regression suite.** Test arbitrary multi-file edits; class/field/method/constructor/interface addition and removal; class retirement; live-instance/static/save migration; failed compilation; failed activation; rollback; world switching; reconnect restoration; and repeated revisions under C2/JFR.
- [x] **15. Production patch entrypoint.** Expose a non-test server API/command that accepts an already-edited world source revision, builds it through the general pipeline, reports diagnostics, and submits it for synchronized activation—the interface the Exocortex backend will call later.
- [x] **16. Remove production dependence on test switches.** Keep named tests only as fixtures and diagnostics; no production compilation, artifact construction, class lifecycle, or activation behavior may depend on a hard-coded test name.
