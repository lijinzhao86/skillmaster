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
# **Why the zone's own nameservers and not a public resolver.** A resolver that has just answered
# NXDOMAIN for the name keeps saying so for the zone's negative TTL, which is minutes — longer than
# a renewal waits. The delegation is looked up rather than written down: Aliyun assigns the pair per
# zone, and a hardcoded one stops matching the day the zone moves.
set -eu
. /etc/skillmaster/acme.env

# Exported rather than passed as flags: an argument list is readable in /proc by every local user.
export ALIBABA_CLOUD_ACCESS_KEY_ID="$ALIYUN_ACCESS_KEY_ID"
export ALIBABA_CLOUD_ACCESS_KEY_SECRET="$ALIYUN_ACCESS_KEY_SECRET"
export ALIBABA_CLOUD_REGION_ID=cn-hangzhou

# The flag as well as the variable: whether this CLI version honours ALIBABA_CLOUD_REGION_ID is
# a detail we do not need to depend on, and an explicit region costs nothing.

ZONE=skillmasterai.com
case "$CERTBOT_DOMAIN" in
  "$ZONE")    RR="_acme-challenge" ;;                                   # the apex: no subdomain part
  *".$ZONE")  RR="_acme-challenge.${CERTBOT_DOMAIN%.$ZONE}" ;;
  *) echo "auth.sh: $CERTBOT_DOMAIN is not under $ZONE" >&2; exit 1 ;;
esac

aliyun alidns AddDomainRecord \
  --DomainName "$ZONE" --RR "$RR" --Type TXT --Value "$CERTBOT_VALIDATION" \
  --region cn-hangzhou > /dev/null

# The NS records themselves are delegated by the parent zone and change rarely, so reading them
# does not suffer the negative-caching problem that made public resolvers unusable here.
nameservers=$(dig +short NS "$ZONE" 2>/dev/null | sed 's/\.$//' | head -2)
if [ -z "$nameservers" ]; then
  echo "auth.sh: cannot resolve the nameservers for $ZONE — is dnsutils installed?" >&2
  exit 1
fi

i=0
while [ "$i" -lt 60 ]; do
  for resolver in $nameservers; do
    if dig +short +time=3 +tries=1 "@$resolver" TXT "_acme-challenge.$CERTBOT_DOMAIN" 2>/dev/null |
         grep -qF "$CERTBOT_VALIDATION"; then
      exit 0
    fi
  done
  i=$((i + 1))
  sleep 2
done
echo "auth.sh: no nameserver for $ZONE answers the TXT record for $CERTBOT_DOMAIN" >&2
exit 1
