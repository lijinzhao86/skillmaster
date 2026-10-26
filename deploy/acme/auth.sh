#!/bin/sh
# Publishes one DNS-01 challenge answer for certbot.
#
# Installed as `/etc/skillmaster/acme/auth.sh`; the certificate's renewal configuration names it,
# so a `certbot renew` re-runs it without anybody remembering to. Credentials come from
# `/etc/skillmaster/acme.env` (`0600 root`): a RAM user whose only rights are `alidns:Describe…`,
# `AddDomainRecord` and `DeleteDomainRecord`.
#
# **Why DNS and not port 80.** Every HTTP-01 request from outside this host is answered `403` by
# Aliyun's ICP check before it reaches nginx — `console.`, `www.` and the apex alike — so
# `standalone` and `webroot` cannot complete a challenge here (2026-10-26). DNS-01 does not need an
# inbound port at all.
#
# **Why the nameservers and not a public resolver.** A resolver that has just answered NXDOMAIN for
# the name keeps saying so for the zone's negative TTL, which is minutes — longer than a renewal
# waits. The zone's own nameservers have the record the moment the API write lands.
set -eu
. /etc/skillmaster/acme.env

ZONE=skillmasterai.com
case "$CERTBOT_DOMAIN" in
  "$ZONE")    RR="_acme-challenge" ;;                                   # the apex: no subdomain part
  *".$ZONE")  RR="_acme-challenge.${CERTBOT_DOMAIN%.$ZONE}" ;;
  *) echo "auth.sh: $CERTBOT_DOMAIN is not under $ZONE" >&2; exit 1 ;;
esac

aliyun alidns AddDomainRecord \
  --DomainName "$ZONE" --RR "$RR" --Type TXT --Value "$CERTBOT_VALIDATION" \
  --access-key-id "$ALIYUN_ACCESS_KEY_ID" \
  --access-key-secret "$ALIYUN_ACCESS_KEY_SECRET" \
  --region cn-hangzhou > /dev/null

i=0
while [ "$i" -lt 60 ]; do
  for resolver in dns17.hichina.com dns18.hichina.com; do
    if dig +short +time=3 +tries=1 "@$resolver" TXT "_acme-challenge.$CERTBOT_DOMAIN" 2>/dev/null |
         grep -qF "$CERTBOT_VALIDATION"; then
      exit 0
    fi
  done
  i=$((i + 1))
  sleep 2
done
echo "auth.sh: no nameserver answers the TXT record for $CERTBOT_DOMAIN" >&2
exit 1
