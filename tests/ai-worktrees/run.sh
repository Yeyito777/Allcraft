#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/tests/ai-worktrees"
CLASSPATH="$ROOT/build/classes/client:$(cat "$ROOT/build/compile-classpath.txt")"

rm -rf "$OUT"
mkdir -p "$OUT"
"$ROOT/jvm/linux-x64/bin/javac" -encoding UTF-8 -g -parameters -proc:none -cp "$CLASSPATH" -d "$OUT" \
  "$ROOT/source/client/net/minecraft/allcraft/AllcraftSourceRepository.java" \
  "$ROOT/tests/ai-worktrees/src/net/minecraft/allcraft/AllcraftAiWorktreeRegression.java"
"$ROOT/jvm/linux-x64/bin/java" -ea -cp "$OUT:$CLASSPATH" net.minecraft.allcraft.AllcraftAiWorktreeRegression
