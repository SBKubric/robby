#!/bin/sh
# Prepares Flexisip's runtime files from the environment, then runs the proxy
# as the unprivileged user "flexisip".
#   - /run/flexisip/passwd: the two accounts, as digest hashes (no clear text);
#   - /etc/flexisip/tls/{cert,key}.pem: Let's Encrypt (written by the certbot
#     service) or, if there is none yet, a self-signed fallback;
#   - /run/flexisip/flexisip.conf: rendered from flexisip.conf.template.
set -eu

log() { echo "[robby-flexisip] $*"; }
die() { log "ERROR: $*"; exit 1; }

# Passwords: Docker secret file if present, else the environment variable.
secret() { # name, env var
  if [ -s "/run/secrets/$1" ]; then tr -d '\r\n' < "/run/secrets/$1"; else eval "printf '%s' \"\${$2:-}\""; fi
}

SIP_DOMAIN=${SIP_DOMAIN:-}
echo "$SIP_DOMAIN" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$' || die "SIP_DOMAIN is empty or not a host name"
BIND_ADDRESS=${BIND_ADDRESS:-0.0.0.0}
RTP_PORT_MIN=${RTP_PORT_MIN:-40000}
RTP_PORT_MAX=${RTP_PORT_MAX:-40199}
LOG_LEVEL=${FLEXISIP_LOG_LEVEL:-message}
SIP_TCP_DIAG=${SIP_TCP_DIAG:-0}
MEDIARELAY_PREVENT_LOOPS=${MEDIARELAY_PREVENT_LOOPS:-true}

case "$RTP_PORT_MIN$RTP_PORT_MAX" in *[!0-9]*) die "RTP_PORT_MIN/RTP_PORT_MAX must be numbers";; esac
[ "$RTP_PORT_MIN" -ge 1024 ] && [ "$RTP_PORT_MAX" -gt "$RTP_PORT_MIN" ] && [ "$RTP_PORT_MAX" -le 65535 ] \
  || die "bad RTP port range $RTP_PORT_MIN-$RTP_PORT_MAX"
echo "$BIND_ADDRESS" | grep -Eq '^[0-9.]+$' || die "BIND_ADDRESS must be an IPv4 address"
case "$LOG_LEVEL" in debug|message|warning|error) ;; *) die "FLEXISIP_LOG_LEVEL must be debug|message|warning|error";; esac
case "$MEDIARELAY_PREVENT_LOOPS" in true|false) ;; *) die "MEDIARELAY_PREVENT_LOOPS must be true|false";; esac

ROBBY_PASSWORD=$(secret robby_password ROBBY_PASSWORD)
OFFICE_PASSWORD=$(secret office_password OFFICE_PASSWORD)
for pair in "robby:$ROBBY_PASSWORD" "office:$OFFICE_PASSWORD"; do
  user=${pair%%:*}; pass=${pair#*:}
  [ "${#pass}" -ge 16 ] || die "password of '$user' must be at least 16 characters"
  case "$pass" in *change-me*|*CHANGE_ME*) die "password of '$user' is still the placeholder from .env.example";; esac
done

RUN=/run/flexisip
mkdir -p "$RUN"
chmod 0750 "$RUN"

# Accounts: HA1 = H(user:realm:password), realm = SIP domain (see template).
ha1() { printf '%s' "$1:$SIP_DOMAIN:$2" | md5sum | cut -d' ' -f1; }
ha256() { printf '%s' "$1:$SIP_DOMAIN:$2" | sha256sum | cut -d' ' -f1; }
(umask 027
{
  echo "version:1"
  echo "robby@$SIP_DOMAIN md5:$(ha1 robby "$ROBBY_PASSWORD") sha256:$(ha256 robby "$ROBBY_PASSWORD") ;"
  echo "office@$SIP_DOMAIN md5:$(ha1 office "$OFFICE_PASSWORD") sha256:$(ha256 office "$OFFICE_PASSWORD") ;"
} > "$RUN/passwd")
unset ROBBY_PASSWORD OFFICE_PASSWORD pass pair

# TLS certificate.
TLS=/etc/flexisip/tls
mkdir -p "$TLS"
if [ ! -s "$TLS/cert.pem" ] || [ ! -s "$TLS/key.pem" ]; then
  san="DNS:$SIP_DOMAIN"
  [ -n "${SELFSIGNED_EXTRA_SAN:-}" ] && san="$san,$SELFSIGNED_EXTRA_SAN"
  log "WARNING: no certificate in $TLS, generating a SELF-SIGNED one for $SIP_DOMAIN ($san)."
  log "WARNING: clients with certificate verification will refuse it; the certbot service replaces it."
  openssl req -x509 -newkey rsa:2048 -nodes -days 90 -sha256 -subj "/CN=$SIP_DOMAIN" \
    -addext "subjectAltName=$san" -keyout "$TLS/key.pem.tmp" -out "$TLS/cert.pem.tmp" 2>/dev/null
  mv "$TLS/key.pem.tmp" "$TLS/key.pem"
  mv "$TLS/cert.pem.tmp" "$TLS/cert.pem"
  touch "$TLS/SELF-SIGNED"
fi
log "certificate: $(openssl x509 -in "$TLS/cert.pem" -noout -subject -issuer -enddate | tr '\n' ' ')"

EXTRA_TRANSPORTS=""
if [ "$SIP_TCP_DIAG" = 1 ]; then
  EXTRA_TRANSPORTS=" sip:$SIP_DOMAIN:5060;transport=tcp;maddr=$BIND_ADDRESS"
  log "WARNING: SIP over plain TCP on 5060 is ON (diagnostics only; SRTP keys travel in clear text)."
fi

sed -e "s/@SIP_DOMAIN@/$SIP_DOMAIN/g" \
    -e "s/@BIND_ADDRESS@/$BIND_ADDRESS/g" \
    -e "s/@EXTRA_TRANSPORTS@/$EXTRA_TRANSPORTS/g" \
    -e "s/@RTP_PORT_MIN@/$RTP_PORT_MIN/g" \
    -e "s/@RTP_PORT_MAX@/$RTP_PORT_MAX/g" \
    -e "s/@LOG_LEVEL@/$LOG_LEVEL/g" \
    -e "s/@MEDIARELAY_PREVENT_LOOPS@/$MEDIARELAY_PREVENT_LOOPS/g" \
    /etc/flexisip/flexisip.conf.template > "$RUN/flexisip.conf"
grep -Eq '^[^#]*@[A-Z_]+@' "$RUN/flexisip.conf" && die "unrendered placeholder in flexisip.conf"

# Files the unprivileged user needs.
LOGDIR=/var/opt/belledonne-communications/log/flexisip
LIBDIR=/var/opt/belledonne-communications/lib/flexisip
mkdir -p "$LOGDIR" "$LIBDIR"
chown -R flexisip:flexisip "$LOGDIR" "$LIBDIR"
chgrp flexisip "$RUN" "$RUN/passwd" "$RUN/flexisip.conf"
chmod 0640 "$RUN/passwd" "$RUN/flexisip.conf"
chgrp flexisip "$TLS/key.pem" "$TLS/cert.pem"
chmod 0640 "$TLS/key.pem"
chmod 0644 "$TLS/cert.pem"

log "domain $SIP_DOMAIN, TLS on $BIND_ADDRESS:5061, STUN on 3478/udp, media relay ports $RTP_PORT_MIN-$RTP_PORT_MAX/udp"
# shellcheck disable=SC2086  # FLEXISIP_EXTRA_ARGS is a list of options
exec setpriv --reuid=flexisip --regid=flexisip --init-groups --inh-caps=-all \
  flexisip --server proxy -c "$RUN/flexisip.conf" ${FLEXISIP_EXTRA_ARGS:-} "$@"
