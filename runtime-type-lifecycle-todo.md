# Runtime Type and Lifecycle TODO

- [x] 1. Generate and validate a canonical cross-side contract for shared logical classes before artifacts can be staged.
- [x] 2. Require runtime registry mutations to originate from canonical shared logical code while still allowing side-only renderers, screens, keybindings, and other client integrations.
- [x] 3. Replace destructive class tombstoning with fail-safe logical retirement so live objects always retain executable class definitions.
- [x] 4. Move client runtime teardown behind client objects and the integrated server's complete shutdown.
- [x] 5. Rewrite registry-backed fixtures so particles, mobs, menus, blocks, block entities, and recipes use shared logical classes with side-only wrappers.
- [x] 6. Add regressions for divergent client/server logical classes, shared-contract tampering, and live objects surviving class retirement/world reset.
- [x] 7. Compile the complete client tree and modified server runtime sources, then run the code-generality, registry, JVM, and Minecraft fixture smoke tests.
- [x] 8. Mark this checklist complete, commit all changes, and push `main`.
