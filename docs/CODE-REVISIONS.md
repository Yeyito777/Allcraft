# General Code Revisions

## Production entrypoint

Edit the authoritative source under `saves/<world>/source/`, then run:

```text
/allcraft apply [label]
```

The command runs the same pipeline used by code/resource test fixtures. Clients only receive compiled artifacts and never run `javac`.

Alternatively, `/allcraft ai <request>` performs edits in a private Git worktree, builds against that checkout, and promotes it only through the normal transactional revision lifecycle. See [AI-COMMAND.md](AI-COMMAND.md).

## Source layout

```text
source/
├── client/                    # side-only client integrations and assets/
├── server/                    # side-only server integrations and data/
├── shared/                    # canonical logical classes used by both sides
└── allcraft-revision.json     # optional lifecycle hooks
```

Every revision is compared with `patches/revisions/current-source.json`. Added, changed, moved, and deleted files are discovered by path and SHA-256. Java reverse dependencies are conservatively recompiled; outputs are cached by side, compiler, source closure, and classpath identity.

Registry- and network-facing logical registration belongs in `shared/`. The builder verifies that every shared class compiles byte-identically on both sides, embeds a hashed shared-class contract, and rejects side-only registry mutation. Screens, renderers, particle providers, keybindings, and other genuinely side-only integrations remain under `client/` or `server/`.

## Artifact contents

Separate client/server JARs contain:

- changed and added class definitions;
- explicit `addedClasses` and `deletedClasses` sets;
- parent definitions required for validation and rollback;
- compatibility retirement definitions retained for old artifact replay;
- changed resources and exact resource deletion filters;
- parent/current revision, world/server/patch identity, hashes, and lifecycle hooks.

The JVM cannot remove one loaded class, and arbitrary live objects cannot be proven unreachable. Deletion therefore removes future source/registry reachability but leaves the class's last executable definition resident. This fail-safe rule prevents live menus, entities, callbacks, lambdas, method handles, active frames, and third-party references from being converted into `NoClassDefFoundError`. A later revision/world can redefine the retained identity normally.

## Lifecycle hooks

`source/allcraft-revision.json` may name arbitrary static hook classes independently for each side:

```json
{
  "client": {
    "prepare": ["example.ClientMigration"],
    "migrate": ["example.ClientMigration"],
    "commit": ["example.ClientMigration"],
    "rollback": ["example.ClientMigration"]
  },
  "server": {
    "prepare": ["example.ServerMigration"],
    "migrate": ["example.ServerMigration"],
    "commit": ["example.ServerMigration"],
    "rollback": ["example.ServerMigration"]
  }
}
```

Each listed class provides the corresponding static method:

```java
public static void allcraftPrepare(AllcraftRuntime.MigrationContext context) throws Exception;
public static void allcraftMigrate(AllcraftRuntime.MigrationContext context) throws Exception;
public static void allcraftCommit(AllcraftRuntime.MigrationContext context) throws Exception;
public static void allcraftRollback(AllcraftRuntime.MigrationContext context) throws Exception;
```

No-argument forms are also accepted. Hook sources are automatically included in every artifact that references them, even when their source text did not change.

Every declared hook/entrypoint class must exist in the selected source revision or the installed base. The builder and runtime both enforce this, so a class left resident in the JVM by an aborted transaction cannot become an undeclared dependency of a later revision or break fresh-process replay.

`MigrationContext` exposes the world, side, parent/target revisions, patch ID, artifact path, in-memory checkpoints, and crash-safe string checkpoints. Durable checkpoints are stored beside the immutable artifact and are available to restart recovery.

## Transaction order

1. Diff and compile in the bundled-JDK child process.
2. Verify artifact hash, class files, hooks, and resource declarations.
3. Stream client bytes; all peers report `READY` only after staging/preflight.
4. Publish dedicated prepare/rollback hook implementations.
5. Run `prepare` against the old game revision.
6. Atomically redefine loaded classes and activate genuine additions at the scheduled tick.
7. Run `migrate`, legacy activation entrypoints, the server-assigned registry plan, and resource/data publication.
8. Peers report `APPLIED` with a complete registry-ID fingerprint; any disagreement aborts publication.
9. Run `commit`; peers report `COMMITTED`.
10. Persist the source snapshot and manifest, send `FINALIZE`, and seal the revision into the world-exit rollback chain.

Any failure before finalization sends `ABORT`. Modified definitions are restored, new classes lose their registered/cache reachability but remain executable for existing references, rollback hooks receive their checkpoints, and client/server resource overlays return to the committed manifest.

## Recovery and world switching

- `patches/transaction.json` records staged, scheduled, publishing, committing, and rollback phases.
- On restart, the committed manifest remains authoritative. An interrupted uncommitted revision runs its dedicated rollback hook without replaying migration.
- Committed revisions retain reversible definition chains in process memory. Leaving a world unwinds them in reverse order, restores base classes, safely retains world-only class bodies, and retires published registry identities without reusing their IDs.
- Client teardown runs only after its old screen/level are detached and an integrated server has completely stopped and drained live world objects. Reopening a world then replays its immutable ordered artifacts.

## Regression tests

```bash
tests/code-generality/run.sh
tests/jvm/run.sh jvm/linux-x64
tests/ai-worktrees/run.sh
```

The generality suite covers arbitrary client/server inputs, shared-contract divergence/tampering, dependency closure, content-addressed cache hits, resource movement, structural class evolution, live/static migration, compile and migration failures, rollback, live-object-safe retirement, world switching, reconnect replay, and JFR-enabled execution. The JVM suite covers methods, fields, constructors, interfaces, active frames, C2, attach, and JFR behavior.
