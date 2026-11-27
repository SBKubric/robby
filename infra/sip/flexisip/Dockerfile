# syntax=docker/dockerfile:1
#
# Flexisip from Belledonne Communications' official Debian packages.
#
# Belledonne publishes no ready Flexisip image: the official container registry
# (gitlab.linphone.org:4567/bc/public/flexisip) has no tagged image, and the
# Docker Hub "flexisip" images are third-party. The official apt repository is
# https://download.linphone.org/snapshots/debian (component "stable"; the
# upstream docker/flex-from-deb recipe also installs from these .deb files).
#
# Pinned: Debian base by digest, Flexisip package by exact version, the
# repository signing keys by fingerprint.

FROM debian:trixie-slim@sha256:8aa5a7bf5a1955bddbe7d74fcdd0b91ab30e74362a8b73af672b0481b35b44a0

ARG FLEXISIP_VERSION=2.6.2-1
# Primary keys in https://download.linphone.org/snapshots/debian/keyring.gpg
# (checked 2026-11-27); the build fails if the keyring holds anything else:
#   BD50F919A18BF76E078F24A6C89D15DD115B34BD  Belledonne Communications (2015)
#   680FF8D8785C05258C627D8696BFCE501E9E4831  Belledonne Communications key 1 (2025)
ARG BC_KEY_FINGERPRINTS="BD50F919A18BF76E078F24A6C89D15DD115B34BD 680FF8D8785C05258C627D8696BFCE501E9E4831"

# An optional build secret "extra_ca" adds a CA certificate for the duration of
# this step only (for builds behind a TLS-inspecting proxy). Nothing of it stays
# in the image. Production builds do not pass it.
RUN --mount=type=secret,id=extra_ca,required=false \
    set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl gpg; \
    if [ -s /run/secrets/extra_ca ]; then \
        cp /run/secrets/extra_ca /usr/local/share/ca-certificates/build-extra-ca.crt; \
        update-ca-certificates; \
    fi; \
    curl -fsSL https://download.linphone.org/snapshots/debian/keyring.gpg -o /usr/share/keyrings/belledonne.gpg; \
    got="$(gpg --show-keys --with-colons /usr/share/keyrings/belledonne.gpg \
        | awk -F: '$1=="pub"{p=1; next} $1=="fpr" && p {print $10; p=0}' | sort -u | tr '\n' ' ')"; \
    for fpr in $BC_KEY_FINGERPRINTS; do echo "$got" | grep -qw "$fpr"; done; \
    for fpr in $got; do echo " $BC_KEY_FINGERPRINTS " | grep -qw "$fpr"; done; \
    echo "deb [signed-by=/usr/share/keyrings/belledonne.gpg] https://download.linphone.org/snapshots/debian trixie stable" \
        > /etc/apt/sources.list.d/belledonne.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends "bc-flexisip=${FLEXISIP_VERSION}" openssl; \
    apt-get purge -y --auto-remove curl gpg; \
    rm -f /usr/local/share/ca-certificates/build-extra-ca.crt; \
    update-ca-certificates --fresh >/dev/null; \
    rm -rf /var/lib/apt/lists/*

ENV PATH=/opt/belledonne-communications/bin:$PATH

# Flexisip runs unprivileged. Fixed ids: the certbot hook gives the key to gid 5061.
RUN groupadd --system --gid 5061 flexisip \
 && useradd --system --uid 5061 --gid 5061 --home-dir /nonexistent --shell /usr/sbin/nologin flexisip \
 && chgrp flexisip /etc/flexisip && chmod 0750 /etc/flexisip

COPY flexisip.conf.template /etc/flexisip/flexisip.conf.template
COPY entrypoint.sh /usr/local/bin/robby-flexisip-entrypoint
RUN chmod 0755 /usr/local/bin/robby-flexisip-entrypoint

ENTRYPOINT ["/usr/local/bin/robby-flexisip-entrypoint"]
