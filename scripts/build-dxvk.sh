#!/bin/sh
# Build DXVK from a pinned upstream tag + runtimes/dxvk/clear-before-external-rendering.patch with pinned
# llvm-mingw: ARM64EC -> system32 (loaded by x64 and ARM64EC apps under Proton arm64ec), i686 -> syswow64.
# Same layout as the WCP Hub dxvk-arm64ec-3.1.1 component. Output: $OUT/dxvk-<ver>.wcp (+ .sha256).
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/components.env"
RT=$HERE/../runtimes/dxvk

WORK=${WORK:-/var/tmp/panvk/components-build}
OUT=${OUT:-/var/tmp/panvk/components}
JOBS=${JOBS:-8}
mkdir -p "$WORK" "$OUT"
. "$HERE/llvm-mingw.sh"

SRC=$WORK/dxvk-src
if [ ! -d "$SRC/.git" ]; then
    git clone --depth 1 --branch "$DXVK_TAG" https://github.com/doitsujin/dxvk.git "$SRC"
fi
test "$(git -C "$SRC" rev-parse HEAD)" = "$DXVK_COMMIT" || { echo "DXVK commit mismatch" >&2; exit 1; }
git -C "$SRC" submodule update --init --recursive --depth 1
git -C "$SRC" checkout -- .
git -C "$SRC" apply "$RT/clear-before-external-rendering.patch"
git -C "$SRC" apply "$RT/panplay-stats.patch"

VSRC=$WORK/vkd3d-proton-src
if [ ! -d "$VSRC/.git" ]; then
    git clone https://github.com/HansKristian-Work/vkd3d-proton.git "$VSRC"
fi
git -C "$VSRC" checkout -q "$VKD3D_COMMIT"
git -C "$VSRC" submodule update --init --recursive

PKG=$WORK/dxvk-package
rm -rf "$PKG"
files=
for arch in arm64ec i686; do
    B=$WORK/dxvk-build-$arch
    rm -rf "$B"
    meson setup "$B" "$SRC" --cross-file "$RT/$arch.txt" --buildtype release -Db_ndebug=true
    ninja -C "$B" -j"$JOBS"
    case $arch in arm64ec) dest=system32;; i686) dest=syswow64;; esac
    mkdir -p "$PKG/$dest"
    for pair in d3d8/d3d8 d3d9/d3d9 d3d10/d3d10core d3d11/d3d11 dxgi/dxgi; do
        cp "$B/src/$pair.dll" "$PKG/$dest/"
        dll=${pair#*/}.dll
        files="$files    { \"source\": \"$dest/$dll\", \"target\": \"\${$dest}/$dll\" },
"
    done
done
for arch in arm64ec i686; do
    B=$WORK/vkd3d-build-$arch
    rm -rf "$B"
    meson setup "$B" "$VSRC" --cross-file "$HERE/../runtimes/vkd3d/$arch.txt" --buildtype release --strip \
        -Denable_tests=false -Denable_extras=false
    ninja -C "$B" -j"$JOBS"
    case $arch in arm64ec) dest=system32;; i686) dest=syswow64;; esac
    for pair in d3d12/d3d12 d3d12core/d3d12core; do
        dll=${pair#*/}.dll
        cp "$B/libs/$pair.dll" "$PKG/$dest/"
        files="$files    { \"source\": \"$dest/$dll\", \"target\": \"\${$dest}/$dll\" },
"
    done
done
files=$(printf '%s' "$files" | sed '$ s/,$//')

cat > "$PKG/profile.json" <<EOF
{
  "type": "DXVK",
  "versionName": "$DXVK_VERSION",
  "versionCode": 1,
  "description": "DXVK $DXVK_TAG ($DXVK_COMMIT) + clear-before-external-rendering fix + PanPlay DXVK_STATS_FILE recorder, built from source with llvm-mingw $LLVM_MINGW_VER (scripts/build-dxvk.sh). ARM64EC system32 + i686 syswow64. D3D12 is the separate VKD3D package.",
  "files": [
$files
  ]
}
EOF

# Split d3d12/d3d12core into their own VKD3D package (type VKD3D, versionName $VKD3D_VERSION).
V=$WORK/vkd3d-pkg
rm -rf "$V"; mkdir -p "$V/system32" "$V/syswow64"
for a in system32 syswow64; do mv "$PKG/$a"/d3d12.dll "$PKG/$a"/d3d12core.dll "$V/$a/"; done
python3 - "$PKG/profile.json" "$V/profile.json" "$VKD3D_VERSION" "$VKD3D_TAG" "$VKD3D_COMMIT" "$LLVM_MINGW_VER" <<'PY'
import json, sys
src, dst, ver, tag, commit, llvm = sys.argv[1:]
p = json.load(open(src))
f = p["files"]
p["files"] = [x for x in f if "d3d12" not in x["source"]]
json.dump(p, open(src, "w"), indent=2)
json.dump({"type": "VKD3D", "versionName": ver, "versionCode": 1,
           "description": f"vkd3d-proton {tag} ({commit}), d3d12.dll + d3d12core.dll, built from source with llvm-mingw {llvm} (scripts/build-dxvk.sh). ARM64EC system32 + i686 syswow64.",
           "files": [x for x in f if "d3d12" in x["source"]]}, open(dst, "w"), indent=2)
PY

for pair in "$PKG:dxvk-$DXVK_VERSION" "$V:vkd3d-$VKD3D_VERSION"; do
    wcp=$OUT/${pair#*:}.wcp
    tar -C "${pair%%:*}" --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf - . | zstd -19 -T0 -q -f -o "$wcp"
    sha256sum "$wcp" | tee "$wcp.sha256"
done
