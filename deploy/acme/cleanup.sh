#!/bin/sh
# Removes the TXT record auth.sh published. A failure here does not fail the certificate, so it
# only reports: another run's records would otherwise accumulate and confuse a later validation.
set -u
. /etc/skillmaster/acme.env

ZONE=skillmasterai.com
case "$CERTBOT_DOMAIN" in
  "$ZONE")    RR="_acme-challenge" ;;
  *".$ZONE")  RR="_acme-challenge.${CERTBOT_DOMAIN%.$ZONE}" ;;
  *) echo "cleanup.sh: $CERTBOT_DOMAIN is not under $ZONE" >&2; exit 0 ;;
esac

ids=$(aliyun alidns DescribeDomainRecords --DomainName "$ZONE" --RRKeyWord "$RR" \
        --access-key-id "$ALIYUN_ACCESS_KEY_ID" \
        --access-key-secret "$ALIYUN_ACCESS_KEY_SECRET" \
        --region cn-hangzhou 2>/dev/null |
      python3 -c '
import json, sys
try:
    records = json.load(sys.stdin)["DomainRecords"]["Record"]
except Exception:
    raise SystemExit(0)
for r in records:
    if r.get("Type") == "TXT":
        print(r["RecordId"])
')

for id in $ids; do
  aliyun alidns DeleteDomainRecord --RecordId "$id" \
    --access-key-id "$ALIYUN_ACCESS_KEY_ID" \
    --access-key-secret "$ALIYUN_ACCESS_KEY_SECRET" \
    --region cn-hangzhou > /dev/null || true
done
exit 0
