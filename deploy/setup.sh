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
# hidden input turns terminal echo off; turn it back on even after Ctrl+C
if [ -t 0 ]; then
  trap 'stty echo' EXIT
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
bold "1/5  GitHub App ID"
cat <<'EOF'
  Where to get it: GitHub -> Settings -> Developer settings -> GitHub Apps -> your app -> Edit
  -> General -> "About" -> "App ID" (a number, e.g. 1234567). Not the Client ID (Iv23...).
EOF
current=$(sed -n 's/^github\.appId=//p' "$CONFIG" | tr -d '[:space:]')
while true; do
  read -rp "  App ID${current:+ [$current]}: " app_id
  app_id=${app_id:-$current}
  [[ $app_id =~ ^[0-9]+$ ]] && break
  if [[ $app_id == Iv* ]]; then
    warn "That is the Client ID. Use the App ID shown just above it (numbers only)."
  else
    warn "The App ID is a number, for example 1234567."
  fi
done
if grep -q '^github\.appId=' "$CONFIG"; then
  sed -i "s/^github\.appId=.*/github.appId=$app_id/" "$CONFIG"
else
  printf '\ngithub.appId=%s\n' "$app_id" >> "$CONFIG"
fi
ok "github.appId=$app_id"

# ---- 2. GitHub App private key ---------------------------------------------
# use_pem FILE: validates and copies FILE to $KEY
use_pem() {
  if [ ! -f "$1" ]; then
    warn "File not found: $1"
    return 1
  fi
  if ! grep -q 'PRIVATE KEY-----' "$1"; then
    warn "$1 is not a private key. Use the .pem downloaded with 'New key'."
    return 1
  fi
  install -m 400 "$1" "$KEY"
  ok "Copied to $KEY"
  if ask_yes "Delete the original $1 now that it has been copied?" Y; then
    rm -f -- "$1"
    ok "Deleted $1"
  fi
}

bold "2/5  GitHub App private key (.pem file)"
server=$(hostname -I 2>/dev/null | awk '{print $1}')
cat <<EOF
  Where to get it: GitHub -> Settings -> Developer settings -> GitHub Apps -> your app -> Edit
  -> left sidebar "Credentials" -> "Key pairs" -> "New key". Your browser downloads a .pem file.
  Put that file in this directory: $PWD
  For example, run on your own computer:
      scp ~/Downloads/<file>.pem ${SUDO_USER:-user}@${server:-this-server}:$PWD/
EOF
if ! keep_existing "$KEY" "The private key"; then
  while true; do
    mapfile -t pems < <(find "$PWD" -maxdepth 1 -name '*.pem' -type f | sort)
    echo
    if [ ${#pems[@]} -eq 1 ]; then
      echo "  Found: ${pems[0]}"
      if ask_yes "Is this the correct .pem file you want to use?" Y; then
        use_pem "${pems[0]}" && break
      fi
    elif [ ${#pems[@]} -gt 1 ]; then
      echo "  Found ${#pems[@]} .pem files:"
      for i in "${!pems[@]}"; do
        printf '    %d) %s\n' $((i + 1)) "${pems[i]}"
      done
      read -rp "  Which one is the GitHub App private key? [1-${#pems[@]}]: " n
      if [[ $n =~ ^[0-9]+$ ]] && [ "$n" -ge 1 ] && [ "$n" -le ${#pems[@]} ]; then
        use_pem "${pems[n - 1]}" && break
      else
        warn "Not one of the numbers above."
      fi
    else
      warn "No .pem file in $PWD yet."
    fi
    read -rp "  Press Enter to search again, or type the path of the .pem file: " pem
    if [ -n "$pem" ]; then
      use_pem "$(expand_path "$pem")" && break
    fi
  done
fi

# ---- 3. Webhook secret -----------------------------------------------------
bold "3/5  GitHub webhook secret"
cat <<'EOF'
  The text you typed into "Webhook secret" when creating the GitHub App. GitHub never shows it
  again (the page only says "There is currently a secret configured"). If you do not remember
  it, leave this empty: a new one is generated, then in the GitHub App -> Edit -> General ->
  "Webhook" click "Change secret", paste it and click "Save changes".
EOF
if ! keep_existing "$HOOK" "The webhook secret"; then
  while true; do
    secret=$(read_hidden "Webhook secret (hidden)")
    if [ -z "$secret" ]; then
      secret=$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')
      echo
      echo "  Generated webhook secret (shown once):"
      echo
      echo "      $secret"
      echo
      echo "  In the GitHub App -> Edit -> General -> Webhook: click \"Change secret\", paste it, click \"Save changes\"."
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
bold "4/5  Discord bot token"
cat <<'EOF'
  Where to get it: https://discord.com/developers/applications -> your application -> "Bot"
  -> "Reset Token" -> "Copy". It looks like  MTIz...xyz.Gh1abc.AbCd...  (three parts with dots).
  Not the Application ID or Public Key from "General Information".
EOF
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
