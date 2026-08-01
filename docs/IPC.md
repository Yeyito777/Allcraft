# Allcraft Development IPC

The client exposes a loopback-only JSON-line IPC endpoint while it is running. The endpoint metadata is written to `build/run/allcraft/ipc.json`.

Use the Linux helper from the repository root:

```bash
scripts/linux/ipc.sh status
scripts/linux/ipc.sh worlds
scripts/linux/ipc.sh join "World Name"
scripts/linux/ipc.sh create "World Name" survival 12345
scripts/linux/ipc.sh quit
scripts/linux/ipc.sh chat "hello"
scripts/linux/ipc.sh command "allcraft test double-jump"
```

`create` accepts `survival` or `creative`; its seed is optional. Join/create/quit operations are submitted to Minecraft's client thread. Chat and commands use the active network connection.
