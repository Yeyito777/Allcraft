#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
java_home="${ALLCRAFT_JAVA_HOME:-$repo_root/jvm/linux-x64}"
classes="$repo_root/build/code-generality-tests/classes"
support="$repo_root/build/code-generality-tests/support"
work="$repo_root/build/code-generality-tests/work"

"$repo_root/scripts/linux/build-agent.sh" >/dev/null
rm -rf "$classes" "$support"
mkdir -p "$classes" "$support" "$work"

"$java_home/bin/javac" \
    -encoding UTF-8 \
    -proc:none \
    -cp "$repo_root/build/allcraft-26.2.jar:$(<"$repo_root/build/compile-classpath.txt")" \
    -d "$support" \
    "$repo_root/source/client/net/minecraft/allcraft/AllcraftRevisionBuilder.java" \
    "$repo_root/source/client/net/minecraft/allcraft/AllcraftRuntime.java"

"$java_home/bin/javac" \
    -encoding UTF-8 \
    -proc:none \
    -cp "$support:$repo_root/build/allcraft-26.2.jar:$(<"$repo_root/build/compile-classpath.txt")" \
    -d "$classes" \
    "$repo_root/tests/code-generality/src/allcraft/generalitytest/CodeGeneralityRegression.java"

"$java_home/bin/java" \
    -Xms128m \
    -Xmx2g \
    -javaagent:"$repo_root/build/allcraft-agent.jar" \
    -XX:+AllowEnhancedClassRedefinition \
    -XX:StartFlightRecording=filename="$repo_root/build/code-generality-tests/regression.jfr",settings=profile,dumponexit=true \
    -cp "$classes:$support:$(<"$repo_root/build/runtime-classpath.txt")" \
    allcraft.generalitytest.CodeGeneralityRegression \
    "$java_home/bin/javac" \
    "$repo_root/build/allcraft-26.2.jar" \
    "$work"
