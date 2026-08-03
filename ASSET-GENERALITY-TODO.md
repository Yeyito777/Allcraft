# Asset Generality TODO

- [x] **1. Atlas change detection.** Detect brand-new, deleted, resized, moved, and static/animated sprite changes instead of allowing a new sprite to miss the current fast path.
- [x] **2. Stable incremental atlases.** Add sprites without moving existing UV coordinates; preserve atlas padding and mipmaps, and grow inside a pre-reserved fixed UV extent. A physical texture resize cannot preserve Minecraft's baked normalized UVs, so reserve exhaustion is handled by the staged full-rebake path.
- [x] **3. Animated sprite hot-swapping.** Support adding, removing, and changing animations and `.png.mcmeta` without a global atlas reload.
- [x] **4. Atlas deletion and rollback.** Remove sprites safely, restore lower-priority overlays when revisions/worlds change, and prevent live models from retaining invalid sprite references.
- [x] **5. Font hot-loading.** Rebuild affected font providers and glyph atlases in the background, then publish them without a global resource reload.
- [x] **6. Shader and render-pipeline hot-loading.** Compile changed shaders in staging, report failures without damaging the active pipeline, and atomically swap successful replacements.
- [x] **7. Remaining asset consumers.** Add dependency-selected reload paths for atlas manifests, particle descriptions, GUI sprites/metadata, post-processing definitions, and other currently unclassified client assets.
- [x] **8. Live sound migration.** Make changed sounds affect already-playing sources safely through targeted restart while leaving unrelated OpenAL state intact.
- [x] **9. Atomic model activation.** Stage replacement meshes for visible sections and swap them together so large model changes do not appear progressively across chunks.
- [x] **10. Remove avoidable full-reload fallbacks.** Ensure every vanilla asset category has an explicit incremental, staged, selected-listener, or on-demand path; reserve the generic fallback for genuinely unknown resource roots.
- [x] **11. Asset generality test suite.** Test new/deleted/resized/animated sprites, new namespaces, fonts, shaders, particles, GUI assets, active sounds, massive model changes, revision rollback, world switching, reconnect restoration, and F3+T durability with no loading screen or permanent stale state.
