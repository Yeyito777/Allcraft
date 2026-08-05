#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTANCE="allcraft-ai-e2e-$$"
WORLD="AI Pipeline E2E $$"
ENDPOINT="$ROOT/build/run/allcraft/ipc.json"
LOG="$ROOT/build/logs/client-runtime.log"
SOURCE="$ROOT/build/run/saves/$WORLD/source"
JOBS="$ROOT/build/run/saves/$WORLD/patches/ai/jobs"
KEY="allcraft.ai_pipeline_e2e_$$"
VALUE="AI pipeline E2E $$"
CANCEL_KEY="allcraft.ai_pipeline_cancel_$$"
INTERRUPT_KEY="allcraft.ai_pipeline_interrupt_$$"

cleanup() {
  xenv stop "$INSTANCE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

rm -f "$ENDPOINT"
xenv start "$INSTANCE" >/dev/null
xenv run -e "$INSTANCE" env ALLCRAFT_JAVA_HOME="$ROOT/jvm/linux-x64" "$ROOT/scripts/linux/launch.sh" >/dev/null

for _ in {1..600}; do
  [[ -f "$ENDPOINT" ]] && "$ROOT/scripts/linux/ipc.sh" status >/dev/null 2>&1 && break
  sleep 0.1
done
"$ROOT/scripts/linux/ipc.sh" status >/dev/null
"$ROOT/scripts/linux/ipc.sh" create "$WORLD" creative 8675309 >/dev/null
for _ in {1..6000}; do
  status="$("$ROOT/scripts/linux/ipc.sh" status 2>/dev/null || true)"
  grep -q '"inWorld": true' <<<"$status" && break
  sleep 0.1
done
grep -q '"inWorld": true' <<<"$status"

first_line=$(( $(wc -l < "$LOG") + 1 ))
"$ROOT/scripts/linux/ipc.sh" command \
  "allcraft ai Add exact JSON property $KEY with exact value '$VALUE' to client/assets/minecraft/lang/en_us.json. Make no other source, asset, or gameplay changes." >/dev/null

job=""
for _ in {1..1200}; do
  job="$(find "$JOBS" -mindepth 2 -maxdepth 2 -name job.json -type f 2>/dev/null | head -1 || true)"
  [[ -n "$job" ]] && break
  sleep 0.1
done
[[ -n "$job" ]]

for _ in {1..7200}; do
  state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state"])' "$job")"
  [[ "$state" == finalized ]] && break
  [[ "$state" == cancelled ]] && exit 1
  sleep 0.25
done
[[ "$state" == finalized ]]
python3 - "$job" <<'PY'
import json,sys
j=json.load(open(sys.argv[1]))
assert j["resultRevision"] == 1, j
assert j["conversationId"], j
PY
grep -Fq "\"$KEY\": \"$VALUE\"" "$SOURCE/client/assets/minecraft/lang/en_us.json"
[[ "$(git -C "$SOURCE" status --porcelain)" == "" ]]
for _ in {1..300}; do
  [[ "$(git -C "$SOURCE" worktree list --porcelain | grep -c '^worktree ')" == 1 ]] && break
  sleep 0.1
done
[[ "$(git -C "$SOURCE" worktree list --porcelain | grep -c '^worktree ')" == 1 ]]
recent="$(tail -n +"$first_line" "$LOG")"
grep -Fq "Prepared general Allcraft revision 1" <<<"$recent"
grep -Fq "PASS ai-" <<<"$recent"

# Stopping the integrated server during a published revision must roll it back,
# retain the private candidate, and publish it normally after reopening the world.
"$ROOT/scripts/linux/ipc.sh" command \
  "allcraft ai Add exact JSON property $INTERRUPT_KEY with exact value 'restart retained candidate' to client/assets/minecraft/lang/en_us.json and add server/data/allcraft/ai-pipeline-interrupt.txt containing the same text. Make no other changes." >/dev/null
interrupt_job=""
for _ in {1..1200}; do
  interrupt_job="$(python3 - "$JOBS" "$INTERRUPT_KEY" <<'PY'
import glob,json,sys
for path in glob.glob(sys.argv[1]+'/*/job.json'):
    if sys.argv[2] in json.load(open(path))["request"]:
        print(path); break
PY
)"
  [[ -n "$interrupt_job" ]] && break
  sleep 0.1
done
[[ -n "$interrupt_job" ]]
for _ in {1..12000}; do
  interrupt_state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state"])' "$interrupt_job")"
  [[ "$interrupt_state" == activating ]] && break
  [[ "$interrupt_state" == finalized || "$interrupt_state" == cancelled ]] && exit 1
  sleep 0.01
done
[[ "$interrupt_state" == activating ]]
"$ROOT/scripts/linux/ipc.sh" quit >/dev/null
for _ in {1..2400}; do
  status="$("$ROOT/scripts/linux/ipc.sh" status 2>/dev/null || true)"
  grep -q '"inWorld": false' <<<"$status" && break
  sleep 0.1
done
grep -q '"inWorld": false' <<<"$status"
python3 - "$ROOT/build/run/saves/$WORLD/patches/manifest.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))["currentRevision"] == 1
PY
interrupt_state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state"])' "$interrupt_job")"
[[ "$interrupt_state" == awaiting-integration ]]

"$ROOT/scripts/linux/ipc.sh" join "$WORLD" >/dev/null
for _ in {1..6000}; do
  status="$("$ROOT/scripts/linux/ipc.sh" status 2>/dev/null || true)"
  grep -q '"inWorld": true' <<<"$status" && break
  sleep 0.1
done
grep -q '"inWorld": true' <<<"$status"
for _ in {1..7200}; do
  interrupt_state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state"])' "$interrupt_job")"
  [[ "$interrupt_state" == finalized ]] && break
  sleep 0.25
done
[[ "$interrupt_state" == finalized ]]
grep -Fq "\"$INTERRUPT_KEY\": \"restart retained candidate\"" "$SOURCE/client/assets/minecraft/lang/en_us.json"
python3 - "$ROOT/build/run/saves/$WORLD/patches/manifest.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))["currentRevision"] == 2
PY

# A cancellation after private build/staging must never advance source or manifest.
"$ROOT/scripts/linux/ipc.sh" command \
  "allcraft ai Add exact JSON property $CANCEL_KEY with exact value 'must never publish' to client/assets/minecraft/lang/en_us.json. Make no other changes." >/dev/null
cancel_job=""
for _ in {1..1200}; do
  cancel_job="$(python3 - "$JOBS" "$CANCEL_KEY" <<'PY'
import glob,json,sys
for path in glob.glob(sys.argv[1]+'/*/job.json'):
    if sys.argv[2] in json.load(open(path))["request"]:
        print(path); break
PY
)"
  [[ -n "$cancel_job" ]] && break
  sleep 0.1
done
[[ -n "$cancel_job" ]]
for _ in {1..7200}; do
  cancel_state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state"])' "$cancel_job")"
  if [[ "$cancel_state" == staging || "$cancel_state" == activating ]]; then break; fi
  [[ "$cancel_state" == finalized ]] && exit 1
  sleep 0.05
done
[[ "$cancel_state" == staging || "$cancel_state" == activating ]]
cancel_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$cancel_job")"
"$ROOT/scripts/linux/ipc.sh" command "allcraft ai cancel $cancel_id" >/dev/null
for _ in {1..600}; do
  cancel_state="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["state"])' "$cancel_job")"
  [[ "$cancel_state" == cancelled ]] && break
  sleep 0.1
done
[[ "$cancel_state" == cancelled ]]
sleep 2
python3 - "$ROOT/build/run/saves/$WORLD/patches/manifest.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))["currentRevision"] == 2
PY
! grep -Fq "$CANCEL_KEY" "$SOURCE/client/assets/minecraft/lang/en_us.json"

"$ROOT/scripts/linux/ipc.sh" quit >/dev/null
for _ in {1..1800}; do
  status="$("$ROOT/scripts/linux/ipc.sh" status 2>/dev/null || true)"
  grep -q '"inWorld": false' <<<"$status" && break
  sleep 0.1
done
first_line=$(( $(wc -l < "$LOG") + 1 ))
"$ROOT/scripts/linux/ipc.sh" join "$WORLD" >/dev/null
for _ in {1..6000}; do
  status="$("$ROOT/scripts/linux/ipc.sh" status 2>/dev/null || true)"
  grep -q '"inWorld": true' <<<"$status" && break
  sleep 0.1
done
grep -q '"inWorld": true' <<<"$status"
awk -v start="$first_line" 'NR >= start && index($0, "Restored Allcraft world revision 2") { found=1 } END { exit !found }' "$LOG"
"$ROOT/scripts/linux/ipc.sh" quit >/dev/null

echo "PASS ai-pipeline-e2e"
