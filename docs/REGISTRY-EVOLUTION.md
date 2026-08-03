# Runtime Registry Evolution

Allcraft can mutate frozen Minecraft registries from a world revision without globally unfreezing vanilla bootstrap code.

## Transaction model

- Registry writes are accepted only while an `AllcraftRegistries.Transaction` owns the current thread.
- The authoritative server assigns every registry numeric ID and emits an ordered registry plan.
- Clients consume that exact plan; a different operation, key, registry, ID, or operation count aborts the revision.
- After publication, peers compare a SHA-256 fingerprint of all built-in registries plus every world-layer registry named by the mutation plan before accepting `APPLIED`.
- The registry journal participates in the same publish, rollback, finalize, world-exit, and replay lifecycle as classes and resources.

## Runtime API

Patch entrypoints and migration hooks can use:

```java
AllcraftRegistries.registerLazy(registry, key, factory);
AllcraftRegistries.register(registry, key, value);
AllcraftRegistries.replace(registry, key, value);
AllcraftRegistries.retire(registry, key);
AllcraftRegistries.reactivate(registry, key);
AllcraftRegistries.remove(registry, key);

// Resolve and mutate the active world's dynamic registry layer:
AllcraftRegistries.registerLazy(Registries.JUKEBOX_SONG, songKey, factory);
```

`registerLazy` is the normal addition/replay operation. It preserves the original value, holder, and wire ID when reopening a world.

`AllcraftMutable.set(...)` is available for revision-scoped mutation of unlocked private fields. It records the old value in the active registry transaction.

## Stable identities

- Published registry IDs are never reused during a process lifetime.
- Leaving a committed world retires additions but keeps their key, holder, value, and numeric ID as compatibility tombstones.
- Reopening that world reactivates the same identities before ordinary gameplay resumes.
- Failed or uncommitted revisions are fully undone and do not retain additions.
- Hard removal exists for controlled migrations, but retirement is the safe default for values referenced by chunks, packets, stacks, entities, or third-party state.

## Dependent state

Allcraft updates the vanilla state that is normally built only during bootstrap:

- intrusive holders for blocks, items, fluids, entity types, and block-entity types;
- global block-state and fluid-state numeric tables;
- block-item mappings and holder component lookups;
- item component initializers;
- atomically published live entity/block-entity renderers, menu screens, and particle providers;
- entity default attributes and spawn placements.

Dynamic registry access is supplied separately on each peer (server world access or the client packet listener's synchronized access). Only registries touched by the ordered plan participate in its cross-peer digest, avoiding unrelated layer differences while still proving every evolved key and ID.

Core configuration fields on blocks, items, fluids, entity types, block entities, menus, particles, and block behavior are no longer `final`, allowing explicit revision migrations to update them.

## Identity replacement

Adding and retiring entries is general. Replacing the object identity behind an existing key is supported transactionally, but the patch must migrate live objects and any consumer-owned references. No JVM mechanism can automatically discover arbitrary references held by game code or future third-party code; in-place class evolution or field reconfiguration is preferred when identity must remain stable.

## Tests

```bash
tests/registries/run.sh
tests/jvm/minecraft-smoke.sh jvm/linux-x64 registry-block
tests/jvm/minecraft-smoke.sh jvm/linux-x64 new-mob new-music-disc lapis-crafting-table
```

The unit regression covers exact server/client plans, add/ensure/replace/retire/reactivate/remove, intrusive holders, rollback, committed tombstones, replay, and plan-conflict rejection. The Minecraft smoke test adds a synchronized block and item, reloads its models/language/recipe, exits the world, and reopens the committed revision.
