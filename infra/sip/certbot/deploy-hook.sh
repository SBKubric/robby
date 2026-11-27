#!/bin/sh
# certbot deploy hook: copy the new certificate into the directory Flexisip
# reads (/tls in this container, /etc/flexisip/tls in Flexisip's). The key goes
# to group 5061 (the "flexisip" user in that image), mode 0640.
set -eu
src=${RENEWED_LINEAGE:?}
dst=/tls
umask 077
cp "$src/privkey.pem" "$dst/key.pem.new"
cp "$src/fullchain.pem" "$dst/cert.pem.new"
chown 0:5061 "$dst/key.pem.new" "$dst/cert.pem.new"
chmod 0640 "$dst/key.pem.new"
chmod 0644 "$dst/cert.pem.new"
mv -f "$dst/key.pem.new" "$dst/key.pem"
mv -f "$dst/cert.pem.new" "$dst/cert.pem"
rm -f "$dst/SELF-SIGNED"
echo "[certbot] certificate for $(basename "$src") installed for Flexisip"
