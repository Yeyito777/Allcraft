# Allcraft Patch Tests

Open a world, then run:

```text
/allcraft test <test-name>
```

Each test streams, caches, schedules, activates, and acknowledges five distinct compiled patch JARs:

| Test | Checks |
| --- | --- |
| `basic` | Basic five-revision patch flow and chat feedback |
| `ordering` | Ordered activation of revisions 1 through 5 |
| `payload` | Chunk transport from 1 KiB through 1.8 MiB |
| `cache` | Per-server/per-world artifact and manifest caching |
| `timing` | Activation delays of 10, 20, 30, 40, and 50 ticks |

The test prints `PASS` only after every connected client acknowledges all five patches. Server results are written under the world's `patches/test-results/`; client results are written under `patches/<serverId>/<worldId>/test-results/`.
