#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
java_home="${ALLCRAFT_JAVA_HOME:-$repo_root/jvm/linux-x64}"
classes="$repo_root/build/registry-tests/classes"

rm -rf "$classes"
mkdir -p "$classes"

"$java_home/bin/javac" \
    -encoding UTF-8 \
    -proc:none \
    -cp "$repo_root/build/allcraft-26.2.jar:$(<"$repo_root/build/compile-classpath.txt")" \
    -d "$classes" \
    "$repo_root/source/client/net/minecraft/allcraft/AllcraftRegistries.java" \
    "$repo_root/source/client/net/minecraft/core/Holder.java" \
    "$repo_root/source/client/net/minecraft/core/IdMapper.java" \
    "$repo_root/source/client/net/minecraft/core/MappedRegistry.java" \
    "$repo_root/source/client/net/minecraft/core/Registry.java" \
    "$repo_root/tests/registries/src/allcraft/registrytest/RegistryEvolutionRegression.java"

"$java_home/bin/java" \
    -ea \
    -cp "$classes:$(<"$repo_root/build/runtime-classpath.txt")" \
    allcraft.registrytest.RegistryEvolutionRegression
