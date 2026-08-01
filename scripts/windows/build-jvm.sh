#!/usr/bin/env bash
set -euo pipefail

# Run from a Cygwin shell launched after vcvarsall.bat amd64.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_root="$repo_root/jvm/source"
boot_jdk="$repo_root/jvm/windows-x64"
output="$repo_root/build/jvm/windows-x64"
install=false

if [[ "${1:-}" == "--install" ]]; then
    install=true
elif [[ $# -ne 0 ]]; then
    printf 'usage: %s [--install]\n' "$0" >&2
    exit 2
fi

expected_commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$repo_root/jvm/SOURCE.json")"
[[ -d "$source_root/.git" || -f "$source_root/.git" ]] || git -C "$repo_root" submodule update --init --depth 1 jvm/source
git -C "$source_root" reset --hard "$expected_commit"
git -C "$source_root" clean -fd

shopt -s nullglob
for patch in "$repo_root"/jvm/patches/*.patch; do
    git -C "$source_root" apply --check "$patch"
    git -C "$source_root" apply "$patch"
done

source_epoch="$(git -C "$source_root" show -s --format=%ct "$expected_commit")"
cd "$source_root"
bash configure \
    --with-boot-jdk="$boot_jdk" \
    --with-toolchain-version="${TOOLCHAIN_VERSION:-2022}" \
    --with-debug-level=release \
    --with-native-debug-symbols=none \
    --disable-full-docs \
    --disable-warnings-as-errors \
    --disable-absolute-paths-in-output \
    --disable-jaws-client \
    --with-build-user=allcraft \
    --with-source-date="$source_epoch" \
    --with-vendor-name='Allcraft / JetBrains s.r.o.' \
    --with-vendor-url='https://github.com/Yeyito777/Allcraft' \
    --with-vendor-bug-url='https://github.com/Yeyito777/Allcraft/issues' \
    --with-vendor-vm-bug-url='https://github.com/Yeyito777/Allcraft/issues' \
    --with-vendor-version-string='Allcraft-JBR-25.0.3+9-508.16.1' \
    --with-version-build=9 \
    --with-version-opt=allcraft.1
make images CONF=windows-x86_64-server-release

image="$source_root/build/windows-x86_64-server-release/images/jdk"
modules="$("$image/bin/java.exe" --list-modules | sed 's/@.*//' | paste -sd, -)"
[[ -n "$modules" ]] || { echo "Built JDK reported no modules" >&2; exit 1; }
rm -rf "$output"
mkdir -p "$(dirname "$output")"
image_windows="$(cygpath -w "$image")"
output_windows="$(cygpath -w "$output")"
"$image/bin/jlink.exe" \
    --module-path "$image_windows\\jmods" \
    --no-man-pages \
    --compress=zip-6 \
    --generate-cds-archive \
    --add-modules "$modules" \
    --output "$output_windows"
cp "$image/lib/src.zip" "$output/lib/"
cp -a "$image/jmods" "$output/"

"$output/bin/java.exe" -version
"$output/bin/javac.exe" -version

if $install; then
    rm -rf "$boot_jdk"
    cp -a "$output" "$boot_jdk"
    printf 'Installed custom Allcraft JBR SDK at %s\n' "$boot_jdk"
else
    printf 'Built custom Allcraft JBR SDK at %s\n' "$output"
fi
