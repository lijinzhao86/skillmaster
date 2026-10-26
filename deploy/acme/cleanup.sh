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

# The flag as well as the variable: whether this CLI version honours ALIBABA_CLOUD_REGION_ID is
# a detail we do not need to depend on, and an explicit region costs nothing.

ZONE=skillmasterai.com
case "$CERTBOT_DOMAIN" in
  "$ZONE")    RR="_acme-challenge" ;;
  *".$ZONE")  RR="_acme-challenge.${CERTBOT_DOMAIN%.$ZONE}" ;;
  *) echo "cleanup.sh: $CERTBOT_DOMAIN is not under $ZONE" >&2; exit 0 ;;
esac

# `--RRKeyWord` is a substring match on the API side, so it is only a pre-filter: the apex keyword
# `_acme-challenge` also matches `_acme-challenge.www` and `_acme-challenge.dl`, and this hook must
# not delete a record it did not create.
# stderr kept apart: the CLI prints routine notices (a version-upgrade line is the common one), and
# folding them into the JSON makes the parse fail on every renewal — a cleanup that silently does
# nothing, which is the state this hook exists to report.
if ! listing=$(aliyun alidns DescribeDomainRecords --DomainName "$ZONE" --RRKeyWord "$RR" --region cn-hangzhou 2>/tmp/cleanup.err); then
  echo "cleanup.sh: DescribeDomainRecords failed: $(cat /tmp/cleanup.err)" >&2
  exit 0
fi
rm -f /tmp/cleanup.err

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
  if ! out=$(aliyun alidns DeleteDomainRecord --RecordId "$id" --region cn-hangzhou 2>&1); then
    echo "cleanup.sh: deleting $id failed: $out" >&2
  fi
done
exit 0
