#!/usr/bin/env bash
# Interactive setup for this deploy directory:
#   GitHub App ID -> config/config.conf
#   secrets/github-app.pem, secrets/github-webhook-secret, secrets/discord-token
#   ownership and permissions for data/ and secrets/ (the container runs as uid 10001)
#   optionally starts the service and waits for /health
#
# Usage, in the deploy directory:  sudo ./setup.sh
# Safe to re-run: press Enter to keep existing values (also fixes permissions).
set -euo pipefail

SERVICE_UID=10001
PORT=33444
SCRIPT=$(readlink -f "$0")
cd "$(dirname "$SCRIPT")"

if [ "$(id -u)" -ne 0 ]; then
  echo "setup.sh needs root to set file ownership; re-running with sudo..."
  exec sudo -- bash "$SCRIPT" "$@"
fi

CONFIG=config/config.conf
KEY=secrets/github-app.pem
HOOK=secrets/github-webhook-secret
TOKEN=secrets/discord-token
USER_HOME=$(getent passwd "${SUDO_USER:-root}" | cut -d: -f6)

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mOK\033[0m  %s\n' "$*"; }
warn() { printf '  \033[33m!!\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mERROR\033[0m  %s\n' "$*" >&2; exit 1; }

# ask_yes "Question" Y|N  -> exit status 0 for yes
ask_yes() {
  local answer hint="[y/N]"
  [ "$2" = Y ] && hint="[Y/n]"
  read -rp "  $1 $hint " answer
  answer=${answer:-$2}
  [[ $answer =~ ^[Yy] ]]
}

# keep_existing FILE LABEL -> exit status 0 when the existing file should be kept
keep_existing() {
  [ -s "$1" ] || return 1
  ask_yes "$2 already exists. Replace it?" N && return 1
  ok "Keeping existing $1"
  return 0
}

# write_secret FILE VALUE: written without a trailing newline, never echoed
write_secret() {
  (umask 077 && printf '%s' "$2" > "$1")
}

read_hidden() {
  local value
  read -rsp "  $1: " value
  echo >&2
  printf '%s' "$value"
}

expand_path() {
  local p=$1
  [[ $p == "~/"* ]] && p="$USER_HOME/${p#\~/}"
  printf '%s' "$p"
}

[ -f "$CONFIG" ] || fail "$CONFIG not found. Run this script from the deploy directory of the project."
mkdir -p data secrets

# ---- 1. GitHub App ID -------------------------------------------------------
bold "1/5  GitHub App ID  (GitHub App settings page -> About -> App ID)"
current=$(sed -n 's/^github\.appId=//p' "$CONFIG" | tr -d '[:space:]')
while true; do
  read -rp "  App ID${current:+ [$current]}: " app_id
  app_id=${app_id:-$current}
  [[ $app_id =~ ^[0-9]+$ ]] && break
  warn "The App ID is a number, for example 1234567."
done
if grep -q '^github\.appId=' "$CONFIG"; then
  sed -i "s/^github\.appId=.*/github.appId=$app_id/" "$CONFIG"
else
  printf '\ngithub.appId=%s\n' "$app_id" >> "$CONFIG"
fi
ok "github.appId=$app_id"

# ---- 2. GitHub App private key ---------------------------------------------
bold "2/5  GitHub App private key  (the .pem file from 'Generate a private key')"
if ! keep_existing "$KEY" "The private key"; then
  found=$(find "$USER_HOME" "$PWD" -maxdepth 2 -name '*.private-key.pem' -type f 2>/dev/null | head -n 1 || true)
  while true; do
    read -rp "  Path to the .pem file${found:+ [$found]}: " pem
    pem=$(expand_path "${pem:-$found}")
    if [ ! -f "$pem" ]; then
      warn "File not found: ${pem:-<empty>}. Copy the .pem to this server first (for example with scp)."
    elif ! grep -q 'PRIVATE KEY-----' "$pem"; then
      warn "$pem does not look like a private key (.pem)."
    else
      break
    fi
  done
  install -m 400 "$pem" "$KEY"
  ok "Copied to $KEY"
  ask_yes "Delete the original $pem now that it has been copied?" N && rm -f -- "$pem" && ok "Deleted $pem"
fi

# ---- 3. Webhook secret -----------------------------------------------------
bold "3/5  GitHub webhook secret  (GitHub App settings -> Webhook secret)"
if ! keep_existing "$HOOK" "The webhook secret"; then
  echo "  Type the secret you set in the GitHub App, or leave it empty to generate a new one."
  while true; do
    secret=$(read_hidden "Webhook secret (hidden)")
    if [ -z "$secret" ]; then
      secret=$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')
      echo
      echo "  Generated webhook secret (shown once):"
      echo
      echo "      $secret"
      echo
      echo "  Paste it into GitHub App settings -> Webhook secret, then click Save changes."
      read -rp "  Press Enter when done... " _
      break
    fi
    again=$(read_hidden "Type it again to confirm")
    [ "$secret" = "$again" ] && break
    warn "The two entries do not match. Try again."
  done
  write_secret "$HOOK" "$secret"
  unset secret again
  ok "Saved $HOOK"
fi

# ---- 4. Discord bot token --------------------------------------------------
bold "4/5  Discord bot token  (Discord Developer Portal -> your app -> Bot -> Reset Token)"
if ! keep_existing "$TOKEN" "The Discord token"; then
  while true; do
    token=$(read_hidden "Bot token (hidden)")
    if [ -z "$token" ]; then
      warn "The token is required."
    elif [[ ! $token =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]; then
      warn "That does not look like a bot token (three parts separated by dots)."
      ask_yes "Use it anyway?" N && break
    else
      break
    fi
  done
  write_secret "$TOKEN" "$token"
  unset token
  ok "Saved $TOKEN"
fi

# ---- 5. Ownership and permissions ------------------------------------------
bold "5/5  Ownership and permissions  (container user uid $SERVICE_UID)"
chown -R "$SERVICE_UID:$SERVICE_UID" data secrets
chmod 700 data secrets
chmod 400 "$KEY" "$HOOK" "$TOKEN"
chmod 644 "$CONFIG"
ls -ln "$KEY" "$HOOK" "$TOKEN" | sed 's/^/  /'
ok "data/ and secrets/ belong to uid $SERVICE_UID"

# ---- Start -----------------------------------------------------------------
health() {
  if command -v curl >/dev/null; then
    curl -fsS "http://127.0.0.1:$PORT/health" 2>/dev/null | grep -q UP
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT" && printf 'GET /health HTTP/1.0\r\n\r\n' >&3 && grep -q UP <&3) 2>/dev/null
  fi
}

echo
if ! ask_yes "Start (or restart) the service now?" Y; then
  echo
  echo "  Start it later with:  docker compose up -d --build --force-recreate"
  exit 0
fi
docker compose up -d --build --force-recreate
printf '  Waiting for http://127.0.0.1:%s/health ' "$PORT"
for _ in $(seq 1 45); do
  if health; then
    echo
    ok "The service is running."
    echo
    echo "  Next:"
    echo "    - Set up the Cloudflare Tunnel: ../docs/CLOUDFLARE-TUNNEL.md"
    echo "    - Configure notifications:      docker compose exec bot java -jar /app/gitstatus.jar admin"
    exit 0
  fi
  printf '.'
  sleep 2
done
echo
warn "The service did not become healthy within 90 seconds. Recent log:"
docker compose logs --tail=40 bot
exit 1
