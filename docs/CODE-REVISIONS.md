# General Code Revisions

## Production entrypoint

Edit the authoritative source under `saves/<world>/source/`, then run:

```text
/allcraft apply [label]
```

The command runs the same pipeline used by code/resource test fixtures. Clients only receive compiled artifacts and never run `javac`.

## Source layout

```text
source/
├── client/                    # client-distribution Java and assets/
├── server/                    # dedicated-server Java and data/
├── shared/                    # optional inputs included on both sides
└── allcraft-revision.json     # optional lifecycle hooks
```

Every revision is compared with `patches/revisions/current-source.json`. Added, changed, moved, and deleted files are discovered by path and SHA-256. Java reverse dependencies are conservatively recompiled; outputs are cached by side, compiler, source closure, and classpath identity.

## Artifact contents

Separate client/server JARs contain:

- changed and added class definitions;
- explicit `addedClasses` and `deletedClasses` sets;
- parent definitions required for validation and rollback;
- shape-compatible tombstones for class retirement;
- changed resources and exact resource deletion filters;
- parent/current revision, world/server/patch identity, hashes, and lifecycle hooks.

The JVM cannot remove one loaded class. A deleted or world-only class is therefore redefined to a tombstone that preserves its JVM shape and throws `NoClassDefFoundError` from executable bodies. It can later be reactivated by another revision/world.

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

`MigrationContext` exposes the world, side, parent/target revisions, patch ID, artifact path, in-memory checkpoints, and crash-safe string checkpoints. Durable checkpoints are stored beside the immutable artifact and are available to restart recovery.

## Transaction order

1. Diff and compile in the bundled-JDK child process.
2. Verify artifact hash, class files, hooks, and resource declarations.
3. Stream client bytes; all peers report `READY` only after staging/preflight.
4. Publish dedicated prepare/rollback hook implementations.
5. Run `prepare` against the old game revision.
6. Atomically redefine loaded classes and activate genuine additions at the scheduled tick.
7. Run `migrate`, legacy activation entrypoints, and resource/data publication.
8. Peers report `APPLIED`.
9. Run `commit`; peers report `COMMITTED`.
10. Persist the source snapshot and manifest, send `FINALIZE`, and seal the revision into the world-exit rollback chain.

Any failure before finalization sends `ABORT`. Class definitions are restored, new classes are retired, rollback hooks receive their checkpoints, and client/server resource overlays return to the committed manifest.

## Recovery and world switching

- `patches/transaction.json` records staged, scheduled, publishing, committing, and rollback phases.
- On restart, the committed manifest remains authoritative. An interrupted uncommitted revision runs its dedicated rollback hook without replaying migration.
- Committed revisions retain reversible definition chains in process memory. Leaving a world unwinds them in reverse order, restores base classes, and tombstones world-only additions.
- Reopening a world replays its immutable ordered artifacts. Remote client disconnect also clears in-flight transactions and restores base code/resources.

## Regression tests

```bash
tests/code-generality/run.sh
tests/jvm/run.sh jvm/linux-x64
```

The generality suite covers arbitrary client/server inputs, dependency closure, content-addressed cache hits, resource movement, structural class evolution, live/static migration, compile and migration failures, rollback, class retirement, world switching, reconnect replay, and JFR-enabled execution. The JVM suite covers methods, fields, constructors, interfaces, active frames, C2, attach, and JFR behavior.
