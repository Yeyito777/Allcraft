# Allcraft Patch Tests

Open a world, then run:

```text
/allcraft test <test-name>
```

The networking tests stream, cache, schedule, activate, and acknowledge five distinct compiled patch JARs:

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

Runtime compilation is queued in the background. Chat reports its duration and the client/server compiler-cache result. The test prints `PASS` only after the server and every connected client finish code/resource activation and acknowledgement.

Runtime tests compile from and update the active world's `source/` tree. Server results are written under the world's `patches/test-results/`; client results are written under `patches/<serverId>/<worldId>/test-results/`. Result records include redefine, resource-reload, total runtime, loading-screen, and GC measurements.
