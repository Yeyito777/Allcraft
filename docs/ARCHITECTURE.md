# Allcraft Architecture

## Core model

Allcraft works like a Roblox-style engine and place:

- Allcraft is the installed base engine.
- A server owns the authoritative source and revision of its world.
- Exocortex modifies and compiles that source on the server.
- Clients receive compiled world overlays and assets; they never compile server patches.
- Single-player uses the same design through Minecraft's integrated server.

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
├── source/                  # authoritative editable world source
├── patches/
│   ├── manifest.json        # serverId, worldId, base revision, current revision
│   ├── source/              # ordered source diffs / commits
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

Clients never run a Java build when joining or receiving an update.

## Revision and cache strategy

- Allcraft's installed JAR is the static base engine.
- Active players normally receive small incremental revision artifacts.
- New or far-behind players receive a cumulative overlay for the current revision.
- A client with a compatible cached parent receives only missing increments.
- Artifact hashes guarantee that every participant staged identical bytes.

## Patch lifecycle

1. Exocortex edits the server's world source.
2. The server compiles the new revision.
3. The builder compares the previous and new outputs.
4. The builder creates separate server and client artifacts.
5. The server stages its server artifact.
6. Clients download and cache the client artifact.
7. Clients report `READY` for the revision and artifact hash.
8. The server announces `ACTIVATE <revision> AT <tick>`.
9. Server and clients apply the artifacts at that tick.
10. Everyone continues on the new revision on the following tick.

The activation tick is a live protocol message, not part of the permanent revision manifest.

## Security scope

Allcraft intentionally grants trusted servers maximal patch capability. There is no sandbox or patch-signing permission system. Hashes and revision checks exist for synchronization and corruption detection.
