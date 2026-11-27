#!/bin/sh
# Let's Encrypt for the SIP domain: HTTP-01 on port 80 (certbot standalone),
# then a renewal check every 12 hours. Each new certificate is copied to the
# shared tls/ directory by deploy-hook.sh; Flexisip notices the change within
# a minute (tls-certificates-check-interval) and loads it without a restart.
set -eu

: "${SIP_DOMAIN:?set SIP_DOMAIN}"
: "${LE_EMAIL:?set LE_EMAIL (Let's Encrypt account e-mail)}"
STAGING=""
[ "${LE_STAGING:-0}" = 1 ] && STAGING="--test-cert"

HOOK=/scripts/deploy-hook.sh

certbot certonly --standalone --non-interactive --agree-tos --no-eff-email \
  -m "$LE_EMAIL" -d "$SIP_DOMAIN" --keep-until-expiring $STAGING \
  --key-type rsa --rsa-key-size 2048 --deploy-hook "sh $HOOK" || echo "[certbot] issuance failed; retrying in 1 hour" >&2

# The certificate may already exist (container recreated): make sure Flexisip has it.
if [ -s "/etc/letsencrypt/live/$SIP_DOMAIN/fullchain.pem" ]; then
  RENEWED_LINEAGE="/etc/letsencrypt/live/$SIP_DOMAIN" sh "$HOOK"
  INTERVAL=12h
else
  INTERVAL=1h
fi

while :; do
  sleep "$INTERVAL"
  if [ -s "/etc/letsencrypt/live/$SIP_DOMAIN/fullchain.pem" ]; then
    certbot renew --non-interactive --deploy-hook "sh $HOOK" || echo "[certbot] renew failed" >&2
    INTERVAL=12h
  else
    certbot certonly --standalone --non-interactive --agree-tos --no-eff-email \
      -m "$LE_EMAIL" -d "$SIP_DOMAIN" $STAGING --key-type rsa --rsa-key-size 2048 --deploy-hook "sh $HOOK" \
      && RENEWED_LINEAGE="/etc/letsencrypt/live/$SIP_DOMAIN" sh "$HOOK" \
      || echo "[certbot] issuance failed; retrying in 1 hour" >&2
  fi
done
