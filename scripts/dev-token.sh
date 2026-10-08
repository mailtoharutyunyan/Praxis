#!/usr/bin/env bash
# Mints a short-lived RS256 JWT for local development against the `local` profile.
# Creates the developer key pair in .dev/ on first use (git-ignored; never use it outside your machine).
#
#   scripts/dev-token.sh [subject] [roles]       e.g. scripts/dev-token.sh alice viewer,operator,approver
set -euo pipefail
cd "$(dirname "$0")/.."
SUBJECT="${1:-dev-user}"
ROLES="${2:-viewer,operator,approver}"
KEY=.dev/jwt-private.pem
PUB=.dev/jwt-public.pem

if [ ! -f "$KEY" ]; then
  mkdir -p .dev
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY" 2>/dev/null
  openssl rsa -in "$KEY" -pubout -out "$PUB" 2>/dev/null
  chmod 600 "$KEY"
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
roles_json=$(printf '%s' "$ROLES" | jq -R 'split(",")')
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
payload=$(jq -cn --arg sub "$SUBJECT" --argjson roles "$roles_json" --argjson iat "$now" \
  '{sub: $sub, roles: $roles, iat: $iat, exp: ($iat + 3600), iss: "agentic-sdlc-dev"}' | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$KEY" | b64url)
printf '%s.%s.%s\n' "$header" "$payload" "$signature"
