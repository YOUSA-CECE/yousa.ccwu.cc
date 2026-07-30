#!/data/data/com.termux/files/usr/bin/bash
set -eu

HOME=/data/data/com.termux/files/home
TOKEN_FILE="$HOME/.cloudflare-ddns-token"
LOG_FILE="$HOME/cloudflare-ddns.log"
ZONE_NAME="yousa.ccwu.cc"
RECORD_NAME="direct.yousa.ccwu.cc"
API="https://api.cloudflare.com/client/v4"

log() {
    printf '[%s] %s\n' "$(date '+%F %T %Z')" "$*" >>"$LOG_FILE"
}

TOKEN="$(cat "$TOKEN_FILE")"
IPV6="$(
    /system/bin/ip -6 addr show dev wlan0 scope global |
    awk '/inet6/ && !/temporary/ && !/deprecated/ {
        split($2, address, "/")
        print address[1]
        exit
    }'
)"

if [ -z "$IPV6" ]; then
    log "No stable global IPv6 address found"
    exit 1
fi

api_get() {
    curl -fsS \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        "$1"
}

ZONE_JSON="$(api_get "$API/zones?name=$ZONE_NAME")"
ZONE_ID="$(
    printf '%s' "$ZONE_JSON" |
    python3 -c 'import json,sys; data=json.load(sys.stdin); print(data["result"][0]["id"])'
)"

RECORD_JSON="$(api_get "$API/zones/$ZONE_ID/dns_records?type=AAAA&name=$RECORD_NAME")"
RECORD_ID="$(
    printf '%s' "$RECORD_JSON" |
    python3 -c 'import json,sys; data=json.load(sys.stdin); print(data["result"][0]["id"] if data["result"] else "")'
)"

PAYLOAD="$(printf '{"type":"AAAA","name":"%s","content":"%s","ttl":120,"proxied":false}' "$RECORD_NAME" "$IPV6")"

if [ -n "$RECORD_ID" ]; then
    RESULT="$(
        curl -fsS -X PUT \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            --data "$PAYLOAD" \
            "$API/zones/$ZONE_ID/dns_records/$RECORD_ID"
    )"
else
    RESULT="$(
        curl -fsS -X POST \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            --data "$PAYLOAD" \
            "$API/zones/$ZONE_ID/dns_records"
    )"
fi

SUCCESS="$(
    printf '%s' "$RESULT" |
    python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("success", False)).lower())'
)"

if [ "$SUCCESS" = "true" ]; then
    log "Updated $RECORD_NAME to $IPV6"
else
    log "Cloudflare update failed"
    exit 1
fi
