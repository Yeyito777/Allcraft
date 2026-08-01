#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jdk="${1:-$repo_root/jvm/linux-x64}"
output="$repo_root/build/jvm-tests"
rm -rf "$output"
mkdir -p "$output/classes" "$output/versions"

"$jdk/bin/javac" -g -d "$output/classes" \
    "$repo_root/tests/jvm/src/allcraft/jvmtest/Agent.java" \
    "$repo_root/tests/jvm/src/allcraft/jvmtest/BaseEntity.java" \
    "$repo_root/tests/jvm/src/allcraft/jvmtest/EvolutionContract.java"

for version in v1 v2-body v2-structural v3-structural; do
    mkdir -p "$output/versions/$version"
    mapfile -t sources < <(find "$repo_root/tests/jvm/versions/$version" -name '*.java' -print | sort)
    if [[ ${#sources[@]} -gt 0 ]]; then
        "$jdk/bin/javac" -g -cp "$output/classes" -d "$output/versions/$version" "${sources[@]}"
    fi
done

for version in v2-structural v3-structural; do
    "$jdk/bin/jar" --create --file "$output/versions/$version.jar" -C "$output/versions/$version" .
done

cp -a "$output/versions/v1/." "$output/classes/"
"$jdk/bin/javac" -g -cp "$output/classes" -d "$output/classes" \
    "$repo_root/tests/jvm/src/allcraft/jvmtest/JvmRegressionMain.java"

cat >"$output/agent.mf" <<'EOF'
Premain-Class: allcraft.jvmtest.Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF
"$jdk/bin/jar" --create --file "$output/agent.jar" --manifest "$output/agent.mf" -C "$output/classes" allcraft/jvmtest/Agent.class
printf 'Built JVM regression harness at %s\n' "$output"
