#!/bin/sh
# Removes the TXT record auth.sh published.
#
# A failure here does not fail the certificate, so it does not abort — but it **does** say so:
# records left behind are what makes a later validation read a stale answer, and a hook that fails
# silently every time would look exactly like one that works.
set -u
. /etc/skillmaster/acme.env

export ALIBABA_CLOUD_ACCESS_KEY_ID="$ALIYUN_ACCESS_KEY_ID"
export ALIBABA_CLOUD_ACCESS_KEY_SECRET="$ALIYUN_ACCESS_KEY_SECRET"
export ALIBABA_CLOUD_REGION_ID=cn-hangzhou

ZONE=skillmasterai.com
case "$CERTBOT_DOMAIN" in
  "$ZONE")    RR="_acme-challenge" ;;
  *".$ZONE")  RR="_acme-challenge.${CERTBOT_DOMAIN%.$ZONE}" ;;
  *) echo "cleanup.sh: $CERTBOT_DOMAIN is not under $ZONE" >&2; exit 0 ;;
esac

# `--RRKeyWord` is a substring match on the API side, so it is only a pre-filter: the apex keyword
# `_acme-challenge` also matches `_acme-challenge.www` and `_acme-challenge.dl`, and this hook must
# not delete a record it did not create.
if ! listing=$(aliyun alidns DescribeDomainRecords --DomainName "$ZONE" --RRKeyWord "$RR" 2>&1); then
  echo "cleanup.sh: DescribeDomainRecords failed: $listing" >&2
  exit 0
fi

ids=$(printf '%s' "$listing" | RR="$RR" python3 -c '
import json, os, sys
try:
    records = json.load(sys.stdin)["DomainRecords"]["Record"]
except Exception as error:
    print(f"cleanup.sh: cannot read the record list: {error}", file=sys.stderr)
    raise SystemExit(0)
for r in records:
    if r.get("Type") == "TXT" and r.get("RR") == os.environ["RR"]:
        print(r["RecordId"])
')

for id in $ids; do
  if ! out=$(aliyun alidns DeleteDomainRecord --RecordId "$id" 2>&1); then
    echo "cleanup.sh: deleting $id failed: $out" >&2
  fi
done
exit 0
