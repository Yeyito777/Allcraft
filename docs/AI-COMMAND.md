# AI command prototype

## Command

```text
/allcraft ai <request>
```

The integrated or dedicated server starts an Exocortex conversation asynchronously. Minecraft's tick thread does not wait for Exocortex, and chat reports either the resulting `exo:<conversation-id>` or a concise launch failure.

This phase only creates the conversation. It does **not** yet create a Git worktree, merge the result, build a revision, retry failures, or activate the result. Those steps are tracked in `ai-worktree-pipeline-todo.md`.

## Current machine-specific integration

Allcraft currently invokes:

```text
/home/yeyito/Workspace/exocortex/external-tools/exo-cli/bin/exo
```

The request is written directly to the process's UTF-8 stdin. `ProcessBuilder` is used without a shell. The conversation is created detached in the Exocortex sidebar folder `allcraft/logs`, and Exocortex's title-generation job chooses its title.

## Conversation tools

Every world receives this infrastructure module in its authoritative source:

```text
saves/<world>/source/.allcraft/exocortex/
└── minecraft-tools.ts
```

Exocortex compiles, hashes, and loads the complete module from its conversation workspace. The module owns the per-conversation semantic index and exports:

- `minecraft_glob` — lists blocks, items, entities, block entities, particles, sounds, textures, models, blockstates, recipes, tags, registries, shaders, fonts, and other content by resource ID.
- `minecraft_grep` — searches exact source/resource locations and follows relationships such as block → blockstate → model → texture, particle → definition → texture, and sound event → `sounds.json` → OGG.

The tool module infers its source root from its own location, so it is automatically scoped to the active world's source now and to an AI worktree later. The tools are read-only, paginated, cancellation-aware, and safe to schedule in parallel.

The prototype conversation receives this exact internal-tool allowlist:

```text
read
write
edit
patch
minecraft_glob
minecraft_grep
```

No external tools are enabled. The standard editing tools allow the agent to edit absolute paths returned by the Minecraft tools; the later worktree pipeline will confine those edits to a private worktree.

## Source revision behavior

`.allcraft/` is installation infrastructure, not world gameplay content. It is excluded from world revision diffs. Opening an existing world refreshes the installed Minecraft tool entry and implementation before the world baseline is used.
