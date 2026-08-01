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
| `double-jump` | Redefines `LocalPlayer`, adds fields, and adds a helper class | Jump, release Space in midair, then press Space again |
| `flying-boats` | Redefines client boat-control behavior | Ride a boat; hold Space to rise and Shift to descend |
| `no-world-gen` | Redefines server chunk-generation classes | Travel into never-generated chunks; they should be empty |
| `new-class` | Adds and invokes new client and server classes | No manual step; both entrypoints must run for `PASS` |

The test prints `PASS` only after every connected client acknowledges every patch. Runtime tests compile from and update the active world's `source/` tree. Server results are written under the world's `patches/test-results/`; client results are written under `patches/<serverId>/<worldId>/test-results/`.
