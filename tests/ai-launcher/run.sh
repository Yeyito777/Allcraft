#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVAC="$ROOT/jvm/linux-x64/bin/javac"
JAVA="$ROOT/jvm/linux-x64/bin/java"
OUT="$ROOT/build/tests/ai-launcher"
CLASSPATH="$ROOT/build/classes/client:$(cat "$ROOT/build/compile-classpath.txt")"

rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVAC" -encoding UTF-8 -g -parameters -proc:none -cp "$CLASSPATH" -d "$OUT" \
  "$ROOT/tests/ai-launcher/src/net/minecraft/allcraft/AllcraftAiLauncherRegression.java"

EXOCORTEX_PARENT_CONV_ID=parent-test "$JAVA" -ea -cp "$OUT:$CLASSPATH" \
  net.minecraft.allcraft.AllcraftAiLauncherRegression
