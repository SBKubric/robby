#!/usr/bin/env bash
# Local check of infra/sip (Flexisip) with its self-signed fallback certificate.
#
# Runs the real docker-compose.yml (plus test/compose.test.yml) on this machine
# with SIP_DOMAIN=sip.sbkubric.com resolving to 127.0.0.1, and two Linux pjsua
# clients (PJSIP 2.18) with the spike's settings: SIP over TLS with
# certificate verification, digest auth, mandatory SDES-SRTP, G.711 only,
# 20 ms, null audio. Checks:
#   1. negative: wrong password, no credentials, call to another domain;
#   2. call A, spike settings (ICE + STUN on the server's own STUN);
#   3. call B, ICE off: media can only go through Flexisip's MediaRelay;
#   each call: robby -> office, auto-answer, 1000 Hz / 440 Hz tones both
#   ways, recordings checked, SRTP suite, clean hang-up.
#
# Needs root (the clients run in a private mount namespace with their own
# /etc/hosts), Docker with compose, openssl, python3, and pjsua:
#   PJSUA=/path/to/pjsua ./test/run-check.sh
# Behind a TLS-inspecting proxy pass its CA for the image build:
#   EXTRA_CA_FILE=/path/to/ca.pem
# KEEP=1 leaves the server running. Output: test/out/summary.txt and logs.
set -euo pipefail

TEST=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$TEST/.." && pwd)
GEN=$TEST/generated
OUT=$TEST/out
PJSUA=${PJSUA:-$(command -v pjsua || true)}
DOMAIN=${SIP_DOMAIN:-sip.sbkubric.com}
CALL_SECS=${CALL_SECS:-10}
KEEP=${KEEP:-0}
IMAGE=robby-flexisip:2.6.2-1

[[ -n "$PJSUA" && -x "$PJSUA" ]] || { echo "set PJSUA=/path/to/pjsua (test/build-pjsua-linux.sh builds one)" >&2; exit 2; }
[[ $(id -u) -eq 0 ]] || { echo "run as root (unshare + bind-mount of /etc/hosts for the clients)" >&2; exit 2; }
for t in docker openssl python3 unshare; do command -v "$t" >/dev/null || { echo "missing: $t" >&2; exit 2; }; done

rm -rf "$OUT" "$GEN"
mkdir -p "$OUT" "$GEN/data/tls" "$GEN/data/log"

ROBBY_PASSWORD=$(openssl rand -hex 16)
OFFICE_PASSWORD=$(openssl rand -hex 16)
cat > "$GEN/test.env" <<EOF
SIP_DOMAIN=$DOMAIN
ROBBY_PASSWORD=$ROBBY_PASSWORD
OFFICE_PASSWORD=$OFFICE_PASSWORD
COMPOSE_PROFILES=
MEDIARELAY_PREVENT_LOOPS=false
FLEXISIP_LOG_LEVEL=${FLEXISIP_LOG_LEVEL:-message}
EOF
chmod 600 "$GEN/test.env"

compose() {
  docker compose -p robby-sip-test --project-directory "$ROOT" --env-file "$GEN/test.env" \
    -f "$ROOT/docker-compose.yml" -f "$TEST/compose.test.yml" "$@"
}
PIDS=()
cleanup() {
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
  if [[ "$KEEP" != 1 ]]; then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# Clients see the domain as this machine.
printf '127.0.0.1 localhost\n127.0.0.1 %s\n' "$DOMAIN" > "$GEN/hosts"
in_ns() { unshare -m sh -c 'mount --bind "$0" /etc/hosts && exec "$@"' "$GEN/hosts" "$@"; }

python3 "$TEST/wavtool.py" tone "$GEN/robby-tone.wav" 1000 60
python3 "$TEST/wavtool.py" tone "$GEN/office-tone.wav" 440 60

# 1. Server.
if [[ -n "${EXTRA_CA_FILE:-}" ]]; then
  docker build -q --secret "id=extra_ca,src=$EXTRA_CA_FILE" -t "$IMAGE" "$ROOT/flexisip" >/dev/null
  compose up -d --no-build --force-recreate >/dev/null 2>&1
else
  compose up -d --build --force-recreate >/dev/null 2>&1
fi
for _ in $(seq 1 60); do
  [[ -s "$GEN/data/tls/cert.pem" ]] && openssl s_client -connect 127.0.0.1:5061 -brief </dev/null >/dev/null 2>&1 && break
  sleep 1
done
compose logs --no-color flexisip > "$OUT/flexisip-start.log" 2>&1 || true
openssl s_client -connect 127.0.0.1:5061 -brief </dev/null >/dev/null 2>&1 || { echo "Flexisip did not start, see out/flexisip-start.log" >&2; exit 1; }
CA=$GEN/data/tls/cert.pem   # the self-signed fallback is its own trust anchor

COMMON=(
  --null-audio --clock-rate=8000 --snd-clock-rate=8000 --ptime=20 --no-vad --ec-tail=0
  --use-tls --no-udp --tls-ca-file="$CA" --tls-verify-server
  --use-srtp=2 --srtp-secure=1 --srtp-keying=0
  --dis-codec=speex --dis-codec=iLBC --dis-codec=GSM --dis-codec=G722 --dis-codec=L16 --dis-codec=opus
  --proxy="sip:$DOMAIN;transport=tls;lr"
  --realm='*' --reg-timeout=300 --max-calls=1
  --log-level=5 --app-log-level=3 --no-color
)
REG=(--registrar="sip:$DOMAIN;transport=tls")
ICE_ON=(--use-ice --stun-srv="$DOMAIN:3478")
ICE_OFF=()

# pjsua as <who>, lifetime in seconds, extra args...; stdin script via $CMDS.
pj() {
  local name=$1 life=$2; shift 2
  ( sleep "$life"; echo q ) | in_ns "$PJSUA" "${COMMON[@]}" --log-file="$OUT/$name-pjsua.log" "$@" \
    > "$OUT/$name-console.log" 2>&1
}

# 2. Negative checks.
pj neg-wrongpass 6 "${REG[@]}" --id="sip:robby@$DOMAIN" --username=robby --password="wrong-$(openssl rand -hex 8)" \
  --local-port=6080 || true
pj neg-noauth 8 --id="sip:robby@$DOMAIN" --local-port=6082 "sip:office@$DOMAIN" || true
pj neg-otherdomain 8 "${REG[@]}" --id="sip:robby@$DOMAIN" --username=robby --password="$ROBBY_PASSWORD" \
  --local-port=6084 "sip:someone@example.com" || true

# 3. Calls.
call() { # tag, ice-args-array-name
  local tag=$1; local -n ice=$2
  pj "$tag-office" $((CALL_SECS + 22)) "${REG[@]}" --id="sip:office@$DOMAIN" --username=office --password="$OFFICE_PASSWORD" \
    --local-port=6070 --rtp-port=4100 "${ice[@]}" --auto-answer=200 \
    --play-file="$GEN/office-tone.wav" --auto-play --rec-file="$OUT/$tag-office-heard.wav" --auto-rec &
  local office=$!; PIDS+=("$office")
  for _ in $(seq 1 20); do grep -q "registration success" "$OUT/$tag-office-pjsua.log" 2>/dev/null && break; sleep 0.5; done
  ( sleep $((CALL_SECS / 2 + 3)); echo dq; sleep $((CALL_SECS / 2 + 8)); echo q ) |
    in_ns "$PJSUA" "${COMMON[@]}" "${REG[@]}" --log-file="$OUT/$tag-robby-pjsua.log" \
      --id="sip:robby@$DOMAIN" --username=robby --password="$ROBBY_PASSWORD" \
      --local-port=6060 --rtp-port=4000 "${ice[@]}" --duration="$CALL_SECS" \
      --play-file="$GEN/robby-tone.wav" --auto-play --rec-file="$OUT/$tag-robby-heard.wav" --auto-rec \
      "sip:office@$DOMAIN" > "$OUT/$tag-robby-console.log" 2>&1 || true
  wait "$office" || true
}
call a ICE_ON
call b ICE_OFF

# 4. Certificate renewal: a new certificate installed by the real certbot deploy
# hook (as after a Let's Encrypt renewal) must be served without a restart.
fpr() { openssl s_client -connect 127.0.0.1:5061 </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2; }
OLD_FPR=$(fpr)
NEW_FPR=""
RELOAD_SECS=""
if [[ "${CHECK_CERT_RELOAD:-1}" == 1 ]]; then
  mkdir -p "$GEN/le"
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -sha256 -subj "/CN=$DOMAIN" -addext "subjectAltName=DNS:$DOMAIN" \
    -keyout "$GEN/le/privkey.pem" -out "$GEN/le/fullchain.pem" 2>/dev/null
  CERTBOT_IMAGE=$(compose config --images 2>/dev/null | grep certbot || true)
  CERTBOT_IMAGE=${CERTBOT_IMAGE:-$(grep -oE 'certbot/certbot:[^ ]+' "$ROOT/docker-compose.yml")}
  docker run --rm --entrypoint sh -e RENEWED_LINEAGE=/le -v "$GEN/le:/le:ro" -v "$GEN/data/tls:/tls" \
    -v "$ROOT/certbot:/scripts:ro" "$CERTBOT_IMAGE" /scripts/deploy-hook.sh > "$OUT/deploy-hook.log" 2>&1 || true
  WANT=$(openssl x509 -in "$GEN/le/fullchain.pem" -noout -fingerprint -sha256 | cut -d= -f2)
  start=$(date +%s)
  for _ in $(seq 1 30); do
    sleep 5
    NEW_FPR=$(fpr)
    [[ "$NEW_FPR" == "$WANT" ]] && { RELOAD_SECS=$(( $(date +%s) - start )); break; }
  done
  CA=$GEN/le/fullchain.pem
  # A client with the new trust anchor registers over the reloaded certificate.
  pj after-reload 5 "${REG[@]}" --id="sip:robby@$DOMAIN" --username=robby --password="$ROBBY_PASSWORD" \
    --local-port=6086 --tls-ca-file="$CA" || true
fi
compose logs --no-color flexisip > "$OUT/flexisip.log" 2>&1 || true

# 4. Verdict.
fails=0
check() { local n=$1; shift; if "$@" >/dev/null 2>&1; then echo "OK    $n"; else echo "FAIL  $n"; fails=$((fails + 1)); fi; }
first() { grep -m1 -E "$1" "$2" 2>/dev/null | sed -E 's/^[0-9:.]+ +//; s/inline:[^ ]+/inline:<hidden>/' || true; }
# The SDP answer robby got for the call (from the 200 OK): where media goes.
answer_sdp() { awk '/SIP\/2.0 200 OK/{p=1} p&&/^CSeq: [0-9]+ INVITE/{inv=1} p&&inv&&/^(c=|m=audio|a=candidate)/{print} /^--end msg--/{if(inv)exit; p=0}' "$1"; }
relayed() { answer_sdp "$1" | grep -qE "^m=audio (4[0-9]{4}) " && answer_sdp "$1" | grep -qE "^c=IN IP4 127\.0\.0\.1"; }
{
  echo "Flexisip local check, $(date -u '+%F %T UTC'), domain $DOMAIN -> 127.0.0.1"
  echo "Flexisip: $(docker run --rm --entrypoint flexisip "$IMAGE" --version 2>/dev/null | grep -m1 -oE 'version: [0-9.]+')"
  echo "PJSUA: PJSIP $("$PJSUA" --version 2>&1 | grep -m1 -oE 'PJ_VERSION +: [0-9.]+' | awk '{print $3}')"
  echo "Certificate: $(openssl x509 -in "$CA" -noout -subject -ext subjectAltName 2>/dev/null | tr '\n' ' ')"
  echo
  echo "[negative]"
  echo "  wrong password:  $(first 'registration (failed|success)|status=40[13]' "$OUT/neg-wrongpass-pjsua.log")"
  echo "  no credentials:  $(first 'is DISCONNECTED' "$OUT/neg-noauth-pjsua.log")"
  echo "  other domain:    $(first 'is DISCONNECTED' "$OUT/neg-otherdomain-pjsua.log")"
  echo
  for tag in a b; do
    [[ $tag == a ]] && echo "[call A: spike settings, ICE + STUN $DOMAIN:3478]" || echo "[call B: ICE off, media only via MediaRelay]"
    for who in robby office; do
      log=$OUT/$tag-$who-pjsua.log
      echo "  $who:"
      echo "    registration: $(first 'registration success' "$log")"
      echo "    TLS:          $(first 'TLS cipher used' "$log")"
      echo "    auth:         $(grep -m1 -oE 'algorithm=[A-Z0-9-]+' "$log" || true)"
      echo "    SRTP:         $(grep -m1 -oE 'SRTP status: [A-Za-z]+[^]]*Crypto-suite: [A-Z0-9_]+' "$log" || true)"
      echo "    ICE:          $(first 'ICE negotiation (success|failed)|ICE.*disabled' "$log")"
      echo "    ICE pair:     $(grep -m1 -E 'nominated, state=Succeeded' "$log" | sed -E 's/^.*\[1\] //' || true)"
      echo "    codec:        $(grep -m1 -oE '(PCMU|PCMA) @[0-9]+kHz' "$log" || true)"
      echo "    disconnect:   $(first 'is DISCONNECTED' "$log")"
    done
    echo "  SDP answer from Flexisip to robby:"
    answer_sdp "$OUT/$tag-robby-pjsua.log" | sed 's/^/    /'
    python3 "$TEST/wavtool.py" analyze "$OUT/$tag-robby-heard.wav" 440 1000 | sed "s#$OUT/##; s/^/  /" || true
    python3 "$TEST/wavtool.py" analyze "$OUT/$tag-office-heard.wav" 1000 440 | sed "s#$OUT/##; s/^/  /" || true
    echo
  done
  if [[ "${CHECK_CERT_RELOAD:-1}" == 1 ]]; then
    echo "[certificate renewal via certbot/deploy-hook.sh]"
    echo "  before: $OLD_FPR"
    echo "  after:  $NEW_FPR (served ${RELOAD_SECS:-?} s after the hook, no restart)"
    echo "  $(cat "$OUT/deploy-hook.log" 2>/dev/null)"
    echo "  register with the new certificate: $(first 'registration success|registration failed' "$OUT/after-reload-pjsua.log")"
    echo
  fi
  echo "[checks]"
  if [[ "${CHECK_CERT_RELOAD:-1}" == 1 ]]; then
    check "renewed certificate served without restart" test -n "$RELOAD_SECS"
    check "registration over renewed certificate"      grep -q "registration success" "$OUT/after-reload-pjsua.log"
  fi
  check "wrong password refused (403)"          grep -qE 'status=403' "$OUT/neg-wrongpass-pjsua.log"
  check "wrong password never registered"        bash -c "! grep -q 'registration success' '$OUT/neg-wrongpass-pjsua.log'"
  check "INVITE without credentials refused"     grep -qE 'DISCONNECTED \[reason=40[37]' "$OUT/neg-noauth-pjsua.log"
  check "call to another domain refused"         grep -qE 'DISCONNECTED \[reason=4[0-9][0-9]' "$OUT/neg-otherdomain-pjsua.log"
  for tag in a b; do
    T=${tag^^}
    check "$T: robby registered (TLS, digest)"   grep -q "registration success" "$OUT/$tag-robby-pjsua.log"
    check "$T: office registered (TLS, digest)"  grep -q "registration success" "$OUT/$tag-office-pjsua.log"
    check "$T: SRTP active (robby)"              grep -q "SRTP status: Active" "$OUT/$tag-robby-pjsua.log"
    check "$T: SRTP active (office)"             grep -q "SRTP status: Active" "$OUT/$tag-office-pjsua.log"
    check "$T: office hears robby (1000 Hz)"     python3 "$TEST/wavtool.py" analyze "$OUT/$tag-office-heard.wav" 1000 440
    check "$T: robby hears office (440 Hz)"      python3 "$TEST/wavtool.py" analyze "$OUT/$tag-robby-heard.wav" 440 1000
    check "$T: robby hung up normally"           grep -qE "DISCONNECTED \[reason=200" "$OUT/$tag-robby-pjsua.log"
    check "$T: office got BYE"                   grep -qE "DISCONNECTED \[reason=200" "$OUT/$tag-office-pjsua.log"
  done
  check "B: SDP answer points to MediaRelay"     relayed "$OUT/b-robby-pjsua.log"
  echo
  [[ $fails -eq 0 ]] && echo "RESULT: PASS" || echo "RESULT: FAIL ($fails)"
} | tee "$OUT/summary.txt"
grep -q "RESULT: PASS" "$OUT/summary.txt"
