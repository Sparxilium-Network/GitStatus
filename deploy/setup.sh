#!/usr/bin/env bash
# Interactive setup for this deploy directory:
#   config/config.conf (created from config.conf.example, upgraded to the current config.version)
#   one or more GitHub Apps -> secrets/github-apps/<App ID>/private-key.pem and webhook-secret
#   secrets/discord-token
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
CONFIG_EXAMPLE=config/config.conf.example
# config.conf format version this script writes; matches ConfigVersionCheck.CURRENT
CONFIG_VERSION=1
APPS=secrets/github-apps
LEGACY_KEY=secrets/github-app.pem
LEGACY_HOOK=secrets/github-webhook-secret
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

[ -f "$CONFIG_EXAMPLE" ] || fail "$CONFIG_EXAMPLE not found. Run this script from the deploy directory of the project."
mkdir -p data "$APPS"

# ---- config.conf: create it, or upgrade it to CONFIG_VERSION ----------------
# A release that adds or renames settings raises CONFIG_VERSION (and ConfigVersionCheck.CURRENT in
# the Java code) and adds a step below that edits existing files in place.
if [ ! -f "$CONFIG" ]; then
  install -m 644 "$CONFIG_EXAMPLE" "$CONFIG"
  ok "Created $CONFIG from $CONFIG_EXAMPLE"
fi
config_version=$(sed -n 's/^config\.version=//p' "$CONFIG" | tr -d '[:space:]')
if [ -z "$config_version" ]; then
  # files from before versioning are version 1 apart from the missing line
  sed -i '1i # Format version of this file. setup.sh upgrades older files to the current version when a\n# release adds or renames settings; do not change it by hand.\nconfig.version=1\n' "$CONFIG"
  config_version=1
  ok "Added config.version=1 to $CONFIG"
fi
[[ $config_version =~ ^[0-9]+$ ]] || fail "config.version in $CONFIG is '$config_version', not a number."
if [ "$config_version" -gt "$CONFIG_VERSION" ]; then
  fail "$CONFIG is version $config_version, newer than this setup.sh understands ($CONFIG_VERSION). Run git pull first."
fi
while [ "$config_version" -lt "$CONFIG_VERSION" ]; do
  case $config_version in
    # 1) ... edit settings for version 2 ... ;;
    *) fail "No upgrade step from config.version=$config_version." ;;
  esac
  config_version=$((config_version + 1))
  sed -i "s/^config\.version=.*/config.version=$config_version/" "$CONFIG"
  ok "Upgraded $CONFIG to version $config_version"
done

# ---- GitHub App helpers -----------------------------------------------------
# read_app_id [DEFAULT]: sets APP_ID
read_app_id() {
  cat <<'EOF'
  App ID: GitHub -> Settings (of the user or organization that owns the App) -> Developer settings
  -> GitHub Apps -> the app -> Edit -> General -> "About" -> "App ID" (a number, e.g. 1234567).
  Not the Client ID (Iv23...).
EOF
  while true; do
    read -rp "  App ID${1:+ [$1]}: " APP_ID
    APP_ID=${APP_ID:-${1:-}}
    [[ $APP_ID =~ ^[0-9]+$ ]] && return 0
    if [[ $APP_ID == Iv* ]]; then
      warn "That is the Client ID. Use the App ID shown just above it (numbers only)."
    else
      warn "The App ID is a number, for example 1234567."
    fi
  done
}

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

# pick_pem: finds the .pem in this directory (or asks for a path) and copies it to $KEY
pick_pem() {
  local server pems n pem
  server=$(hostname -I 2>/dev/null | awk '{print $1}')
  cat <<EOF
  Private key: in the same App -> Edit -> left sidebar "Credentials" -> "Key pairs" -> "New key".
  Your browser downloads a .pem file. Put that file in this directory: $PWD
  For example, run on your own computer:
      scp ~/Downloads/<file>.pem ${SUDO_USER:-user}@${server:-this-server}:$PWD/
EOF
  while true; do
    mapfile -t pems < <(find "$PWD" -maxdepth 1 -name '*.pem' -type f | sort)
    echo
    if [ ${#pems[@]} -eq 1 ]; then
      echo "  Found: ${pems[0]}"
      if ask_yes "Is this the correct .pem file you want to use?" Y; then
        use_pem "${pems[0]}" && return 0
      fi
    elif [ ${#pems[@]} -gt 1 ]; then
      echo "  Found ${#pems[@]} .pem files:"
      for i in "${!pems[@]}"; do
        printf '    %d) %s\n' $((i + 1)) "${pems[i]}"
      done
      read -rp "  Which one is the private key of App $APP_ID? [1-${#pems[@]}]: " n
      if [[ $n =~ ^[0-9]+$ ]] && [ "$n" -ge 1 ] && [ "$n" -le ${#pems[@]} ]; then
        use_pem "${pems[n - 1]}" && return 0
      else
        warn "Not one of the numbers above."
      fi
    else
      warn "No .pem file in $PWD yet."
    fi
    read -rp "  Press Enter to search again, or type the path of the .pem file: " pem
    if [ -n "$pem" ]; then
      use_pem "$(expand_path "$pem")" && return 0
    fi
  done
}

# ask_webhook_secret: writes $HOOK
ask_webhook_secret() {
  local secret again
  cat <<'EOF'
  Webhook secret: the text you typed into "Webhook secret" when creating this App. GitHub never
  shows it again (the page only says "There is currently a secret configured"). If you do not
  remember it, leave this empty: a new one is generated, then in the App -> Edit -> General ->
  "Webhook" click "Change secret", paste it and click "Save changes".
  Use a different secret for every App.
EOF
  while true; do
    secret=$(read_hidden "Webhook secret (hidden)")
    if [ -z "$secret" ]; then
      secret=$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')
      echo
      echo "  Generated webhook secret for App $APP_ID (shown once):"
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
  ok "Saved $HOOK"
}

# add_app: asks for one App's ID, private key and webhook secret
add_app() {
  echo
  read_app_id
  mkdir -p "$APPS/$APP_ID"
  KEY=$APPS/$APP_ID/private-key.pem
  HOOK=$APPS/$APP_ID/webhook-secret
  echo
  if ! keep_existing "$KEY" "The private key of App $APP_ID"; then
    pick_pem
  fi
  echo
  if ! keep_existing "$HOOK" "The webhook secret of App $APP_ID"; then
    ask_webhook_secret
  fi
  ok "GitHub App $APP_ID is set up"
}

list_apps() {
  local d id state
  for d in "$APPS"/*/; do
    [ -d "$d" ] || continue
    id=$(basename "$d")
    state=""
    [ -s "$d/private-key.pem" ] || state="missing private-key.pem"
    [ -s "$d/webhook-secret" ] || state="${state:+$state, }missing webhook-secret"
    printf '    App %-12s %s\n' "$id" "${state:-ready}"
  done
}

app_count() {
  find "$APPS" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*' | wc -l
}

# ---- Upgrade: single-App files from earlier releases ------------------------
if [ -f "$LEGACY_KEY" ] || [ -f "$LEGACY_HOOK" ]; then
  bold "Moving the GitHub App from the earlier single-App layout"
  echo "  Found $LEGACY_KEY / $LEGACY_HOOK. Each App now has its own directory under $APPS/."
  legacy_id=$(sed -n 's/^github\.appId=//p' "$CONFIG" | tr -d '[:space:]')
  if [[ $legacy_id =~ ^[0-9]+$ ]]; then
    APP_ID=$legacy_id
  else
    echo "  Which App do these files belong to?"
    read_app_id
  fi
  mkdir -p "$APPS/$APP_ID"
  [ -f "$LEGACY_KEY" ] && mv -f "$LEGACY_KEY" "$APPS/$APP_ID/private-key.pem"
  [ -f "$LEGACY_HOOK" ] && mv -f "$LEGACY_HOOK" "$APPS/$APP_ID/webhook-secret"
  ok "Moved to $APPS/$APP_ID/"
fi

# ---- 1. GitHub Apps ---------------------------------------------------------
bold "1/3  GitHub Apps"
cat <<'EOF'
  A private GitHub App can only be installed on the account that owns it. To get notifications
  from several organizations, create one App in each organization and add each one here.
  All Apps use the same Webhook URL: https://<your tunnel host>/webhooks/github
EOF
if [ "$(app_count)" -eq 0 ]; then
  add_app
else
  echo
  echo "  Configured:"
  list_apps
  echo
  if ask_yes "Change the private key or webhook secret of one of them?" N; then
    add_app
  fi
fi
echo
while ask_yes "Add another GitHub App?" N; do
  add_app
  echo
done

# ---- 2. Discord bot token --------------------------------------------------
bold "2/3  Discord bot token"
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

# ---- 3. Ownership and permissions ------------------------------------------
bold "3/3  Ownership and permissions  (container user uid $SERVICE_UID)"
chown -R "$SERVICE_UID:$SERVICE_UID" data secrets
chmod 700 data
find secrets -type d -exec chmod 700 {} +
find secrets -type f -exec chmod 400 {} +
chmod 644 "$CONFIG"
find secrets -type f | sort | xargs ls -ln | sed 's/^/  /'
ok "data/ and secrets/ belong to uid $SERVICE_UID"
echo
echo "  GitHub Apps:"
list_apps

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
