# Allcraft Architecture

## Core model

Allcraft works like a Roblox-style engine and place:

- Allcraft is the installed base engine.
- A server owns the authoritative source and revision of its world.
- Exocortex modifies and compiles that source on the server.
- Clients receive compiled world overlays and assets; they never compile server patches.
- Single-player uses the same design through Minecraft's integrated server.

The `/allcraft ai <request>` worktree/build/retry pipeline and its conversation-scoped semantic tools are documented in [AI-COMMAND.md](AI-COMMAND.md).

## Roles

### Server

- Owns each world's editable source and source-patch history.
- Runs the Exocortex patch-generation agent.
- Compiles separate server and client artifacts.
- Distributes client artifacts and synchronizes their activation.
- Applies the server artifact through Allcraft's hardcoded runtime patch mechanism.

### Client

- Does **not** run the Exocortex patch-generation agent.
- Contains a hardcoded Allcraft patch receiver and runtime patch mechanism.
- Downloads, caches, stages, and applies compiled artifacts from the server.
- Reports readiness before synchronized activation.

### Single-player and LAN

- In single-player, the integrated server owns and compiles the world source.
- A LAN host behaves as the authoritative server.
- Joining LAN clients only receive compiled client artifacts.

## Identity

- Every Allcraft server generates and persists a random `serverId` UUID.
- Every Allcraft world generates and persists a random `worldId` UUID.
- Client caches are keyed by `serverId` and `worldId`, never by IP address.
- The last known address may be stored as display metadata only.

## Storage

### Server identity

```text
allcraft/
└── server.json              # serverId and server metadata
```

### Server or single-player world

```text
saves/<world>/
├── source/                  # Git-backed authoritative world source
│   ├── .git/                # finalized revision refs and canonical history
│   └── .worktrees/          # ignored private AI job checkouts
├── patches/
│   ├── manifest.json        # serverId, worldId, base revision, current revision
│   ├── source/              # ordered source diffs / commits
│   ├── build-cache/         # content-addressed client/server compiler outputs
│   ├── ai/jobs/             # persistent AI state and diagnostics
│   └── artifacts/
│       ├── client/          # compiled client deltas and cumulative overlays
│       └── server/          # compiled server deltas and cumulative overlays
└── level.dat
```

### Remote client cache

```text
patches/
└── <serverId>/
    └── <worldId>/
        ├── current.json
        ├── manifests/
        └── revisions/       # cached compiled client artifacts
```

## Patch types

### Source patches

- Stored only by the authoritative server/world.
- Ordinary source and asset changes produced by Exocortex.
- Preserve the editable history of the world.

### Runtime artifacts

- Precompiled JAR overlays consumed by the runtime patch mechanism.
- Client and server artifacts are produced separately.
- May contain changed/new classes, assets, sounds, models, shaders, and data.
- Include a manifest describing their base, parent revision, contents, and hash.

### Runtime resource application

- Client artifacts are mounted as ordered resource-pack overlays; newer revisions override older revisions.
- Texture, model, language, sound, shader, and other client-resource preparation runs on Minecraft's resource worker pool.
- Normal resource listeners swap prepared state on the game thread without installing a `LoadingOverlay` or disconnecting the player.
- Server `data/` overlays use Minecraft's asynchronous data reload pipeline for recipes, loot tables, tags, functions, and related data.
- Resource deletions are represented by exact resource-pack filters, so an overlay can add, modify, or remove resources.
- Client and server acknowledgements are sent only after resource reload and byte-level visibility checks finish.
- Ordered resource overlays are restored with the world's other runtime artifacts when a single-player world is reopened.

### Runtime class application

- The bundled Allcraft Java agent exposes JVM `Instrumentation`.
- Loaded classes are atomically redefined with JetBrains Runtime enhanced class redefinition.
- New classes are appended to Minecraft's system classloader and loaded without restarting the game.
- Registry/network-facing logical classes are compiled from `shared/`; side artifacts carry the same hashed class contract and side-only registry factories are rejected.
- Deleted/world-only classes remain executable but logically unreachable because arbitrary live Java references cannot be proven absent safely.
- Byte-identical definitions are skipped instead of forcing another JVM-wide redefinition.
- Optional static artifact entrypoints can initialize newly added code at activation.
- Before activation, a background coordinator compiles authoritative world source into separate client and server JARs.
- `javac` runs in a constrained child JVM so compilation cannot block or pollute the game JVM's heap.
- Compiler outputs are cached by source, classpath, compiler, and side; only changed inputs invalidate them.
- At tick `N`, the server applies its server JAR and clients apply the corresponding client JAR.
- Opening an evolved single-player world restores its ordered server and integrated-client artifacts automatically.
- World exit retires runtime code only after the client view and integrated server have fully drained their live objects.

Clients never run a Java build when joining or receiving an update.

Actual class redefinition still requires a short JVM safepoint. The bundled Allcraft JBR uses selective code flushing and keeps normal tiered compilation and C2 enabled globally during arbitrary evolution.

### Runtime registry application

- Frozen registries accept writes only under a revision-scoped Allcraft mutation lease; vanilla code cannot mutate them accidentally.
- The server assigns stable numeric IDs and sends an ordered registry plan with the client artifact.
- Every client applies the exact IDs and returns a full registry fingerprint with `APPLIED`; a mismatch aborts the revision.
- Additions, replacements, retirement, controlled removal, intrusive holders, block/fluid state IDs, and common client dispatch maps are journaled for rollback.
- The same transaction can resolve and evolve a registry from the active world registry layer; touched dynamic registries are included in the synchronized plan and digest.
- Published IDs are process-lifetime identities. World exit retains additions as retired tombstones, and artifact replay reactivates them with the same holders and IDs.

See [REGISTRY-EVOLUTION.md](REGISTRY-EVOLUTION.md) for the mutation API and identity rules.

### Runtime input application

- Client entrypoints can register world-scoped key mappings through `AllcraftKeyMappings`.
- Dynamic keys participate in the normal input indexes, controls UI, sorting, conflict display, rebinding, and options persistence.
- The user's binding is retained while a key is inactive, then restored when its world revision replays.
- Mapping callbacks, custom categories, open controls screens, rollback, and world switching use the same revision journal as registries.

## Revision and cache strategy

- Allcraft's installed JAR is the static base engine.
- Active players normally receive small incremental revision artifacts.
- New or far-behind players receive a cumulative overlay for the current revision.
- A client with a compatible cached parent receives only missing increments.
- Artifact hashes guarantee that every participant staged identical bytes.

## Patch lifecycle

1. Exocortex edits the server's world source.
2. `/allcraft apply` diffs it against the committed source snapshot.
3. The server compiles the affected dependency closure and builds separate client/server artifacts.
4. The server and clients validate and stage their artifacts; clients report `READY`.
5. The server announces `ACTIVATE <revision> AT <tick>`.
6. At that tick, peers publish classes, run migrations and the ordered registry plan, and activate resource/data transactions.
7. Peers report `APPLIED` with the resulting registry fingerprint; the server requests lifecycle `COMMIT` only when every fingerprint agrees.
8. Peers report `COMMITTED` while retaining rollback definitions.
9. The server atomically advances the source snapshot and manifest, then sends `FINALIZE`.
10. Any pre-finalization failure sends `ABORT` and restores the complete committed revision.

The activation tick is a live protocol message, not part of the permanent revision manifest.

See [CODE-REVISIONS.md](CODE-REVISIONS.md) for artifact, migration, retirement, rollback, and crash-recovery details.

## AI source integration

- Up to 32 Exocortex turns may edit separate linked worktrees concurrently.
- Each world has one ordered integration reservation held through distributed `FINALIZE`.
- Candidates rebase onto the latest finalized source and compile from their private checkout.
- Canonical source does not move during AI editing, conflict repair, or compilation.
- At commit, `allcraft/main` fast-forwards to the validated candidate and `refs/allcraft/revisions/<n>` records the source selected by the world manifest.
- Publication abort restores the parent source ref; restart recovery also resets source to the manifest-selected ref.
- Compiler, merge, contract, and activation diagnostics return to the same persistent Exocortex conversation automatically.

## Security scope

Allcraft intentionally grants trusted servers maximal patch capability. There is no sandbox or patch-signing permission system. Hashes and revision checks exist for synchronization and corruption detection.
