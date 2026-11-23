#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_MAIN_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_LIBS_DIR="$APP_MAIN_DIR/jniLibs"
JNI_SRC="$SCRIPT_DIR/ffmpeg_mp3_jni.c"

WORK_DIR="${WORK_DIR:-/tmp/native_build}"
DOWNLOAD_DIR="$WORK_DIR/downloads"
LAME_TAR="$DOWNLOAD_DIR/lame-3.100.tar.gz"
FFMPEG_TAR="$DOWNLOAD_DIR/ffmpeg-6.1.2.tar.xz"
LAME_SRC="$WORK_DIR/lame-3.100"
FFMPEG_SRC="$WORK_DIR/ffmpeg-6.1.2"

# Auto-detect Android NDK if ANDROID_NDK_HOME is not explicitly provided
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME:-}" ]; then
    SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android/sdk}}"
    if [ -d "$SDK_ROOT/ndk" ]; then
        ANDROID_NDK_HOME="$(ls -1d "$SDK_ROOT/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
    fi
fi
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set and no NDK was found in SDK}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "Error: NDK LLVM toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi

# NDK r27/r28+ may omit prebuilt make; fall back cleanly to system make
if [ -x "$NDK/prebuilt/linux-x86_64/bin/make" ]; then
    MAKE_BIN="$NDK/prebuilt/linux-x86_64/bin/make"
elif command -v make >/dev/null 2>&1; then
    MAKE_BIN="$(command -v make)"
else
    echo "Error: GNU make not found in NDK or PATH" >&2
    exit 1
fi

API="${ANDROID_API_LEVEL:-24}"
export PATH="$TOOLCHAIN/bin:$NDK/prebuilt/linux-x86_64/bin:$PATH"
JOBS="$(nproc 2>/dev/null || echo 4)"
TARGET_ABIS="${TARGET_ABIS:-arm64-v8a armeabi-v7a x86_64 x86}"

mkdir -p "$WORK_DIR" "$DOWNLOAD_DIR"

verify_archive() {
    local file="$1"
    local type="$2"
    [ -s "$file" ] || return 1
    case "$type" in
        gz)
            gzip -t "$file" >/dev/null 2>&1
            ;;
        xz)
            if command -v xz >/dev/null 2>&1; then
                xz -t "$file" >/dev/null 2>&1
            else
                python3 -c "import tarfile; tarfile.open('$file', 'r:xz').close()" >/dev/null 2>&1
            fi
            ;;
        *)
            return 1
            ;;
    esac
}

download_with_mirrors() {
    local dest="$1"
    local type="$2"
    shift 2

    if verify_archive "$dest" "$type"; then
        return 0
    fi
    rm -f "$dest"

    local tmp="${dest}.tmp"
    for url in "$@"; do
        echo "Downloading $(basename "$dest") from $url..."
        rm -f "$tmp"
        if curl -fsSL -L -A "Mozilla/5.0" --retry 3 --retry-delay 2 -o "$tmp" "$url"; then
            if verify_archive "$tmp" "$type"; then
                mv "$tmp" "$dest"
                return 0
            fi
            echo "Warning: Downloaded file from $url failed integrity check, trying next mirror..." >&2
        else
            echo "Warning: Failed to download from $url, trying next mirror..." >&2
        fi
    done

    rm -f "$tmp"
    echo "Error: Could not download valid archive for $(basename "$dest")" >&2
    return 1
}

update_gnu_config_scripts() {
    local target_dir="$1"
    local updated_sub=""
    local updated_guess=""

    for candidate in /usr/share/misc/config.sub /usr/share/automake-*/config.sub; do
        if [ -f "$candidate" ]; then
            updated_sub="$candidate"
            break
        fi
    done
    for candidate in /usr/share/misc/config.guess /usr/share/automake-*/config.guess; do
        if [ -f "$candidate" ]; then
            updated_guess="$candidate"
            break
        fi
    done

    if [ -n "$updated_sub" ] && [ -n "$updated_guess" ]; then
        cp -f "$updated_sub" "$target_dir/config.sub"
        cp -f "$updated_guess" "$target_dir/config.guess"
        chmod +x "$target_dir/config.sub" "$target_dir/config.guess"
    else
        echo "Downloading updated GNU config.sub & config.guess for Android target triples..."
        curl -fsSL -L --retry 3 -o "$target_dir/config.sub" "https://git.savannah.gnu.org/gitweb/?p=config.git;a=blob_plain;f=config.sub;hb=HEAD"
        curl -fsSL -L --retry 3 -o "$target_dir/config.guess" "https://git.savannah.gnu.org/gitweb/?p=config.git;a=blob_plain;f=config.guess;hb=HEAD"
        chmod +x "$target_dir/config.sub" "$target_dir/config.guess"
    fi
}

if [ ! -d "$LAME_SRC" ]; then
    download_with_mirrors "$LAME_TAR" "gz" \
        "https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz" \
        "https://sourceforge.net/projects/lame/files/lame/3.100/lame-3.100.tar.gz/download" \
        "https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz" \
        "https://ftp.osuosl.org/pub/blfs/conglomeration/lame/lame-3.100.tar.gz"
    tar -xzf "$LAME_TAR" -C "$WORK_DIR"
fi

# LAME 3.100 ships an older config.sub that does not recognize aarch64-linux-android
update_gnu_config_scripts "$LAME_SRC"

if [ ! -d "$FFMPEG_SRC" ]; then
    download_with_mirrors "$FFMPEG_TAR" "xz" \
        "https://ffmpeg.org/releases/ffmpeg-6.1.2.tar.xz" \
        "https://www.ffmpeg.org/releases/ffmpeg-6.1.2.tar.xz" \
        "https://fossies.org/linux/misc/ffmpeg-6.1.2.tar.xz"
    if command -v xz >/dev/null 2>&1; then
        tar -xJf "$FFMPEG_TAR" -C "$WORK_DIR"
    else
        python3 -c "import tarfile; tarfile.open('$FFMPEG_TAR', 'r:xz').extractall('$WORK_DIR')"
    fi
fi

build_abi() {
    local ABI="$1"
    local TARGET_HOST="$2"
    local CLANG_TRIPLE="$3"
    local FFMPEG_ARCH="$4"
    local FFMPEG_CPU="$5"
    local EXTRA_CFLAGS="$6"
    local FFMPEG_EXTRA_CFG="$7"

    echo "========================================"
    echo "Building MP3-only libffmpeg.so for $ABI"
    echo "========================================"

    local PREFIX="$WORK_DIR/install/$ABI"
    local LAME_BUILD="$WORK_DIR/build_lame/$ABI"
    local FFMPEG_BUILD="$WORK_DIR/build_ffmpeg/$ABI"
    local OUT_DIR="$JNI_LIBS_DIR/$ABI"

    rm -rf "$PREFIX" "$LAME_BUILD" "$FFMPEG_BUILD"
    mkdir -p "$PREFIX" "$LAME_BUILD" "$FFMPEG_BUILD" "$OUT_DIR"

    local CC="$TOOLCHAIN/bin/${CLANG_TRIPLE}${API}-clang"
    local CXX="$TOOLCHAIN/bin/${CLANG_TRIPLE}${API}-clang++"
    local AR="$TOOLCHAIN/bin/llvm-ar"
    local RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    local NM="$TOOLCHAIN/bin/llvm-nm"
    local STRIP="$TOOLCHAIN/bin/llvm-strip"
    local COMPAT_CFLAGS="-Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types -Wno-error=int-conversion"

    if [ ! -x "$CC" ]; then
        echo "Error: Clang wrapper not found at $CC" >&2
        exit 1
    fi

    # 1. Build libmp3lame.a
    pushd "$LAME_BUILD" >/dev/null
    if ! CC="$CC" \
        CXX="$CXX" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        NM="$NM" \
        STRIP="$STRIP" \
        CFLAGS="-O2 -fPIC -DSTDC_HEADERS $COMPAT_CFLAGS $EXTRA_CFLAGS" \
        ac_cv_func_malloc_0_nonnull=yes \
        ac_cv_func_realloc_0_nonnull=yes \
        ac_cv_header_xmmintrin_h=no \
        "$LAME_SRC/configure" \
            --host="$TARGET_HOST" \
            --prefix="$PREFIX" \
            --enable-static \
            --disable-shared \
            --disable-frontend \
            --disable-decoder \
            --with-pic \
            --quiet; then
        echo "LAME configure failed for $ABI; tail of config.log:" >&2
        tail -n 80 "$LAME_BUILD/config.log" >&2 || true
        exit 1
    fi

    if ! "$MAKE_BIN" -j"$JOBS" install >"$LAME_BUILD/build.log" 2>&1; then
        echo "LAME make failed for $ABI; tail of build.log:" >&2
        tail -n 100 "$LAME_BUILD/build.log" >&2 || true
        exit 1
    fi
    popd >/dev/null

    # 2. Build minimal MP3-only FFmpeg static libraries
    pushd "$FFMPEG_BUILD" >/dev/null
    if ! "$FFMPEG_SRC/configure" \
        --prefix="$PREFIX" \
        --target-os=android \
        --arch="$FFMPEG_ARCH" \
        --cpu="$FFMPEG_CPU" \
        --cc="$CC" \
        --cxx="$CXX" \
        --as="$CC" \
        --ld="$CC" \
        --ar="$AR" \
        --ranlib="$RANLIB" \
        --nm="$NM" \
        --strip="$STRIP" \
        --enable-cross-compile \
        --enable-static \
        --disable-shared \
        --enable-pic \
        --enable-optimizations \
        --enable-small \
        --disable-symver \
        --disable-debug \
        --disable-programs \
        --disable-doc \
        --disable-avdevice \
        --disable-avfilter \
        --disable-swscale \
        --disable-postproc \
        --disable-network \
        --disable-vulkan \
        --disable-hwaccels \
        --disable-everything \
        --enable-libmp3lame \
        --enable-encoder=libmp3lame \
        --enable-decoder=mp3,mp3float,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_u8 \
        --enable-muxer=mp3 \
        --enable-demuxer=mp3,wav,pcm_s16le \
        --enable-parser=mpegaudio \
        --enable-protocol=file,pipe \
        --extra-cflags="-O2 -fPIC $COMPAT_CFLAGS -I$PREFIX/include $EXTRA_CFLAGS" \
        --extra-ldflags="-L$PREFIX/lib" \
        $FFMPEG_EXTRA_CFG; then
        echo "FFmpeg configure failed for $ABI; tail of ffbuild/config.log:" >&2
        tail -n 100 "$FFMPEG_BUILD/ffbuild/config.log" >&2 || true
        exit 1
    fi

    if ! "$MAKE_BIN" -j"$JOBS" install >"$FFMPEG_BUILD/build.log" 2>&1; then
        echo "FFmpeg make failed for $ABI; tail of build.log:" >&2
        tail -n 120 "$FFMPEG_BUILD/build.log" >&2 || true
        exit 1
    fi
    popd >/dev/null

    # 3. Link JNI bridge + FFmpeg static archives + libmp3lame.a into single libffmpeg.so
    "$CC" \
        -O2 -fPIC -shared \
        -I"$PREFIX/include" \
        "$JNI_SRC" \
        -Wl,--start-group \
        "$PREFIX/lib/libavformat.a" \
        "$PREFIX/lib/libavcodec.a" \
        "$PREFIX/lib/libswresample.a" \
        "$PREFIX/lib/libavutil.a" \
        "$PREFIX/lib/libmp3lame.a" \
        -Wl,--end-group \
        -lm -lz -llog \
        -Wl,-soname,libffmpeg.so \
        -Wl,-Bsymbolic \
        -Wl,-z,max-page-size=16384 \
        -Wl,--gc-sections \
        -o "$OUT_DIR/libffmpeg.so"

    "$STRIP" --strip-unneeded "$OUT_DIR/libffmpeg.so"
    ls -lh "$OUT_DIR/libffmpeg.so"
}

for abi in $TARGET_ABIS; do
    case "$abi" in
        arm64-v8a)
            build_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android" "aarch64" "armv8-a" "" "--enable-neon"
            ;;
        armeabi-v7a)
            build_abi "armeabi-v7a" "arm-linux-androideabi" "armv7a-linux-androideabi" "arm" "armv7-a" "-mfloat-abi=softfp -mfpu=neon" "--enable-neon"
            ;;
        x86_64)
            build_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android" "x86_64" "x86-64" "" "--disable-x86asm"
            ;;
        x86)
            build_abi "x86" "i686-linux-android" "i686-linux-android" "x86" "i686" "" "--disable-asm"
            ;;
        *)
            echo "Unknown ABI: $abi" >&2
            exit 1
            ;;
    esac
done

echo "All libffmpeg.so builds complete!"
