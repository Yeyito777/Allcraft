# Runtime Gameplay Generality TODO

- [x] Add proper transactional entity-renderer and block-entity-renderer publication, including staged construction, live dispatcher updates, rollback, and world-switch restoration.
- [x] Implement generic transactional dynamic-registry evolution with stable holders/IDs, client synchronization, registry-layer publication, dependent-state refresh, rollback, and world replay.
- [x] Implement a transactional dynamic keybinding lifecycle with registration, removal, persistence, controls UI integration, conflict handling, dispatch, rollback, and world switching.
- [x] Finish the real `new-mob`, `new-music-disc`, and `new-keybind` `/allcraft test` fixtures and their automated/manual verification paths.
- [x] Implement the `lapis-crafting-table` fixture with a new block, item, block entity, menu, client screen, custom recipe type/serializer, assets, synchronized activation, rollback, and replay tests.
