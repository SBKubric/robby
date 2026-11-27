#!/usr/bin/env bash
# Self-signed test PKI for the local SIP check: a throwaway CA and a server
# certificate for 127.0.0.1 / localhost / sip.test, signed by that CA.
# Output: <dir>/ca.crt, <dir>/server.crt, <dir>/server.key (never committed).
set -euo pipefail
OUT=${1:-"$(cd "$(dirname "$0")" && pwd)/generated/certs"}
mkdir -p "$OUT"
cd "$OUT"
if [[ -s server.crt && -s server.key && -s ca.crt ]]; then
  echo "certs already in $OUT"
  exit 0
fi
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -sha256 \
  -subj "/CN=Robby local SIP test CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -keyout ca.key -out ca.crt 2>/dev/null
openssl req -newkey rsa:2048 -nodes -sha256 -subj "/CN=sip.test" \
  -keyout server.key -out server.csr 2>/dev/null
cat > server.ext <<'EOF'
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:sip.test,DNS:localhost,IP:127.0.0.1
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 30 -sha256 -extfile server.ext -out server.crt 2>/dev/null
rm -f server.csr server.ext ca.srl ca.key   # the CA key is not needed after signing
chmod 644 ca.crt server.crt
chmod 640 server.key
echo "certs written to $OUT"
openssl x509 -in server.crt -noout -subject -ext subjectAltName
