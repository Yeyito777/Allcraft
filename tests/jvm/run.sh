#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jdk="${1:-$repo_root/jvm/linux-x64}"
scenario="${2:-all}"
output="$repo_root/build/jvm-tests"

"$repo_root/tests/jvm/build.sh" "$jdk"

run_one() {
    local name=$1
    timeout 90s "$jdk/bin/java" \
        -Xms128m -Xmx1g \
        -javaagent:"$output/agent.jar" \
        -XX:+AllowEnhancedClassRedefinition \
        -XX:CompileThreshold=100 \
        -Dallcraft.jvmtest.versions="$output/versions" \
        -Dallcraft.jvmtest.jfr="$output/$name.jfr" \
        -cp "$output/classes" \
        allcraft.jvmtest.JvmRegressionMain "$name"
}

run_jfr_attach() {
    local log="$output/jfr-attach.log"
    rm -f "$log" "$output/jfr-attach.jfr"
    timeout 90s "$jdk/bin/java" \
        -Xms128m -Xmx1g \
        -javaagent:"$output/agent.jar" \
        -XX:+AllowEnhancedClassRedefinition \
        -XX:CompileThreshold=100 \
        -Dallcraft.jvmtest.versions="$output/versions" \
        -cp "$output/classes" \
        allcraft.jvmtest.JvmRegressionMain jfr-wait >"$log" 2>&1 &
    local wrapper_pid=$!
    for _ in {1..300}; do
        grep -q '^READY pid=' "$log" && break
        kill -0 "$wrapper_pid" 2>/dev/null || { cat "$log"; wait "$wrapper_pid"; }
        sleep 0.1
    done
    grep -q '^READY pid=' "$log" || { cat "$log"; kill "$wrapper_pid" 2>/dev/null || true; return 1; }
    local pid
    pid="$(sed -n 's/^READY pid=//p' "$log" | tail -1)"
    "$jdk/bin/jcmd" "$pid" JFR.start duration=10s filename="$output/jfr-attach.jfr" settings=profile
    wait "$wrapper_pid"
    cat "$log"
    [[ -s "$output/jfr-attach.jfr" ]]
}

if [[ "$scenario" == all ]]; then
    for name in configuration c2-old-method structural multi-structural repeat jfr jfr-structural; do
        run_one "$name"
    done
    run_jfr_attach
elif [[ "$scenario" == jfr-attach ]]; then
    run_jfr_attach
else
    run_one "$scenario"
fi
