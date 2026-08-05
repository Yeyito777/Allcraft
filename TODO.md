# TODO

- [x] **1.** Get the Minecraft source for 26.2 with the mappings baked in—easily editable. Source must include code and assets so we can do textures.
- [x] **2.** Get it running under our custom JVM / what is right for this: DCEVM—Dynamic Code Evolution VM, but you mentioned JetBrains JVM later?
- [ ] **3.** Update our source directly so that:
  - [x] **3.1.** Every world in `saves/` gets a clone of the source in `saves/<world>/source` and a patch list in `saves/<world>/patches`.
  - [x] **3.2.** Server can stream patches down to users and synchronize to apply them on tick `<n>`.
  - [x] **3.3.** Server follows under the same architecture, so it applies its patches to the source in the save it is running on.
  - [x] **3.4.** Patches to code, textures, sound, etc. (any part of the game) land seamlessly with no loading screens.
  - [x] **3.5.** Modify source to unlock any frozen registries, remove `final` on changeable vars, and tear down restrictions.
  - [x] **3.6** Give the AI super-external tools like grep_texture, grep_sound, grep_craft, grep_uv...
  - [x] **3.6.** Test suite that tests 10 different patches from: adding new items, mobs, workstation blocks, blocks, movement options, keybinds, etc.
  - [ ] **3.7.** Wire up the AI so that it can do custom craftings on any workstation (Exocortex backend).
  - [ ] **3.8.** Wire up dynamic difficulty (zombie invasion, etc., where AI wakes up every `<n>` minutes and makes the world harder).
  - [ ] **3.9.** Wire up 10 AI tests in test suite 2.
  - [ ] **3.10.** Give the mod a name, aesthetics, and any special presentation.
  - [ ] **3.11.** Bake in better click utils, Sodium, AppleSkin, and other mods.
  - [ ] **3.12.** Make the "request table" where we can introduce requests to the AI for game modifications at the cost of a relevant item.
- [ ] **4.** Custom launcher for Linux (Prism fork).
- [ ] **5.** Custom launcher for Windows.

> **P.S.** No security architecture; we go for maximal capability.
