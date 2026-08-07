# AI Gameplay Benchmark Suite 1

Suite 1 sends 16 real gameplay requests to Exocortex. It does not use `AllcraftPatchCompiler` fixtures: every case receives a private world-source worktree, Sol at `low` effort, the scoped Minecraft tools, normal automatic repair, and the production revision lifecycle. Asset-bearing cases request original textures, models, sprites, and audio rather than allowing vanilla assets to stand in for them.

This is intentionally expensive. Use a fresh creative world and do not run it as part of routine smoke tests.

## Recommended environment

- Use a fresh world dedicated to this benchmark.
- Keep two clients connected when testing distributed synchronization. Integrated single-player is accepted for development, but does not prove multi-client behavior.
- Pre-generate a useful area before Phase A because one request disables generation of new chunks.
- Keep the client log visible and record frame hitches, loading screens, disconnects, and activation failures.
- Do not use `/allcraft apply` or launch unrelated AI jobs during a suite run.

## Phase A

Run once:

```text
/allcraft test suite-1-a
```

This atomically queues 12 jobs from one base revision. They edit concurrently in separate worktrees; integration and distributed finalization remain sequential.

| Case | Main coverage |
| --- | --- |
| `double-jump` | Existing movement code, live players, effects |
| `blink` | Keybinding, client/server payload, server authority, HUD |
| `ruby-item` | Item registry, model, language, recipe, creative caches |
| `spring-block` | Block/item IDs, state IDs, model, loot, collision behavior |
| `echo-cow` | Entity type, attributes, renderer, particles, loot, wire ID |
| `lapis-alchemy-table` | Block entity, menu, screen, persistence, processing |
| `comet-particle` | Particle type/provider/resources/network serialization |
| `moonlight-disc` | Sound event, jukebox data, item/model/recipe |
| `flying-boats` | Existing vehicle physics, input, multiplayer authority |
| `no-new-worldgen` | Chunk-generation internals and asynchronous stability |
| `zombie-invasion` | Persistent world scheduler and dynamic difficulty |
| `dirt-makeover` | Live models, language, particles, chunk meshes, F3+T durability |

Running `suite-1-a` again does not duplicate work. It prints current progress instead. Detailed state is persisted at:

```text
saves/<world>/patches/ai/suites/suite-1/current.json
```

Each case still has its ordinary job record under `patches/ai/jobs/<job-id>/job.json`. The suite manifest records case/job identity, state, attempts, final revision, diagnostics, and cleanup completion. It also records `phaseATiming` and `phaseBTiming`, each containing the completed-task count plus average and maximum completion time in milliseconds. Completion time runs from job creation through successful distributed finalization, so it includes editor queueing, repair attempts, sequential integration, and activation but excludes asynchronous cleanup.

## Phase A manual checks

Wait until all 12 jobs are `finalized`. Exercise each feature and confirm both clients agree. In particular:

1. Use double-jump and Blink in survival.
2. Craft/give and use `allcraft:ruby` and `allcraft:spring_block`.
3. Run `/summon allcraft:echo_cow`.
4. Use `allcraft:lapis_alchemy_table`, close it with inventory inside, and reopen it.
5. Run `/particle allcraft:comet ~ ~1 ~ 0.5 0.5 0.5 0.02 100`.
6. Play `allcraft:moonlight_disc` in a jukebox.
7. Fly a boat while another player observes it.
8. Approach the pre-generated area's edge without hanging or crashing.
9. Verify the invasion schedule survives save/reopen.
10. Inspect near and distant dirt, press F3+T, then rejoin; Diamond Dirt must remain consistent.

## Phase B preparation and launch

The first invocation after Phase A finalizes prints and arms the preparation checklist:

```text
/allcraft test suite-1-b
```

Before confirming:

1. Keep two clients connected.
2. Place and use `allcraft:lapis_alchemy_table`; leave ingredients stored in it.
3. Put `allcraft:moonlight_disc` in a player inventory, a container, and a jukebox.

Run the same command a second time to confirm preparation and launch the four jobs:

```text
/allcraft test suite-1-b
```

| Case | Main coverage |
| --- | --- |
| `lapis-table-evolution` | Existing placed block entities, inventories, menu evolution |
| `moonlight-disc-removal` | Class/resource deletion, registry retirement, semantic stack migration |
| `double-jump-cooldown` | First side of an intentional source conflict |
| `double-jump-hunger` | Second side of the conflict; both semantics must survive repair |

All four editors start from the same Phase B base. The final two deliberately target the same feature. A correct run detects/rebases or repairs the conflict in the appropriate existing Exocortex conversation and preserves both cooldown and hunger behavior.

## Restart and cumulative-client checks

After all 16 jobs finalize:

1. Save and quit normally, then reopen the world.
2. Verify table inventory/progress and invasion state survived.
3. Confirm Moonlight Disc stacks became vanilla Music Disc 13 and no removed class/resource is referenced.
4. Confirm double-jump has both the two-second cooldown and hunger restriction.
5. Press F3+T and recheck all models, names, particles, and sounds.
6. Join with a fresh client cache. It must receive the cumulative overlay, remain connected, and observe the same registry IDs and behavior without compiling source.
7. Confirm canonical `source/` is clean and finalized/cancelled worktrees have been removed.

## Passing criteria

- Phase A finalizes 12 jobs and Phase B finalizes 4 jobs.
- The world advances by exactly 16 suite revisions if no unrelated revisions were submitted.
- No client-side compilation, disconnect, loading screen, stale registry ID, or long integration freeze occurs.
- Every activation is observed identically by all connected clients.
- Phase B preserves live table state, migrates all removed-disc stacks, and retains both conflicting double-jump changes.
- Save/reopen, F3+T, and a fresh-client cumulative join retain the final game.
- The suite manifest reports 16 finalized cases and eventual cleanup for all 16.
- Phase A and Phase B each report average and maximum end-to-end task completion time.

## Deterministic regression

The regression does not contact Exocortex or spend model tokens:

```bash
tests/ai-benchmark/run.sh
```

It checks the 12+4 definitions, stable IDs, command discovery, phase gating, duplicate prevention, persistent result manifests, ordered atomic batch creation, crash cleanup, and the global 32-editor capacity boundary.
