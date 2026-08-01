# Allcraft JVM

## Runtime

Allcraft bundles a patched JetBrains Runtime SDK for each supported platform:

- `jvm/linux-x64/`
- `jvm/windows-x64/`

Both contain the runtime, Java compiler, development tools, `jmods`, and Java-library source. Allcraft always launches and compiles world patches with its bundled SDK.

Expected identity:

```text
openjdk version "25.0.3-internal"
OpenJDK Runtime Environment Allcraft-JBR-25.0.3+9-508.16.1
OpenJDK 64-Bit Server VM Allcraft-JBR-25.0.3+9-508.16.1
```

## Source provenance

`jvm/SOURCE.json` pins the complete native JBR source:

- Repository: `https://github.com/JetBrains/JetBrainsRuntime.git`
- Tag: `jbr-release-25.0.3b508.16`
- Commit: `c624f1bd958763cf442320ee570b5ad468b226bb`
- Upstream SDK: `JBRSDK-25.0.3+9-508.16`

The source is checked out as the `jvm/source` Git submodule. All Allcraft changes are maintained as ordered patches under `jvm/patches/`; build scripts reset the submodule to the pinned commit before applying them.

## Allcraft HotSpot changes

`0001-allcraft-dcevm-stability-and-hybrid-redefine.patch`:

1. Makes C2 reject stale/obsolete method metadata conservatively instead of terminating the VM during enhanced redefinition.
2. Uses standard HotSpot redefinition for schema-compatible changes and retries with DCEVM only for structural evolution.
3. Enables selective code-cache invalidation by default.
4. Allows JFR to remain active during structural evolution instead of silently disabling enhanced redefinition.
5. Preserves deleted old-generation method targets while live lambdas, method handles, or already-linked bytecode still reference them.

The launcher enables `AllowEnhancedClassRedefinition`. Normal tiered compilation and C2 remain enabled globally (`TieredCompilation=true`, `TieredStopAtLevel=4`); there are no method-specific compiler exclusions.

## Building

Linux prerequisites are the normal OpenJDK native build dependencies. Then run:

```bash
scripts/linux/build-jvm.sh            # output: build/jvm/linux-x64
scripts/linux/build-jvm.sh --install  # replace bundled jvm/linux-x64
```

On Windows, install Visual Studio 2022 Build Tools with the C++ workload and Cygwin with `autoconf`, `make`, `zip`, `unzip`, `git`, `python3`, and `procps-ng`. Launch Cygwin after `VsDevCmd.bat -arch=amd64`, then run:

```bash
scripts/windows/build-jvm.sh
scripts/windows/build-jvm.sh --install
```

A platform build is required because HotSpot produces native `libjvm.so` on Linux and `jvm.dll` on Windows.

## Regression tests

Linux:

```bash
tests/jvm/run.sh jvm/linux-x64
tests/jvm/minecraft-smoke.sh jvm/linux-x64
```

Windows PowerShell:

```powershell
tests\jvm\run.ps1 -Jdk jvm\windows-x64
```

The JVM suite checks runtime configuration, C2 body redefinition, repeated single- and multi-class structural evolution, live-instance migration, active old frames, live lambdas whose target methods are removed, class addition/retirement, and structural evolution while JFR records both in-process and by dynamic attach. The Minecraft smoke test creates and joins a world, applies every current runtime patch test, records JFR during evolution, leaves and rejoins the world, and verifies revision restoration.

## Validated builds

Validation date: 2026-08-01.

| Platform | JVM regression suite | Minecraft/runtime smoke |
| --- | --- | --- |
| Linux x86-64 | Pass | Pass |
| Windows x86-64 | Pass | Deferred until the Windows launcher (main TODO item 5) |

Loaded classes cannot be individually unloaded by any JVM. Logical class removal uses a tombstone definition; physical unloading occurs when its defining classloader becomes unreachable.
