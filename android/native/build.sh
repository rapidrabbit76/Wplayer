#!/usr/bin/env bash
# Builds libmpv and its dependencies for Android arm64 and 32-bit ARM into
# native/out/jniLibs, and the static libarchive (with liblzma) that the Rust core
# links into native/out/static.
#
#   ./build.sh            fetch missing sources, build everything not yet built
#   ./build.sh ffmpeg mpv rebuild the named components (and nothing else)
#   ./build.sh clean      remove build outputs (sources are kept)
#   ABIS=armeabi-v7a ./build.sh ...   only for the given ABIs
#
# Versions live in versions.env; patches/<component>/*.patch are applied to the
# fetched sources (rebuild the component after adding one). Requires: Android NDK (ANDROID_NDK_HOME),
# meson, cmake, ninja, pkg-config, git, curl. Runs on macOS and Linux hosts.
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

fetch_tarball() { # name url sha256
    local name=$1 url=$2 sha256=$3
    [[ -d "$SRC/$name" ]] && return
    log "fetching $name $(basename "$url")"
    local archive="$SRC/$(basename "$url")" actual
    curl -fsSL -o "$archive" "$url"
    actual=$( (sha256sum "$archive" 2>/dev/null || shasum -a 256 "$archive") | cut -d' ' -f1)
    if [[ $actual != "$sha256" ]]; then
        rm -f "$archive"
        echo "$name: sha256 $actual does not match versions.env" >&2
        exit 1
    fi
    rm -rf "$SRC/$name.tmp" && mkdir "$SRC/$name.tmp"
    tar -xf "$archive" -C "$SRC/$name.tmp" --strip-components=1
    mv "$SRC/$name.tmp" "$SRC/$name"
    rm "$archive"
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
    fetch_tarball mbedtls "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$MBEDTLS_VERSION/mbedtls-$MBEDTLS_VERSION.tar.bz2" "$MBEDTLS_SHA256"
    fetch_tarball xz "https://github.com/tukaani-project/xz/releases/download/v$XZ_VERSION/xz-$XZ_VERSION.tar.xz" "$XZ_SHA256"
    fetch_tarball libarchive "https://github.com/libarchive/libarchive/releases/download/v$LIBARCHIVE_VERSION/libarchive-$LIBARCHIVE_VERSION.tar.xz" "$LIBARCHIVE_SHA256"
}

apply_patches() { # applies patches/<component>/*.patch once each
    local dir patch name
    for dir in "$ROOT"/patches/*/; do
        [[ -d "$dir" ]] || continue
        name=$(basename "$dir")
        for patch in "$dir"*.patch; do
            git -C "$SRC/$name" apply --reverse --check "$patch" 2>/dev/null && continue
            log "patching $name: $(basename "$patch")"
            git -C "$SRC/$name" apply "$patch"
        done
    done
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

cmake_build() { # name [cmake options...]
    local name=$1; shift
    log "building $name"
    rm -rf "$BUILD/$name"
    # Section flags let the Rust link drop the parts of these static libraries it never calls.
    cmake -S "$SRC/$name" -B "$BUILD/$name" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$API" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
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
            --enable-version3 --enable-mbedtls \
            --disable-vulkan \
            --disable-encoders --enable-encoder=mjpeg,png \
            --disable-muxers
        make -j "$JOBS"
        make install >/dev/null
    )
}

# TLS for https links and HLS segments. FFmpeg takes mbedTLS only under (L)GPLv3,
# which the GPL-3.0-or-later app already is. Linked statically into libavformat.
build_mbedtls() {
    cmake_build mbedtls -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF -DMBEDTLS_FATAL_WARNINGS=OFF \
        -DUSE_SHARED_MBEDTLS_LIBRARY=OFF -DUSE_STATIC_MBEDTLS_LIBRARY=ON
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

# liblzma for 7z (and zip's xz/lzma methods). The encoders stay: libarchive's
# configure only accepts a full liblzma, and the link drops them anyway.
build_xz() {
    cmake_build xz -DXZ_NLS=OFF -DXZ_DOC=OFF -DBUILD_TESTING=OFF \
        -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF
}

# Reads zip, 7z and RAR comic archives. Deflate comes from the NDK's zlib. No iconv:
# the Rust core decodes legacy (CP949) entry names itself. No bzip2, zstd or crypto
# libraries: those zip and 7z methods are rare in comic archives.
build_libarchive() {
    cmake_build libarchive -DENABLE_TEST=OFF \
        -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF \
        -DENABLE_ACL=OFF -DENABLE_XATTR=OFF -DENABLE_ICONV=OFF \
        -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_MD=OFF -DENABLE_CNG=OFF \
        -DENABLE_ZLIB=ON -DENABLE_LZMA=ON \
        -DLIBLZMA_INCLUDE_DIR="$PREFIX/include" -DLIBLZMA_LIBRARY="$PREFIX/lib/liblzma.a" \
        -DENABLE_BZip2=OFF -DENABLE_ZSTD=OFF -DENABLE_LZ4=OFF -DENABLE_LZO=OFF -DENABLE_LIBB2=OFF \
        -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_BSDXML=OFF -DENABLE_WIN32_XMLLITE=OFF \
        -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_LIBGCC=OFF
}

collect() {
    log "collecting shared libraries into ${OUT#"$ROOT/"}"
    rm -rf "$OUT" && mkdir -p "$OUT"
    local lib
    for lib in libmpv libavcodec libavformat libavutil libavfilter libswresample libswscale; do
        "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded -o "$OUT/$lib.so" "$PREFIX/lib/$lib.so"
    done
    cp "$SYSROOT/usr/lib/$LIB_TRIPLE/libc++_shared.so" "$OUT/"
    # Linked into libwplayer_core.so by rust/wplayer-core/build.rs, so nothing extra ships.
    local static_out="$ROOT/out/static/$ABI"
    rm -rf "$static_out" && mkdir -p "$static_out"
    cp "$PREFIX/lib/libarchive.a" "$PREFIX/lib/liblzma.a" "$static_out/"
    # Every library must resolve its dependencies by unversioned soname.
    "$TOOLCHAIN/bin/llvm-readelf" -d "$OUT"/*.so | grep -E 'NEEDED|SONAME' | sort -u
}

COMPONENTS=(dav1d mbedtls ffmpeg freetype fribidi harfbuzz libass libplacebo mpv xz libarchive)

main() {
    if [[ "${1:-}" == clean ]]; then
        rm -rf "$ROOT/build" "$ROOT/prefix" "$ROOT/out"
        return
    fi
    fetch_all
    apply_patches
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
