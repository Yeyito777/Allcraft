# Allcraft Patch Tests

Open a world, then run:

```text
/allcraft test <test-name>
```

The networking tests edit an ordinary world-source fixture and submit five sequential revisions through the production differ/artifact pipeline. They stream, cache, schedule, activate, and acknowledge five distinct compiled patch JARs:

| Test | Checks |
| --- | --- |
| `basic` | Basic five-revision patch flow and chat feedback |
| `ordering` | Ordered activation of revisions 1 through 5 |
| `payload` | Chunk transport from 1 KiB through 1.8 MiB |
| `cache` | Per-server/per-world artifact and manifest caching |
| `timing` | Activation delays of 10, 20, 30, 40, and 50 ticks |

The runtime tests each compile and activate one real source/bytecode patch:

| Test | Checks | Manual verification |
| --- | --- | --- |
| `double-jump` | Redefines `LocalPlayer` and adds helper/state classes | Jump, release Space in midair, then press Space again |
| `flying-boats` | Redefines client boat-control behavior | Ride a boat; hold Space to rise and Shift to descend |
| `no-world-gen` | Redefines server chunk-generation classes | Travel into never-generated chunks; they should be empty |
| `new-class` | Adds and invokes new client and server classes | No manual step; both entrypoints must run for `PASS` |

The resource tests edit the world's authoritative source, stream resource/data overlays, reload them without a loading screen, and verify the active bytes:

| Test | Checks | Manual verification |
| --- | --- | --- |
| `live-texture` | Replaces a loaded atlas texture | Exposed dirt becomes a magenta-and-black checkerboard |
| `live-model` | Re-bakes a loaded block model | Dirt renders with the diamond-block texture |
| `live-sound` | Replaces a loaded OGG sound | The automatic experience-orb preview plays the ominous-effect sound |
| `live-language` | Reloads the active language table | Dirt is named “Allcraft Live Dirt” |
| `live-recipe` | Reloads server data and synchronizes recipes | Craft one dirt by itself to receive one diamond |
| `live-resource-delete` | Deletes a base resource through an exact overlay tombstone | No manual step; absence is checked before `PASS` |

The asset-generality tests exercise staged asset consumers and atlas evolution. Run destructive atlas tests in separate fresh worlds when you want an isolated visual result; world revisions are intentionally cumulative.

| Test | Checks | Manual verification |
| --- | --- | --- |
| `asset-new-sprite` | Adds an `allcraft` namespace sprite and uses it from a block model | Dirt becomes orange/cyan |
| `asset-resized-sprite` | Moves a 16×16 sprite into a stable 32×32 allocation | Dirt becomes green/blue; neighboring sprites remain intact |
| `asset-animated-sprite` | Hot-loads animation frames and `.png.mcmeta` | Dirt alternates between the two checker patterns |
| `asset-atlas-delete` | Deletes an active atlas sprite while retaining safe old allocations | Dirt resolves to the missing sprite without corrupting adjacent sprites |
| `asset-font` | Loads a new namespace/font definition and stages new glyph atlases | Test reaches `PASS` without closing the active font textures |
| `asset-shader` | Reprocesses an included GLSL file and compiles a candidate pipeline cache one pipeline per frame | Rendering stays live until the candidate cache commits |
| `asset-particle` | Adds a particle sprite whose atlas ID differs from its resource path and reloads particle descriptions | Run `/particle minecraft:flame ~ ~1 ~ 0.5 0.5 0.5 0 100`; particles are magenta/yellow |
| `asset-gui` | Replaces a GUI-atlas sprite | The crosshair changes to a checker pattern |
| `asset-live-sound` | Invalidates one decoded buffer and migrates matching active channels | First run `/playsound minecraft:music_disc.cat master @s`; only that source restarts with the replacement audio |
| `asset-mass-model` | Builds replacement meshes off-thread and publishes the visible cohort together | A large stone area changes to emerald at one atomic commit |
| `asset-atlas-manifest` | Re-resolves an `atlases/blocks.json` source and detects its new sprite ID | Dirt uses the orange/blue manifest sprite |

Durability/rollback sequence:

1. Run several asset tests and wait for `PASS`.
2. Press **F3+T**; all committed overlays must remain active.
3. Quit and rejoin the same world; its manifest revision must be restored.
4. Join or create a revision-zero world; prior textures, models, fonts, shaders, and sounds must roll back to vanilla.
5. Check `build/run/logs/latest.log`: expected revisions have `PASS`, no change invokes a loading screen, and known asset roots report `fullFallback=false`.

Runtime compilation is queued in the background. Chat reports its duration and the client/server compiler-cache result. The test prints `PASS` only after the server and every connected client finish code/resource activation and acknowledgement.

Runtime tests compile from and update the active world's `source/` tree. Server results are written under the world's `patches/test-results/`; client results are written under `patches/<serverId>/<worldId>/test-results/`. Result records include redefine, resource-reload, total runtime, loading-screen, and GC measurements.

For the non-rendering production-pipeline/JVM regression, run:

```bash
tests/code-generality/run.sh
```
