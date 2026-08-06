# AI Benchmark Suite TODO

- [x] 1. Define the 16 behavior-only benchmark requests, stable case IDs, exact runtime resource IDs, two execution waves, and global acceptance criteria.
- [x] 2. Add a persistent per-world suite-run manifest that records the run ID, base revision, phase job IDs, timestamps, and derived progress without conflating suite state with AI job state.
- [x] 3. Add an atomic AI batch-enqueue API so `suite-1-a` either persists all 12 independent jobs in sequence order or queues none, while respecting canonical-source cleanliness and the global editor capacity.
- [x] 4. Implement `/allcraft test suite-1-a` to start the 12 concurrent feature requests, prevent accidental duplicate active runs, and report the run ID and job IDs.
- [x] 5. Implement `/allcraft test suite-1-b` to require a fully finalized phase A, present/check the live-state preparation checklist, and enqueue the four evolution/removal/conflict requests with the last two deliberately concurrent.
- [x] 6. Integrate both suite names into `/allcraft test` discovery, suggestions, and dispatch without treating them as deterministic fixture patches or reserving the revision pipeline prematurely.
- [x] 7. Persist useful benchmark metadata and provide concise suite progress/results, including finalized/failed counts, attempts, resulting revisions, and cleanup state.
- [x] 8. Add deterministic regression coverage for prompt definitions, 12+4 wave composition, persistent manifests, duplicate/gating rules, exact ordering, and command routing without launching paid AI turns.
- [x] 9. Document how to run, prepare, observe, manually verify, restart, and evaluate the 16-query benchmark with two connected clients and a fresh-client cumulative-overlay check.
- [x] 10. Compile mirrored client/server sources, run launcher/worktree/benchmark and core JVM regressions, verify source parity and clean diffs, then commit and push.
