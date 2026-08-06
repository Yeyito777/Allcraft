#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/tests/ai-benchmark"
CLASSPATH="$ROOT/build/classes/client:$(cat "$ROOT/build/compile-classpath.txt")"

rm -rf "$OUT"
mkdir -p "$OUT"
"$ROOT/jvm/linux-x64/bin/javac" -encoding UTF-8 -g -parameters -proc:none -cp "$CLASSPATH" -d "$OUT" \
  "$ROOT/source/client/net/minecraft/allcraft/AllcraftSourceRepository.java" \
  "$ROOT/source/client/net/minecraft/allcraft/AllcraftAiJobs.java" \
  "$ROOT/source/client/net/minecraft/allcraft/AllcraftAiTestSuites.java" \
  "$ROOT/source/client/net/minecraft/allcraft/AllcraftPatchServer.java" \
  "$ROOT/tests/ai-benchmark/src/net/minecraft/allcraft/AllcraftAiBenchmarkRegression.java"
"$ROOT/jvm/linux-x64/bin/java" -ea -cp "$OUT:$CLASSPATH" net.minecraft.allcraft.AllcraftAiBenchmarkRegression
