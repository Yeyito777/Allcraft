#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jdk="$(realpath "${1:-$repo_root/jvm/linux-x64}")"
if [[ $# -gt 1 ]]; then
    runtime_tests=("${@:2}")
else
    runtime_tests=(double-jump flying-boats no-world-gen new-class)
fi
instance="allcraft-jvm-smoke-$$"
world="JVM Smoke $$"
endpoint="$repo_root/build/run/allcraft/ipc.json"
log="$repo_root/build/logs/client-runtime.log"
recording="$repo_root/build/jvm-tests/minecraft-smoke.jfr"

cleanup() {
    xenv stop "$instance" >/dev/null 2>&1 || true
}
trap cleanup EXIT
rm -f "$endpoint"
xenv start "$instance" >/dev/null
xenv run -e "$instance" env \
    ALLCRAFT_JAVA_HOME="$jdk" \
    "$repo_root/scripts/linux/launch.sh" >/dev/null

for _ in {1..300}; do
    [[ -f "$endpoint" ]] && "$repo_root/scripts/linux/ipc.sh" status >/dev/null 2>&1 && break
    sleep 0.1
done
"$repo_root/scripts/linux/ipc.sh" status >/dev/null
"$repo_root/scripts/linux/ipc.sh" create "$world" creative 424242 >/dev/null

for _ in {1..900}; do
    status="$($repo_root/scripts/linux/ipc.sh status 2>/dev/null || true)"
    grep -q '"inWorld": true' <<<"$status" && break
    sleep 0.1
done
status="$($repo_root/scripts/linux/ipc.sh status)"
grep -q '"inWorld": true' <<<"$status"
pid="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["pid"])' <<<"$status")"

rm -f "$recording"
"$jdk/bin/jcmd" "$pid" JFR.start name=AllcraftSmoke filename="$recording" settings=profile >/dev/null

for test_name in "${runtime_tests[@]}"; do
    first_line=$(( $(wc -l < "$log") + 1 ))
    "$repo_root/scripts/linux/ipc.sh" command "allcraft test $test_name" >/dev/null
    for _ in {1..1200}; do
        recent="$(tail -n +"$first_line" "$log")"
        if grep -Fq "[Allcraft] FAIL $test_name" <<<"$recent"; then
            printf '%s\n' "$recent" >&2
            exit 1
        fi
        grep -Fq "[Allcraft] PASS $test_name:" <<<"$recent" && break
        sleep 0.1
    done
    recent="$(tail -n +"$first_line" "$log")"
    grep -Fq "[Allcraft] PASS $test_name:" <<<"$recent"
    "$repo_root/scripts/linux/ipc.sh" status >/dev/null
done

"$jdk/bin/jcmd" "$pid" JFR.stop name=AllcraftSmoke >/dev/null
"$repo_root/scripts/linux/ipc.sh" status >/dev/null
[[ -s "$recording" ]]

"$repo_root/scripts/linux/ipc.sh" quit >/dev/null
for _ in {1..900}; do
    status="$($repo_root/scripts/linux/ipc.sh status 2>/dev/null || true)"
    grep -q '"inWorld": false' <<<"$status" && break
    sleep 0.1
done
first_line=$(( $(wc -l < "$log") + 1 ))
"$repo_root/scripts/linux/ipc.sh" join "$world" >/dev/null
for _ in {1..1200}; do
    status="$($repo_root/scripts/linux/ipc.sh status 2>/dev/null || true)"
    grep -q '"inWorld": true' <<<"$status" && break
    sleep 0.1
done
grep -q '"inWorld": true' <<<"$status"
recent="$(tail -n +"$first_line" "$log")"
grep -Fq 'Restored Allcraft world revision' <<<"$recent"
"$repo_root/scripts/linux/ipc.sh" status >/dev/null

"$repo_root/scripts/linux/ipc.sh" quit >/dev/null
echo "PASS minecraft-smoke"
