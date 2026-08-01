# Allcraft JBR TODO

Goal: ship a custom JBR/HotSpot build that supports repeated arbitrary class evolution under normal C2 optimization without targeted JVM workarounds, crashes, or long JIT recovery stalls.

Current state: the exact native JBR source is pinned under `jvm/source/`, the Allcraft patch is reproducible, and both bundled platform SDKs are custom builds.

- [x] **1. Add the exact JBR source.** Add the native JetBrains Runtime source matching `JBRSDK-25.0.3+9-508.16` and record its upstream tag and commit.
- [x] **2. Make JBR builds reproducible.** Add Linux and Windows scripts that build the complete SDK from that source and place outputs under `jvm/linux-x64/` and `jvm/windows-x64/`.
- [x] **3. Add JVM regression reproducers.** Reproduce the C2 `old method not detected` crash, the post-redefinition JFR crash, repeated structural evolution, and repeated redefine/reset without requiring Minecraft UI interaction.
- [x] **4. Fix C2/redefinition coordination at the root.** Correct stale compile-task, `ciEnv` state, old-method, and compiler-metadata invalidation across enhanced redefinition; retain a conservative no-optimization fallback instead of aborting the JVM if stale metadata is observed.
- [x] **5. Fix JFR after enhanced redefinition.** Correct obsolete-method and line-table handling so JFR/profilers can start and sample safely after arbitrary redefinitions.
- [x] **6. Minimize redefinition stalls.** Preserve correct selective dependency deoptimization, avoid global code-cache flushing, and use the standard HotSpot redefine path automatically when a patch is schema-compatible while retaining DCEVM for structural changes.
- [x] **7. Stress maximum class evolution.** Test repeated addition, modification, and removal of fields, methods, constructors, interfaces, and classes under C2 and G1, including live instances and active stack frames.
- [x] **8. Validate the custom Linux and Windows SDKs.** Run the JVM regression suite on both SDKs and the complete Minecraft smoke suite on the implemented Linux launcher, including world create/join, all runtime patch tests, world switching, revision restoration, and JFR during structural evolution. Windows Minecraft validation remains part of the Windows launcher in main TODO item 5.
- [x] **9. Remove temporary workarounds.** Remove the `Entity.move` C2 compiler directive and any launcher tuning made unnecessary by the custom JVM; verify normal C2 remains enabled globally.
- [x] **10. Document and publish the custom JVM.** Record source provenance, patches, build commands, test results, and bundled runtime identity; commit and push the source/build changes and both validated SDKs.
