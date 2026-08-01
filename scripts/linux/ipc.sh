#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
endpoint="$repo_root/build/run/allcraft/ipc.json"

if [[ ! -f "$endpoint" ]]; then
    printf 'Allcraft IPC endpoint is missing: %s\n' "$endpoint" >&2
    printf 'Launch Allcraft first.\n' >&2
    exit 1
fi

python3 - "$endpoint" "$@" <<'PY'
import json
import socket
import sys

endpoint_path = sys.argv[1]
args = sys.argv[2:]
if not args:
    raise SystemExit("usage: ipc.sh status|worlds|join|create|chat|command|quit|raw ...")

verb = args[0]
rest = args[1:]
if verb == "status":
    request = {"action": "status"}
elif verb == "worlds":
    request = {"action": "list-worlds"}
elif verb == "join" and rest:
    request = {"action": "join-world", "world": " ".join(rest)}
elif verb == "create" and rest:
    request = {"action": "create-world", "name": rest[0], "mode": rest[1] if len(rest) > 1 else "survival"}
    if len(rest) > 2:
        request["seed"] = int(rest[2])
elif verb == "chat" and rest:
    request = {"action": "chat", "text": " ".join(rest)}
elif verb == "command" and rest:
    request = {"action": "command", "text": " ".join(rest)}
elif verb == "quit":
    request = {"action": "quit-world"}
elif verb == "raw" and rest:
    request = json.loads(" ".join(rest))
else:
    raise SystemExit(f"invalid IPC command: {' '.join(args)}")

with open(endpoint_path, "r", encoding="utf-8") as handle:
    endpoint = json.load(handle)

payload = (json.dumps(request, separators=(",", ":")) + "\n").encode()
try:
    with socket.create_connection((endpoint["host"], endpoint["port"]), timeout=95) as connection:
        connection.sendall(payload)
        received = bytearray()
        while not received.endswith(b"\n"):
            chunk = connection.recv(65536)
            if not chunk:
                break
            received.extend(chunk)
except OSError as error:
    raise SystemExit(f"Cannot connect to Allcraft IPC process {endpoint.get('pid')}: {error}")

if not received:
    raise SystemExit("Allcraft IPC returned no response")
response = json.loads(received)
print(json.dumps(response, indent=2, sort_keys=True))
if not response.get("ok"):
    raise SystemExit(1)
PY
