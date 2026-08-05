# AI Command and Custom Tools TODO

## Scope

This phase wires `/allcraft ai <request>` to a real Exocortex conversation with Minecraft-aware discovery tools. It does not yet implement the parallel Git-worktree, merge, build, retry, or revision-promotion pipeline from `ai-worktree-pipeline-todo.md`.

- [x] 1. Add a conversation-scoped TypeScript toolset at `source/.allcraft/exocortex/minecraft-tools.ts`, so every cloned world source and future AI worktree carries the same tool entry point and the toolset can infer its source root from its own module location.
- [x] 2. Implement `minecraft_glob` as a semantic inventory tool for discoverable categories such as blocks, items, entities, block entities, particles, sounds, textures, models, blockstates, recipes, tags, registries, shaders, fonts, and data resources.
- [x] 3. Implement `minecraft_grep` as a semantic source/resource search tool with exact paths and line locations plus useful relationships such as block → blockstate → model → texture, sound event → `sounds.json` → OGG, particle → definition → texture, and entity → renderer/model/texture.
- [x] 4. Make both tools read only, conversation scoped, cancellation aware, bounded/paginated, parallel safe, and rooted to the active world-source checkout without requiring the model to supply an arbitrary filesystem root.
- [x] 5. Add TypeScript tests for tool-module loading, category discovery, text/resource-ID search, relationship traversal, pagination, and isolation between two different source roots.
- [x] 6. Update the Exocortex `exo-cli` protocol mirror to include current conversation tool-policy and custom-module fields used by the daemon.
- [x] 7. Extend `exo send` with repeatable custom-tool-module and exact internal/external-tool selection flags, using Exocortex's draft tool-policy protocol so custom modules are loaded and enabled atomically before the new conversation's first turn.
- [x] 8. Validate custom module paths in `exo-cli`, surface module-load/schema/name-collision errors clearly, and include the enabled custom tools in structured CLI output.
- [x] 9. Add an `exo send` folder option that resolves or creates the nested Exocortex sidebar folder `allcraft/logs` and creates the conversation there directly.
- [x] 10. Add an `exo send` option that leaves the title to the daemon's title-generation job instead of installing the current `cli: ...` title, while preserving existing CLI behavior for callers that do not request it.
- [x] 11. Add exo-cli parsing, protocol, help, and command tests covering multiple custom modules, exact tool allowlists, nested folder placement, daemon-generated titles, detached sends, and failure before the first turn when tool setup is invalid.
- [x] 12. Add an Allcraft server-side AI launcher that resolves the active world's `source/.allcraft/exocortex/minecraft-tools.ts` and invokes the machine-specific executable `/home/yeyito/Workspace/exocortex/external-tools/exo-cli/bin/exo` with `ProcessBuilder`, without shell interpolation.
- [x] 13. Send the exact player request over the child process's UTF-8 stdin, attach `minecraft_glob` and `minecraft_grep`, select the intended standard edit tools, request daemon title generation, place the conversation in `allcraft/logs`, start it detached, and capture its conversation ID.
- [x] 14. Run the launcher away from the Minecraft tick thread, report immediate start/failure status in chat, log stderr and exit status, and report the resulting Exocortex conversation ID without blocking gameplay.
- [x] 15. Register `/allcraft ai <request>` with a greedy-string argument in both client and server source trees, reject blank requests, and give useful errors when no world source, Exocortex daemon, CLI executable, or custom tool module is available.
- [x] 16. Compile the complete client tree and affected server sources, rebuild the Allcraft JAR, and add focused Java tests for command parsing, exact stdin preservation, ProcessBuilder arguments, asynchronous completion, and failure reporting.
- [x] 17. Test the complete path through Minecraft IPC: create/join a world, issue `/allcraft ai <request with spaces>`, verify the game remains responsive, inspect the returned conversation ID, confirm it appears under `allcraft/logs` with a generated title, and verify its first turn can call both custom tools against that world's source.
- [x] 18. Document the temporary machine-specific CLI path, command behavior, attached tools, Exocortex folder, and the boundary between this placeholder and the later worktree/integration pipeline.
- [x] 19. Run the existing JVM and Minecraft smoke suites, commit the Allcraft and Exocortex changes in their respective repositories, and push both repositories.
