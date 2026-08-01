#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_dir="$repo_root/build"
jar_tool="$repo_root/jvm/linux-x64/bin/jar"
minecraft_jar="$build_dir/allcraft-26.2.jar"
agent_jar="$build_dir/allcraft-agent.jar"

temporary="$(mktemp -d "$build_dir/.allcraft-agent.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT

(
    cd "$temporary"
    "$jar_tool" --extract --file "$minecraft_jar" net/minecraft/allcraft/AllcraftAgent.class
    cat > MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Premain-Class: net.minecraft.allcraft.AllcraftAgent
Agent-Class: net.minecraft.allcraft.AllcraftAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true

EOF
    "$jar_tool" --create --file allcraft-agent.jar --manifest MANIFEST.MF net/minecraft/allcraft/AllcraftAgent.class
)

mv -f "$temporary/allcraft-agent.jar" "$agent_jar"
printf 'Built %s\n' "$agent_jar"
