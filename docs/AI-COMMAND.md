# AI worktree pipeline

## Commands

```text
/allcraft ai <request>
/allcraft ai status [job-id-or-prefix]
/allcraft ai cancel <job-id-or-prefix>
/allcraft ai retry <job-id-or-prefix>
```

`/allcraft ai <request>` creates a persistent world job. Minecraft's tick thread never waits for Git, Exocortex, or `javac`; progress and the final world revision are reported in chat and through `status`.

## Job lifecycle

```text
triggered → queued → editing → awaiting-integration
                    ↑              │
                    └ failed/conflicted/retrying
                                   │
                         staging → activating → finalized
```

A cancelled job never publishes. Job state and diagnostics survive a server restart.

## Source and job layout

```text
saves/<world>/
├── source/
│   ├── .git/                         # canonical world-source history
│   └── .worktrees/<job-uuid>/        # ignored linked AI checkout
└── patches/ai/jobs/<job-uuid>/
    ├── job.json                      # request, state, Exocortex ID, commits, revision
    └── diagnostics.txt               # latest integration failure
```

The checked-out `allcraft/main` branch and `refs/allcraft/revisions/<n>` identify finalized source. Repository object alternates reuse the installed Allcraft clone where possible, so each world does not duplicate the base source's Git objects.

## Workflow and invariants

1. The server records the request, base world revision, and canonical source commit.
2. A linked worktree and `allcraft/ai/<job-uuid>` branch are created.
3. Exocortex receives the exact worktree path, world revision, request, and scoped Minecraft tools.
4. When the turn ends, Allcraft rejects empty changes, unsupported links/repositories, unresolved conflicts, or edits made while canonical source is dirty, then commits the candidate branch itself.
5. Up to 32 Exocortex editing turns may run server-wide. Integration is one ordered queue per world.
6. The candidate is rebased onto the latest finalized source. Conflicts are left in its worktree and sent back to the same Exocortex conversation.
7. Client and server artifacts are compiled and preflighted directly from the private worktree. Canonical source is still unchanged.
8. A successful candidate reserves the world's revision slot through `FINALIZE`, preventing two candidates from using the same parent revision.
9. The existing `stage → READY → ACTIVATE → APPLIED → COMMIT → FINALIZE` protocol publishes the prepared artifacts. Clients receive compiled artifacts and never run `javac`.
10. Only at the commit/finalize boundary does canonical source fast-forward to the candidate. Any abort restores `refs/allcraft/revisions/<parent>` and sends diagnostics back to Exocortex for another repair turn.
11. Finalized/cancelled worktrees and branches are removed. Failed worktrees are retained until repaired or cancelled.

The pipeline is intentionally capability-oriented rather than sandboxed. The prompt confines the trusted agent to its worktree, and integration verifies that canonical source remained clean; this is a correctness boundary, not a hostile-code security boundary.

## Exocortex integration

Allcraft currently invokes this machine-specific executable:

```text
/home/yeyito/Workspace/exocortex/external-tools/exo-cli/bin/exo
```

Conversations are placed in the Exocortex sidebar folder `allcraft/logs`; Exocortex generates their titles. New Allcraft editing conversations explicitly use `openai/gpt-5.6-luna` at `max` reasoning effort rather than inheriting Exocortex's defaults. Repair turns also reassert `max` effort on the existing conversation. Allcraft durably reserves each conversation ID before creation, starts turns detached, observes completion through `exo info --json`, and sends repair diagnostics through `exo send --conv <id>`. Launch locks, reserved IDs, and candidate-commit recovery make server-stop/crash windows restart-idempotent.

Each worktree contains:

```text
.allcraft/exocortex/minecraft-tools.ts
```

The conversation receives exactly these internal tools:

```text
read
write
edit
patch
minecraft_glob
minecraft_grep
```

No external tools are enabled. `minecraft_glob` discovers semantic game content—blocks, items, entities, particles, sounds, models, textures, recipes, tags, registries, shaders, fonts, and more. `minecraft_grep` searches mapped source/assets and follows resource relationships such as block → blockstate → model → texture and sound event → `sounds.json` → OGG. The module infers the assigned worktree from its own path, so agents do not need to know Minecraft's mapped directory structure.

`.allcraft/`, `.git`, and `.worktrees/` are revision infrastructure and are excluded from gameplay diffs.

## Failure handling

Compiler diagnostics, lifecycle-contract failures, merge conflicts, client/server staging failures, registry mismatches, and activation rollbacks all return to the same conversation. Exocortex edits the preserved worktree, and the candidate re-enters the ordered integration queue automatically. A missing lifecycle/entrypoint class is rejected from the selected source revision; an aborted JVM's leftover loaded class can never make a later revision appear valid.

## Tests

```bash
bun test tests/exocortex-tools/minecraft-tools.test.ts
tests/ai-launcher/run.sh
tests/ai-worktrees/run.sh
tests/code-generality/run.sh
tests/ai-pipeline/minecraft-e2e.sh   # live Exocortex + xenv + IPC
```

The worktree regression covers Git history, successful promotion, merge repair, rollback/restart recovery, symlink rejection, crash-window candidate recovery, and 32 linked worktrees. The live IPC validation covers a successful AI asset revision, cancellation without publication, automatic compiler-error repair, two parallel requests with sequential revisions and conflict repair, distributed activation rollback/repair, worktree cleanup, and world reopen/replay.
