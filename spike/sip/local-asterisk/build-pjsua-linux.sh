#!/usr/bin/env bash
# Build the Linux pjsua CLI from PJSIP 2.18 (the spike's version) with OpenSSL
# (TLS + SRTP crypto), no sound/video. Needs build-essential and libssl-dev.
# Usage: ./build-pjsua-linux.sh [WORKDIR]   -> prints the path of the pjsua binary
set -euo pipefail
WORK=${1:-"$HOME/.cache/robby-pjsua"}
mkdir -p "$WORK"
SRC=$WORK/pjproject-2.18
if [[ ! -d "$SRC" ]]; then
  git clone --quiet --depth 1 --branch 2.18 https://github.com/pjsip/pjproject "$SRC"
fi
cd "$SRC"
cat > pjlib/include/pj/config_site.h <<'EOF'
#define PJ_HAS_SSL_SOCK 1
#define PJMEDIA_HAS_SRTP 1
#define PJMEDIA_HAS_VIDEO 0
EOF
./configure --disable-video --disable-sdl --disable-ffmpeg --disable-v4l2 --disable-openh264 \
  --disable-libyuv --disable-libwebrtc --disable-sound --with-ssl > "$WORK/configure.log" 2>&1
grep -q "OpenSSL library found" "$WORK/configure.log" || { echo "OpenSSL not found (apt install libssl-dev)" >&2; exit 1; }
make dep > "$WORK/make.log" 2>&1
make -j"$(nproc)" >> "$WORK/make.log" 2>&1
ls "$SRC"/pjsip-apps/bin/pjsua-*
