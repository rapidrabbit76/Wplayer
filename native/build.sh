#!/usr/bin/env bash
# Builds libmpv and its dependencies for Android arm64 and 32-bit ARM into
# native/out/jniLibs.
#
#   ./build.sh            fetch missing sources, build everything not yet built
#   ./build.sh ffmpeg mpv rebuild the named components (and nothing else)
#   ./build.sh clean      remove build outputs (sources are kept)
#   ABIS=armeabi-v7a ./build.sh ...   only for the given ABIs
#
# Versions live in versions.env. Requires: Android NDK (ANDROID_NDK_HOME),
# meson, ninja, pkg-config, git. Runs on macOS and Linux hosts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
source "$ROOT/versions.env"

API=29
ABIS=(${ABIS:-arm64-v8a armeabi-v7a})
SRC="$ROOT/src"
JOBS="$(getconf _NPROCESSORS_ONLN)"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME (see ../env.sh)}"
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo "unsupported host" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
SYSROOT="$TOOLCHAIN/sysroot"
LDFLAGS_COMMON="-Wl,-z,max-page-size=16384"
unset PKG_CONFIG_PATH PKG_CONFIG_SYSROOT_DIR

select_abi() { # abi: sets the per-ABI toolchain and directories
    ABI=$1
    case "$ABI" in
        arm64-v8a)
            TRIPLE=aarch64-linux-android LIB_TRIPLE=aarch64-linux-android
            CPU_FAMILY=aarch64 CPU=armv8-a FFMPEG_ARCH=(--arch=aarch64 --cpu=armv8-a) ;;
        armeabi-v7a)
            TRIPLE=armv7a-linux-androideabi LIB_TRIPLE=arm-linux-androideabi
            CPU_FAMILY=arm CPU=armv7-a FFMPEG_ARCH=(--arch=arm --cpu=armv7-a --enable-neon) ;;
        *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
    esac
    CC="$TOOLCHAIN/bin/$TRIPLE$API-clang"
    CXX="$TOOLCHAIN/bin/$TRIPLE$API-clang++"
    BUILD="$ROOT/build/$ABI"
    PREFIX="$ROOT/prefix/$ABI"
    OUT="$ROOT/out/jniLibs/$ABI"
    export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
}

log() { printf '\033[1;34m==> %s\033[0m\n' "$*"; }

fetch() { # name url ref [--recursive]
    local name=$1 url=$2 ref=$3
    [[ -d "$SRC/$name" ]] && return
    log "fetching $name $ref"
    git clone --quiet --depth 1 --branch "$ref" ${4:+--recurse-submodules --shallow-submodules} "$url" "$SRC/$name"
}

fetch_all() {
    mkdir -p "$SRC"
    fetch dav1d https://code.videolan.org/videolan/dav1d.git "$DAV1D_VERSION"
    fetch ffmpeg https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_VERSION"
    fetch freetype https://gitlab.freedesktop.org/freetype/freetype.git "$FREETYPE_VERSION"
    fetch fribidi https://github.com/fribidi/fribidi.git "$FRIBIDI_VERSION"
    fetch harfbuzz https://github.com/harfbuzz/harfbuzz.git "$HARFBUZZ_VERSION"
    fetch libass https://github.com/libass/libass.git "$LIBASS_VERSION"
    fetch libplacebo https://code.videolan.org/videolan/libplacebo.git "$LIBPLACEBO_VERSION" --recursive
    fetch mpv https://github.com/mpv-player/mpv.git "$MPV_VERSION"
}

write_cross_file() {
    mkdir -p "$BUILD"
    cat >"$BUILD/cross.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$TOOLCHAIN/bin/llvm-ar'
nm = '$TOOLCHAIN/bin/llvm-nm'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_link_args = ['$LDFLAGS_COMMON']
cpp_link_args = ['$LDFLAGS_COMMON']

[host_machine]
system = 'android'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'
EOF
}

meson_build() { # name [meson options...]
    local name=$1; shift
    log "building $name"
    rm -rf "$BUILD/$name"
    meson setup "$BUILD/$name" "$SRC/$name" \
        --cross-file "$BUILD/cross.ini" \
        --prefix "$PREFIX" --libdir lib \
        --buildtype release --default-library static -Db_staticpic=true \
        "$@"
    ninja -C "$BUILD/$name" -j "$JOBS"
    ninja -C "$BUILD/$name" install >/dev/null
}

build_dav1d() {
    meson_build dav1d -Denable_tools=false -Denable_tests=false -Denable_examples=false -Denable_docs=false
}

build_ffmpeg() {
    log "building ffmpeg"
    rm -rf "$BUILD/ffmpeg" && mkdir -p "$BUILD/ffmpeg"
    (
        cd "$BUILD/ffmpeg"
        "$SRC/ffmpeg/configure" \
            --prefix="$PREFIX" \
            --target-os=android "${FFMPEG_ARCH[@]}" --enable-cross-compile \
            --cc="$CC" --cxx="$CXX" \
            --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
            --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
            --sysroot="$SYSROOT" \
            --pkg-config=pkg-config --pkg-config-flags=--static \
            --extra-ldflags="$LDFLAGS_COMMON" \
            --enable-shared --disable-static --enable-pic \
            --disable-debug --disable-doc --disable-programs --disable-avdevice \
            --enable-jni --enable-mediacodec \
            --enable-libdav1d \
            --enable-network \
            --disable-vulkan \
            --disable-encoders --enable-encoder=mjpeg,png \
            --disable-muxers
        make -j "$JOBS"
        make install >/dev/null
    )
}

build_freetype() {
    meson_build freetype -Dbrotli=disabled -Dbzip2=disabled -Dharfbuzz=disabled -Dpng=disabled \
        -Dzlib=internal -Dtests=disabled
}

build_fribidi() {
    meson_build fribidi -Ddocs=false -Dbin=false -Dtests=false
}

build_harfbuzz() {
    meson_build harfbuzz -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled \
        -Dicu=disabled -Dgraphite2=disabled -Dchafa=disabled -Dtests=disabled -Ddocs=disabled \
        -Dutilities=disabled -Dintrospection=disabled -Dbenchmark=disabled
}

build_libass() {
    meson_build libass -Dfontconfig=disabled -Ddirectwrite=disabled -Dcoretext=disabled \
        -Drequire-system-font-provider=false -Dlibunibreak=disabled -Dtest=disabled -Dprofile=disabled \
        -Dcompare=disabled -Dfuzz=disabled -Dcheckasm=disabled
}

build_libplacebo() {
    meson_build libplacebo -Dvulkan=disabled -Dopengl=enabled -Dd3d11=disabled \
        -Dglslang=disabled -Dshaderc=disabled -Dlcms=disabled -Dlibdovi=disabled \
        -Ddemos=false -Dtests=false -Dbench=false -Dfuzz=false -Dunwind=disabled -Dxxhash=disabled
}

build_mpv() {
    log "building mpv"
    rm -rf "$BUILD/mpv"
    # Static dependencies contribute C++ code (libplacebo); link the shared STL we ship.
    meson setup "$BUILD/mpv" "$SRC/mpv" \
        --cross-file "$BUILD/cross.ini" \
        --prefix "$PREFIX" --libdir lib \
        --buildtype release --default-library shared -Dprefer_static=true \
        -Dc_link_args="$LDFLAGS_COMMON -lc++_shared" \
        -Dlibmpv=true -Dcplayer=false -Dtests=false -Dgpl=true \
        -Dandroid-media-ndk=enabled -Daudiotrack=enabled -Dopensles=enabled -Daaudio=enabled \
        -Degl-android=enabled -Dgl=enabled -Dvulkan=disabled -Dshaderc=disabled -Dspirv-cross=disabled \
        -Dlua=disabled -Djavascript=disabled -Dlibarchive=disabled -Dlibbluray=disabled \
        -Ddvdnav=disabled -Dcdda=disabled -Duchardet=disabled -Drubberband=disabled \
        -Dlcms2=disabled -Dzimg=disabled -Dvapoursynth=disabled -Djpeg=disabled \
        -Dlibavdevice=disabled -Dmanpage-build=disabled -Dhtml-build=disabled -Dpdf-build=disabled
    ninja -C "$BUILD/mpv" -j "$JOBS"
    ninja -C "$BUILD/mpv" install >/dev/null
}

collect() {
    log "collecting shared libraries into ${OUT#"$ROOT/"}"
    rm -rf "$OUT" && mkdir -p "$OUT"
    local lib
    for lib in libmpv libavcodec libavformat libavutil libavfilter libswresample libswscale; do
        "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded -o "$OUT/$lib.so" "$PREFIX/lib/$lib.so"
    done
    cp "$SYSROOT/usr/lib/$LIB_TRIPLE/libc++_shared.so" "$OUT/"
    # Every library must resolve its dependencies by unversioned soname.
    "$TOOLCHAIN/bin/llvm-readelf" -d "$OUT"/*.so | grep -E 'NEEDED|SONAME' | sort -u
}

COMPONENTS=(dav1d ffmpeg freetype fribidi harfbuzz libass libplacebo mpv)

main() {
    if [[ "${1:-}" == clean ]]; then
        rm -rf "$ROOT/build" "$ROOT/prefix" "$ROOT/out"
        return
    fi
    fetch_all
    local targets=("$@")
    [[ ${#targets[@]} -eq 0 ]] && targets=("${COMPONENTS[@]}")
    local abi name
    for abi in "${ABIS[@]}"; do
        select_abi "$abi"
        log "ABI $ABI"
        write_cross_file
        for name in "${targets[@]}"; do
            local stamp="$BUILD/.$name.done"
            if [[ $# -eq 0 && -f "$stamp" ]]; then
                log "$name already built (pass its name to rebuild)"
                continue
            fi
            "build_$name"
            touch "$stamp"
        done
        collect
    done
}

main "$@"
