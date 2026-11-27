#!/usr/bin/env bash
# THROWAWAY SPIKE (issue #17): builds PJSIP pjsua2 + OpenSSL for Android and drops
#   - app/src/main/jniLibs/<abi>/libpjsua2.so, libc++_shared.so
#   - app/src/main/java/org/pjsip/pjsua2/*.java   (SWIG-generated bindings)
# into this spike project. Not used by :core / :app.
#
# Usage:  spike/sip/build-pjsip.sh            # arm64-v8a only (Pixel 3a)
#         ABIS="arm64-v8a armeabi-v7a" spike/sip/build-pjsip.sh
# Needs: JDK, Android SDK with sdkmanager (ANDROID_SDK_ROOT, default /root/android-sdk),
#        swig (apt-get install swig), make, curl, git, perl.
set -euo pipefail

# ---- pinned versions -------------------------------------------------------
PJ_TAG="2.18"
OPENSSL_VER="3.5.9"   # LTS line
OPENSSL_SHA256="603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
NDK_VER="29.0.14206865"   # r29
SWIG_MIN="4.2.0"
API_LEVEL="31"            # = minSdk of the spike app
ABIS="${ABIS:-arm64-v8a}"
JOBS="${JOBS:-$(nproc)}"

SPIKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/root/android-sdk}}"
WORK="${WORK:-$HOME/spike-build}"          # build intermediates live outside the repo
OUT_JNI="$SPIKE_DIR/app/src/main/jniLibs"
OUT_JAVA="$SPIKE_DIR/app/src/main/java/org/pjsip/pjsua2"
mkdir -p "$WORK"

log() { echo "==> $*"; }

# ---- tools -----------------------------------------------------------------
NDK="$SDK/ndk/$NDK_VER"
if [ ! -d "$NDK" ]; then
  log "Installing NDK $NDK_VER"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --install "ndk;$NDK_VER" >/dev/null
fi
export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

command -v swig >/dev/null || { echo "swig not found: apt-get install swig (>= $SWIG_MIN)"; exit 1; }
SWIG_VER="$(swig -version | sed -n 's/^SWIG Version //p')"
if [ "$(printf '%s\n%s\n' "$SWIG_MIN" "$SWIG_VER" | sort -V | head -1)" != "$SWIG_MIN" ]; then
  echo "swig $SWIG_VER is older than $SWIG_MIN"; exit 1
fi
log "NDK $NDK_VER, SWIG $SWIG_VER, pjproject $PJ_TAG, OpenSSL $OPENSSL_VER, API $API_LEVEL, ABIs: $ABIS"

# ---- sources ---------------------------------------------------------------
cd "$WORK"
if [ ! -d "pjproject-$PJ_TAG" ]; then
  git clone -q --depth 1 --branch "$PJ_TAG" https://github.com/pjsip/pjproject "pjproject-$PJ_TAG"
fi
TARBALL="openssl-$OPENSSL_VER.tar.gz"
if [ ! -f "dl/$TARBALL" ]; then
  mkdir -p dl
  curl -sSfL -o "dl/$TARBALL" "https://www.openssl.org/source/$TARBALL"
fi
echo "$OPENSSL_SHA256  dl/$TARBALL" | sha256sum -c -

openssl_target() {
  case "$1" in
    arm64-v8a) echo android-arm64 ;;
    armeabi-v7a) echo android-arm ;;
    x86_64) echo android-x86_64 ;;
    x86) echo android-x86 ;;
    *) echo "unknown ABI $1" >&2; exit 1 ;;
  esac
}

for ABI in $ABIS; do
  # ---- OpenSSL (static, linked into libpjsua2.so) --------------------------
  OSSL_PREFIX="$WORK/openssl-$OPENSSL_VER-$ABI"
  if [ ! -f "$OSSL_PREFIX/lib/libssl.a" ]; then
    log "OpenSSL $OPENSSL_VER for $ABI"
    rm -rf "$WORK/ossl-src-$ABI"; mkdir -p "$WORK/ossl-src-$ABI"
    tar -xzf "dl/$TARBALL" -C "$WORK/ossl-src-$ABI" --strip-components=1
    (
      cd "$WORK/ossl-src-$ABI"
      export PATH="$TOOLCHAIN/bin:$PATH"
      ./Configure "$(openssl_target "$ABI")" -D__ANDROID_API__="$API_LEVEL" \
        no-shared no-tests no-docs no-apps no-module no-legacy no-engine \
        --prefix="$OSSL_PREFIX" --openssldir="$OSSL_PREFIX/ssl" --libdir=lib >/dev/null
      make -j"$JOBS" build_libs >/dev/null
      make install_dev >/dev/null
    )
    rm -rf "$WORK/ossl-src-$ABI"
  fi

  # ---- pjproject (one source copy per ABI: the build is in-tree) ------------
  PJ="$WORK/pj-$ABI"
  log "pjproject $PJ_TAG for $ABI"
  rm -rf "$PJ"; cp -a "$WORK/pjproject-$PJ_TAG" "$PJ"
  cat > "$PJ/pjlib/include/pj/config_site.h" <<'EOF'
/* Robby SIP spike: audio only, no Android audio devices (null sound device only). */
#define PJMEDIA_HAS_VIDEO                  0
#define PJMEDIA_AUDIO_DEV_HAS_ANDROID_JNI  0
#define PJMEDIA_AUDIO_DEV_HAS_OPENSL       0
#define PJMEDIA_AUDIO_DEV_HAS_PORTAUDIO    0
#define PJMEDIA_HAS_SRTP                   1
#define PJSUA_DEFAULT_SND_USE_SW_CLOCK     PJ_TRUE
#define PJ_LOG_MAX_LEVEL                   5
EOF
  (
    cd "$PJ"
    TARGET_ABI="$ABI" APP_PLATFORM="android-$API_LEVEL" ./configure-android --use-ndk-cflags \
      --with-ssl="$OSSL_PREFIX" \
      --disable-sound --disable-video --disable-android-mediacodec \
      --disable-speex-codec --disable-speex-aec --disable-ilbc-codec --disable-gsm-codec \
      --disable-g7221-codec --disable-l16-codec --disable-opencore-amr --disable-silk \
      --disable-opus --disable-bcg729 --disable-lyra --disable-libyuv --disable-libwebrtc \
      --disable-sdl --disable-ffmpeg --disable-v4l2 --disable-openh264 --disable-vpx \
      --disable-upnp --disable-libuuid > "$WORK/configure-$ABI.log" 2>&1
    grep -E 'OpenSSL' "$WORK/configure-$ABI.log" || true
    grep -q 'OpenSSL library found' "$WORK/configure-$ABI.log" \
      || { echo "OpenSSL not detected by configure, see $WORK/configure-$ABI.log"; exit 1; }
    make dep >/dev/null 2>&1
    make -j"$JOBS" >"$WORK/make-$ABI.log" 2>&1 || { tail -40 "$WORK/make-$ABI.log"; exit 1; }
    cd pjsip-apps/src/swig
    make -j1 java >"$WORK/swig-$ABI.log" 2>&1 || { tail -40 "$WORK/swig-$ABI.log"; exit 1; }
  )

  SWIG_AND="$PJ/pjsip-apps/src/swig/java/android/pjsua2/src/main"
  mkdir -p "$OUT_JNI/$ABI"
  cp -f "$SWIG_AND/jniLibs/$ABI/libpjsua2.so" "$SWIG_AND/jniLibs/$ABI/libc++_shared.so" "$OUT_JNI/$ABI/"
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUT_JNI/$ABI/libpjsua2.so" "$OUT_JNI/$ABI/libc++_shared.so"
  # The Java side is ABI-independent: take it from the first ABI.
  if [ "$ABI" = "${ABIS%% *}" ]; then
    rm -rf "$OUT_JAVA"; mkdir -p "$OUT_JAVA"
    cp -a "$SWIG_AND/java/org/pjsip/pjsua2/." "$OUT_JAVA/"
  fi
  rm -rf "$PJ"   # intermediates are large; the pristine clone stays for the next ABI/run
done

log "Done:"
du -sh "$OUT_JNI"/* "$OUT_JAVA"
