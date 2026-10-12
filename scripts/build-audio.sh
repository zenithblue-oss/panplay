#!/bin/sh
# Build the game-audio components for Proton's aarch64-unix winepulse.so / winealsa.so (Android bionic, NDK):
#   PulseAudio-$PULSE_VERSION.wcp : pulseaudio daemon + libpulse + module-aaudio-sink (+ libsndfile, libltdl)
#   ALSA-$ALSA_VERSION.wcp        : alsa-lib + libasound_module_pcm_aaudio.so (runtimes/audio/pcm_aaudio.c)
# Pins in scripts/components.env (tarball sha256 / git commit checked). Output: $OUT/*.wcp (+ .sha256).
# Needs: NDK $AUDIO_NDK_VER, meson, ninja, make, python3, zstd. Licences: LGPL-2.1+ (pulseaudio client/daemon as
# configured here, alsa-lib, libsndfile, libltdl, both aaudio modules); see runtimes/audio/README.md.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$HERE/components.env"
RT=$HERE/../runtimes/audio

WORK=${WORK:-/var/tmp/panvk/audio-build}
OUT=${OUT:-/var/tmp/panvk/components}
JOBS=${JOBS:-8}
NDK=${NDK:-/opt/android-sdk/ndk/$AUDIO_NDK_VER}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
TRIPLE=aarch64-linux-android
API=$AUDIO_API
[ -x "$TC/bin/$TRIPLE$API-clang" ] || { echo "NDK $AUDIO_NDK_VER not found (set NDK=)" >&2; exit 1; }
SRC=$WORK/src
P=$WORK/prefix                     # build-time sysroot of the deps; never shipped as a path
mkdir -p "$SRC" "$OUT"
rm -rf "$P" "$WORK/build"; mkdir -p "$P" "$WORK/build"

export CC="$TC/bin/$TRIPLE$API-clang" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip"
export CFLAGS="-O2 -fPIC -I$P/include" LDFLAGS="-L$P/lib -Wl,-z,max-page-size=16384"
export PKG_CONFIG_LIBDIR="$P/lib/pkgconfig" PKG_CONFIG_PATH=

fetch() { # url file sha256
    [ -f "$SRC/$2" ] || { curl -fL -o "$SRC/$2.part" "$1" && mv "$SRC/$2.part" "$SRC/$2"; }
    echo "$3  $SRC/$2" | sha256sum -c -
}
autotools() { # srcdir builddir args...
    s=$1 b=$2; shift 2
    mkdir -p "$b"
    (cd "$b" && "$s/configure" --host=$TRIPLE --prefix="$P" --disable-static "$@" >configure.log && make -j"$JOBS" >make.log && make install >install.log)
}

# --- libltdl (libtool), needed by the pulseaudio daemon's module loader
fetch https://ftp.gnu.org/gnu/libtool/libtool-$LIBTOOL_VER.tar.xz libtool-$LIBTOOL_VER.tar.xz "$LIBTOOL_SHA256"
rm -rf "$SRC/libtool-$LIBTOOL_VER"; tar -C "$SRC" -xJf "$SRC/libtool-$LIBTOOL_VER.tar.xz"
autotools "$SRC/libtool-$LIBTOOL_VER/libltdl" "$WORK/build/libltdl" --enable-ltdl-install

# --- libsndfile, required by libpulse (no external codecs: pulse only needs WAV/raw for samples)
fetch https://github.com/libsndfile/libsndfile/releases/download/$SNDFILE_VER/libsndfile-$SNDFILE_VER.tar.xz libsndfile-$SNDFILE_VER.tar.xz "$SNDFILE_SHA256"
rm -rf "$SRC/libsndfile-$SNDFILE_VER"; tar -C "$SRC" -xJf "$SRC/libsndfile-$SNDFILE_VER.tar.xz"
autotools "$SRC/libsndfile-$SNDFILE_VER" "$WORK/build/libsndfile" --disable-external-libs --disable-mpeg \
    --disable-sqlite --disable-alsa --disable-full-suite --disable-octave --disable-test-coverage

# --- alsa-lib: only the PCM plugins plug -> ioplug needs (no dmix/dsnoop/shm: they need SysV IPC).
# seq + rawmidi stay in: winealsa.so links the snd_seq_* / snd_midi_event_* API (MIDI); without /dev/snd/seq it finds no ports.
fetch https://www.alsa-project.org/files/pub/lib/alsa-lib-$ALSA_LIB_VER.tar.bz2 alsa-lib-$ALSA_LIB_VER.tar.bz2 "$ALSA_LIB_SHA256"
rm -rf "$SRC/alsa-lib-$ALSA_LIB_VER"; tar -C "$SRC" -xjf "$SRC/alsa-lib-$ALSA_LIB_VER.tar.bz2"
autotools "$SRC/alsa-lib-$ALSA_LIB_VER" "$WORK/build/alsa-lib" --with-versioned=no --disable-python \
    --disable-ucm --disable-topology --disable-alisp --disable-hwdep \
    --disable-aload --with-pcm-plugins="copy linear route plug rate null hooks asym softvol extplug ioplug mmap_emul" \
    --with-ctl-plugins="ext"

# --- AAudio ioplug plugin
mkdir -p "$P/lib/alsa-lib"
$CC $CFLAGS -DPIC -shared -Wall -Wextra -Werror -o "$P/lib/alsa-lib/libasound_module_pcm_aaudio.so" "$RT/pcm_aaudio.c" \
    $LDFLAGS -lasound -laaudio -llog

# --- PulseAudio (git tag, commit pinned) + termux module-aaudio-sink
PS=$SRC/pulseaudio
[ -d "$PS/.git" ] || git clone https://gitlab.freedesktop.org/pulseaudio/pulseaudio.git "$PS"
git -C "$PS" checkout -q -f "$PULSE_COMMIT"; git -C "$PS" clean -qfdx
test "$(git -C "$PS" rev-parse HEAD)" = "$PULSE_COMMIT" || { echo "pulseaudio commit mismatch" >&2; exit 1; }
git -C "$PS" apply "$RT/pulseaudio-android.patch"
mkdir -p "$PS/src/modules/aaudio"; cp "$RT/module-aaudio-sink.c" "$PS/src/modules/aaudio/"
cat > "$WORK/build/android.cross" <<EOF
[binaries]
c = '$CC'
cpp = '$TC/bin/$TRIPLE$API-clang++'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
[built-in options]
c_args = ['-I$P/include']
c_link_args = ['-L$P/lib', '-Wl,-z,max-page-size=16384', '-Wl,--undefined-version']
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
EOF
dis="alsa asyncns avahi bluez5 bluez5-gstreamer consolekit dbus elogind fftw glib gsettings gstreamer gtk jack lirc
     openssl orc oss-output samplerate soxr speex systemd tcpwrap udev valgrind x11 webrtc-aec"
opts=; for o in $dis; do opts="$opts -D$o=disabled"; done
meson setup "$WORK/build/pulse" "$PS" --cross-file "$WORK/build/android.cross" --buildtype release --prefix "$P" \
    -Ddaemon=true -Dclient=true -Ddoxygen=false -Dman=false -Dtests=false -Ddatabase=simple -Dadrian-aec=true \
    -Dhal-compat=false -Dbluez5-native-headset=false -Dbluez5-ofono-headset=false -Dipv6=false $opts >"$WORK/build/pulse-setup.log"
ninja -C "$WORK/build/pulse" -j"$JOBS" >"$WORK/build/pulse-ninja.log"
ninja -C "$WORK/build/pulse" install >"$WORK/build/pulse-install.log"

# --- package. Files are named exactly as NEEDED by Proton's winepulse.so (libpulse.so) / winealsa.so (libasound.so).
pkg() { # dir type version description
    cat > "$1/profile.json" <<EOF
{
  "type": "$2",
  "versionName": "$3",
  "versionCode": 1,
  "description": "$4",
  "files": []
}
EOF
    find "$1" -type f \( -name '*.so*' -o -path '*/bin/*' \) -exec "$STRIP" --strip-unneeded {} +
    wcp=$OUT/$2-$3.wcp
    tar -C "$1" --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf - . | zstd -19 -T0 -q -f -o "$wcp"
    sha256sum "$wcp" | tee "$wcp.sha256"
}
copylib() { # dst lib... (resolve symlinks, keep NEEDED names)
    d=$1; shift; for l in "$@"; do cp -L "$l" "$d/"; done
}
PA=$WORK/pkg-pulse; rm -rf "$PA"; mkdir -p "$PA/bin" "$PA/lib/modules"
cp "$P/bin/pulseaudio" "$P/bin/pactl" "$PA/bin/"
needed() { "$TC/bin/llvm-readelf" -d "$1" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'; }
for n in libpulse.so libpulse-simple.so; do copylib "$PA/lib" "$P/lib/$n"; done
# libpulse.so.0 on Linux; bionic resolves the NEEDED file name, so ship the exact names the binaries ask for.
for f in "$PA/bin/pulseaudio" "$PA/bin/pactl" "$PA/lib/libpulse.so"; do
    for n in $(needed "$f"); do
        for d in "$P/lib" "$P/lib/pulseaudio"; do [ -e "$d/$n" ] && [ ! -e "$PA/lib/$n" ] && copylib "$PA/lib" "$d/$n"; done
    done
done
for m in module-native-protocol-unix module-aaudio-sink; do
    cp "$P/lib/pulseaudio/modules/$m.so" "$PA/lib/modules/"
    for n in $(needed "$PA/lib/modules/$m.so"); do
        for d in "$P/lib" "$P/lib/pulseaudio" "$P/lib/pulseaudio/modules"; do [ -e "$d/$n" ] && [ ! -e "$PA/lib/$n" ] && copylib "$PA/lib" "$d/$n"; done
    done
done
# second pass: deps of the copied libs (libpulsecore -> libltdl/libsndfile ...)
for f in "$PA"/lib/*.so*; do
    for n in $(needed "$f"); do
        for d in "$P/lib" "$P/lib/pulseaudio"; do [ -e "$d/$n" ] && [ ! -e "$PA/lib/$n" ] && copylib "$PA/lib" "$d/$n"; done
    done
done
cp "$RT/README.md" "$PA/README.md"
pkg "$PA" PulseAudio "$PULSE_VERSION" "PulseAudio $PULSE_TAG ($PULSE_COMMIT) daemon + libpulse with termux module-aaudio-sink, libsndfile $SNDFILE_VER, libltdl $LIBTOOL_VER; NDK $AUDIO_NDK_VER aarch64 API $API (scripts/build-audio.sh). Game audio for winepulse.drv."

AL=$WORK/pkg-alsa; rm -rf "$AL"; mkdir -p "$AL/lib/alsa-lib"
copylib "$AL/lib" "$P/lib/libasound.so"
cp "$P/lib/alsa-lib/libasound_module_pcm_aaudio.so" "$AL/lib/alsa-lib/"
cp "$RT/README.md" "$AL/README.md"
pkg "$AL" ALSA "$ALSA_VERSION" "alsa-lib $ALSA_LIB_VER + PanPlay AAudio ioplug PCM plugin (runtimes/audio/pcm_aaudio.c); NDK $AUDIO_NDK_VER aarch64 API $API (scripts/build-audio.sh). Game audio for winealsa.drv, no sound server."
