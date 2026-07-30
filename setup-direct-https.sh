#!/data/data/com.termux/files/usr/bin/bash
set -eu

HOME=/data/data/com.termux/files/home
DOMAIN=direct.yousa.ccwu.cc

if [ ! -x "$HOME/.acme.sh/acme.sh" ]; then
    curl -fsSL https://get.acme.sh |
        sh -s email=1161137549@qq.com
fi

export CF_Token="$(cat "$HOME/.cloudflare-ddns-token")"

"$HOME/.acme.sh/acme.sh" \
    --issue \
    --dns dns_cf \
    --domain "$DOMAIN" \
    --server letsencrypt \
    --keylength ec-256

mkdir -p "$HOME/.ssl"
"$HOME/.acme.sh/acme.sh" \
    --install-cert \
    --ecc \
    --domain "$DOMAIN" \
    --key-file "$HOME/.ssl/direct.yousa.ccwu.cc.key" \
    --fullchain-file "$HOME/.ssl/direct.yousa.ccwu.cc.fullchain.pem"

chmod 600 "$HOME/.ssl/direct.yousa.ccwu.cc.key"
