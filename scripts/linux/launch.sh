#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_dir="$repo_root/build"
java_home="${ALLCRAFT_JAVA_HOME:-$repo_root/jvm/linux-x64}"
java="$java_home/bin/java"
classpath_file="$build_dir/runtime-classpath.txt"
jar="$build_dir/allcraft-26.2.jar"
agent="$build_dir/allcraft-agent.jar"
assets="$build_dir/assets"
game_dir="$build_dir/run"
log="$build_dir/logs/client-runtime.log"

for required in "$java" "$classpath_file" "$jar" "$assets/indexes/32.json"; do
    if [[ ! -e "$required" ]]; then
        printf 'Missing required Allcraft build file: %s\n' "$required" >&2
        exit 1
    fi
done

if [[ ! -e "$agent" || "$jar" -nt "$agent" ]]; then
    "$repo_root/scripts/linux/build-agent.sh"
fi

mkdir -p \
    "$game_dir" \
    "$build_dir/logs" \
    "$build_dir/natives/java" \
    "$build_dir/natives/jna" \
    "$build_dir/natives/lwjgl" \
    "$build_dir/natives/netty"

cd "$game_dir"

exec "$java" \
    -Xms512M \
    -Xmx4G \
    -javaagent:"$agent" \
    -XX:+AllowEnhancedClassRedefinition \
    --sun-misc-unsafe-memory-access=allow \
    --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$build_dir/natives/java" \
    -Djna.tmpdir="$build_dir/natives/jna" \
    -Dorg.lwjgl.system.SharedLibraryExtractPath="$build_dir/natives/lwjgl" \
    -Dio.netty.native.workdir="$build_dir/natives/netty" \
    -Dallcraft.sourceRoot="$repo_root/source" \
    -Dallcraft.gameDir="$game_dir" \
    -Dallcraft.javac="$java_home/bin/javac" \
    -Dallcraft.baseVersion=26.2-allcraft \
    -Dminecraft.launcher.brand=Allcraft \
    -Dminecraft.launcher.version=0.1 \
    -Dlog4j.configurationFile="$build_dir/logging/client-1.21.2.xml" \
    -cp "$(<"$classpath_file")" \
    net.minecraft.client.main.Main \
    --username "${ALLCRAFT_USERNAME:-Allcraft}" \
    --version 26.2-allcraft \
    --gameDir "$game_dir" \
    --assetsDir "$assets" \
    --assetIndex 32 \
    --uuid 00000000-0000-0000-0000-000000000000 \
    --accessToken 0 \
    --clientId '' \
    --xuid '' \
    --versionType allcraft \
    >>"$log" 2>&1
