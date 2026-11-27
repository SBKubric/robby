#!/usr/bin/env bash
# Local check of the spike's SIP settings against Asterisk in Docker (#17).
#
# Two Linux pjsua clients (PJSIP 2.18, the spike's settings: SIP over TLS with
# server certificate verification, mandatory SDES-SRTP, ICE, G.711 only, 20 ms,
# no VAD/EC, null audio device) register as `robby` and `office`; robby calls
# office, office auto-answers. robby plays a 1000 Hz tone, office a 440 Hz
# tone, each side records what it hears; the recordings are then checked for
# the far side's tone. robby hangs up after CALL_SECS seconds.
#
# Usage: PJSUA=/path/to/pjsua ./run-check.sh      (KEEP=1 leaves Asterisk running)
# Output: out/summary.txt, out/*-pjsua.log, out/*-heard.wav, out/asterisk.log
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
GEN=$HERE/generated
OUT=$HERE/out
PJSUA=${PJSUA:-$(command -v pjsua || true)}
SIP_HOST=${SIP_HOST:-127.0.0.1}   # where the clients connect (cert has IP SAN 127.0.0.1)
SIP_DOMAIN=${SIP_DOMAIN:-sip.test}
CALL_SECS=${CALL_SECS:-12}
KEEP=${KEEP:-0}

if [[ -z "$PJSUA" || ! -x "$PJSUA" ]]; then
  echo "pjsua not found: set PJSUA=/path/to/pjsua (./build-pjsua-linux.sh builds PJSIP 2.18 with OpenSSL)" >&2
  exit 2
fi
for tool in docker openssl python3; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 2; }
done

compose() { docker compose -f "$HERE/docker-compose.yml" "$@"; }
ast() { compose exec -T asterisk asterisk -rx "$1"; }
PIDS=()
cleanup() {
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
  if [[ "$KEEP" != 1 ]]; then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

rm -rf "$OUT"
mkdir -p "$OUT" "$GEN"

# 1. Certificates and random passwords (nothing secret is committed).
"$HERE/gen-cert.sh" "$GEN/certs" >/dev/null
chown 1000 "$GEN/certs/server.key" 2>/dev/null || chmod 644 "$GEN/certs/server.key"  # Asterisk runs as uid 1000
ROBBY_PASSWORD=$(openssl rand -hex 16)
OFFICE_PASSWORD=$(openssl rand -hex 16)
sed -e "s/@ROBBY_PASSWORD@/$ROBBY_PASSWORD/" -e "s/@OFFICE_PASSWORD@/$OFFICE_PASSWORD/" \
  "$HERE/asterisk/pjsip.conf.template" > "$GEN/pjsip.conf"
chmod 644 "$GEN/pjsip.conf"
python3 "$HERE/wavtool.py" tone "$GEN/robby-tone.wav" 1000 60
python3 "$HERE/wavtool.py" tone "$GEN/office-tone.wav" 440 60

# 2. Asterisk.
compose up -d --force-recreate >/dev/null
for _ in $(seq 1 60); do
  if ast "pjsip show transports" 2>/dev/null | grep -q transport-tls; then break; fi
  sleep 1
done
ast "pjsip show transports" | grep -q transport-tls || { compose logs > "$OUT/asterisk.log"; echo "Asterisk did not start, see out/asterisk.log" >&2; exit 1; }
ast "core show version" > "$OUT/asterisk-version.txt"

# 3. The spike's settings, as pjsua options.
COMMON=(
  --null-audio --clock-rate=8000 --snd-clock-rate=8000 --ptime=20 --no-vad --ec-tail=0
  --use-tls --no-udp --tls-ca-file="$GEN/certs/ca.crt" --tls-verify-server
  --use-srtp=2 --srtp-secure=1 --srtp-keying=0
  --use-ice
  --dis-codec=speex --dis-codec=iLBC --dis-codec=GSM --dis-codec=G722 --dis-codec=L16 --dis-codec=opus
  --registrar="sip:$SIP_HOST:5061;transport=tls"
  --proxy="sip:$SIP_HOST:5061;transport=tls;lr"
  --realm='*' --reg-timeout=300 --max-calls=1
  --log-level=5 --app-log-level=3 --no-color
)

# office: auto-answer, plays 440 Hz, records what it hears.
OFFICE_LIFETIME=$((CALL_SECS + 30))
( sleep $((CALL_SECS / 2 + 10)); echo dq; sleep $((OFFICE_LIFETIME - CALL_SECS / 2 - 10)); echo q ) |
  "$PJSUA" "${COMMON[@]}" --id="sip:office@$SIP_DOMAIN" --username=office --password="$OFFICE_PASSWORD" \
    --local-port=6070 --rtp-port=4100 --auto-answer=200 \
    --play-file="$GEN/office-tone.wav" --auto-play --rec-file="$OUT/office-heard.wav" --auto-rec \
    --log-file="$OUT/office-pjsua.log" > "$OUT/office-console.log" 2>&1 &
PIDS+=($!)
OFFICE_PID=$!

for _ in $(seq 1 20); do
  grep -q "registration success" "$OUT/office-pjsua.log" 2>/dev/null && break
  sleep 0.5
done

# robby: registers, calls office, plays 1000 Hz, records, hangs up after CALL_SECS.
( sleep $((CALL_SECS / 2 + 3)); echo dq; sleep $((CALL_SECS / 2 + 8)); echo q ) |
  "$PJSUA" "${COMMON[@]}" --id="sip:robby@$SIP_DOMAIN" --username=robby --password="$ROBBY_PASSWORD" \
    --local-port=6060 --rtp-port=4000 --duration="$CALL_SECS" \
    --play-file="$GEN/robby-tone.wav" --auto-play --rec-file="$OUT/robby-heard.wav" --auto-rec \
    --log-file="$OUT/robby-pjsua.log" "sip:office@$SIP_DOMAIN" > "$OUT/robby-console.log" 2>&1 &
PIDS+=($!)
ROBBY_PID=$!

# 4. While the call is up: Asterisk's view of it.
for _ in $(seq 1 30); do
  grep -q "CONFIRMED" "$OUT/robby-pjsua.log" 2>/dev/null && break
  sleep 0.5
done
sleep 3
{
  echo "== core show channels verbose"; ast "core show channels verbose"
  echo "== pjsip show channelstats"; ast "pjsip show channelstats"
  echo "== pjsip show contacts"; ast "pjsip show contacts"
} > "$OUT/asterisk-during-call.txt" 2>&1 || true

wait "$ROBBY_PID" || true
wait "$OFFICE_PID" || true
compose logs --no-color > "$OUT/asterisk.log" 2>&1 || true

# 5. Verdict.
fails=0
check() { # name, command...
  local name=$1; shift
  if "$@" >/dev/null 2>&1; then echo "OK    $name"; else echo "FAIL  $name"; fails=$((fails + 1)); fi
}
first() { grep -m1 -E "$1" "$2" 2>/dev/null | sed -E 's/^[0-9:.]+ +//' || true; }
{
  echo "Local SIP check, $(date -u '+%F %T UTC')"
  echo "PJSUA: PJSIP $("$PJSUA" --version 2>&1 | grep -m1 -oE 'PJ_VERSION +: [0-9.]+' | awk '{print $3}')"
  echo "Asterisk: $(head -1 "$OUT/asterisk-version.txt")"
  echo
  for who in robby office; do
    log=$OUT/$who-pjsua.log
    echo "[$who]"
    echo "  registration: $(first 'registration success' "$log")"
    echo "  TLS:          $(first 'SIP TLS transport is connected' "$log"); $(first 'TLS cipher used' "$log")"
    echo "  SRTP:         $(grep -m1 -oE 'SRTP status: [A-Za-z]+[^]]*Crypto-suite: [A-Z0-9_]+' "$log" || true)"
    echo "  ICE:          $(first 'ICE negotiation success' "$log")"
    echo "  codec:        $(grep -m1 -oE '(PCMU|PCMA) @[0-9]+kHz' "$log" || true)"
    echo "  disconnect:   $(first 'is DISCONNECTED' "$log")"
    echo
  done
  echo "[asterisk, mid-call: media anchored, packets counted by Asterisk]"
  grep -E '^ +[0-9a-f]{8} ' "$OUT/asterisk-during-call.txt" || true
  echo
  echo "[audio]"
  python3 "$HERE/wavtool.py" analyze "$OUT/robby-heard.wav" 440 1000 || true
  python3 "$HERE/wavtool.py" analyze "$OUT/office-heard.wav" 1000 440 || true
  echo
  echo "[checks]"
  check "robby registered over TLS"            grep -q "registration success" "$OUT/robby-pjsua.log"
  check "office registered over TLS"           grep -q "registration success" "$OUT/office-pjsua.log"
  check "call confirmed (robby)"               grep -q "CONFIRMED" "$OUT/robby-pjsua.log"
  check "SRTP active (robby)"                  grep -q "SRTP status: Active" "$OUT/robby-pjsua.log"
  check "SRTP active (office)"                 grep -q "SRTP status: Active" "$OUT/office-pjsua.log"
  check "media relayed by Asterisk (both legs)" test "$(grep -cE '^ +[0-9a-f]{8} .* (ulaw|alaw) ' "$OUT/asterisk-during-call.txt")" -eq 2
  check "office hears robby (1000 Hz)"        python3 "$HERE/wavtool.py" analyze "$OUT/office-heard.wav" 1000 440
  check "robby hears office (440 Hz)"          python3 "$HERE/wavtool.py" analyze "$OUT/robby-heard.wav" 440 1000
  check "robby hung up normally"               grep -qE "DISCONNECTED \[reason=200" "$OUT/robby-pjsua.log"
  check "office got BYE"                       grep -qE "DISCONNECTED \[reason=200" "$OUT/office-pjsua.log"
  echo
  [[ $fails -eq 0 ]] && echo "RESULT: PASS" || echo "RESULT: FAIL ($fails)"
} | tee "$OUT/summary.txt"
grep -q "RESULT: PASS" "$OUT/summary.txt"
