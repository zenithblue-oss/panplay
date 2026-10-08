#!/bin/sh
# Build wowbox64.dll (Box64 as the Wine WoW64 32-bit emulator, PE/ARM64) from a pinned upstream box64 commit with
# pinned llvm-mingw. Needs only cmake, ninja and python3. Output: app/src/main/assets/deps/wow64/wowbox64.dll
# (bundled into the APK; Containers copies it to system32). Source: https://github.com/ptitSeb/box64 (MIT).
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/components.env"

WORK=${WORK:-/var/tmp/panvk/components-build}
OUT=${OUT:-$HERE/../app/src/main/assets/deps/wow64}
mkdir -p "$WORK" "$OUT"
. "$HERE/llvm-mingw.sh"

CMAKE=${CMAKE:-$(command -v cmake || ls /opt/android-sdk/cmake/*/bin/cmake 2>/dev/null | tail -1)}
[ -x "$CMAKE" ] || { echo "cmake >= 3.14 required (set CMAKE=)" >&2; exit 1; }

SRC=$WORK/box64-src
if [ ! -d "$SRC/.git" ]; then
    git clone https://github.com/ptitSeb/box64.git "$SRC"
fi
git -C "$SRC" checkout -q "$BOX64_COMMIT"
test "$(git -C "$SRC" rev-parse HEAD)" = "$BOX64_COMMIT" || { echo "box64 commit mismatch" >&2; exit 1; }

# Same flags as box64's .github/workflows/release.yml WOW64 job; only the wowbox64 target is built (the ExternalProject
# configures the PE build with llvm-mingw's aarch64 compiler; the regular box64 target is disabled by NOBOX64).
B=$WORK/box64-build
rm -rf "$B"
"$CMAKE" -S "$SRC" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=aarch64-w64-mingw32-clang \
    -DWOW64=1 -DARM_DYNAREC=1 -DNOBOX64=1
"$CMAKE" --build "$B" --target wowbox64 -j"${JOBS:-8}"
P=$WORK/box64-pkg
rm -rf "$P"; mkdir -p "$P/system32"
cp "$B/wowbox64-prefix/src/wowbox64-build/wowbox64.dll" "$P/system32/wowbox64.dll"
cat > "$P/profile.json" <<EOF
{
  "type": "Box64",
  "versionName": "$BOX64_VERSION",
  "versionCode": 1,
  "description": "Box64 v${BOX64_VERSION%%-*} (master $BOX64_COMMIT) as the Wine WoW64 32-bit emulator (wowbox64.dll, PE/ARM64), built from source with llvm-mingw $LLVM_MINGW_VER (scripts/build-box64-wow64.sh). Optional per-game alternative to FEX.",
  "files": [
    { "source": "system32/wowbox64.dll", "target": "\${system32}/wowbox64.dll" }
  ]
}
EOF
wcp=${OUT_WCP:-/var/tmp/panvk/components}/box64-$BOX64_VERSION.wcp
tar -C "$P" --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf - . | zstd -19 -T0 -q -f -o "$wcp"
sha256sum "$wcp" | tee "$wcp.sha256"
