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
# paste_pem [FIRST_LINE]: reads a pasted PEM (hidden) up to its END line into $KEY
paste_pem() {
  local line pem="${1:+$1$'\n'}" lines=0
  [ -n "$pem" ] && lines=1
  echo "  Paste the whole key now, from -----BEGIN ... to -----END ... (input is hidden)."
  [ -t 0 ] && stty -echo
  while IFS= read -r line; do
    line=${line%$'\r'}
    [ -z "$pem" ] && [ -z "$line" ] && continue
    pem+="$line"$'\n'
    lines=$((lines + 1))
    printf '.' >&2
    [[ $line == -----END*PRIVATE\ KEY----- ]] && break
  done
  [ -t 0 ] && stty echo
  echo >&2
  if [[ $pem != -----BEGIN*PRIVATE\ KEY-----* || $pem != *-----END*PRIVATE\ KEY-----* ]]; then
    warn "That was not a complete private key ($lines lines received). It must start with -----BEGIN and end with -----END."
    return 1
  fi
  write_secret "$KEY" "$pem"
  ok "Saved $KEY ($lines lines)"
}

bold "2/5  GitHub App private key"
cat <<'EOF'
  Where to get it: GitHub -> Settings -> Developer settings -> GitHub Apps -> your app -> Edit
  -> General. Keep scrolling down past "Webhook", "Display information" and "IP allow list"
  (or press Ctrl+F and search "Private keys") -> "Generate a private key".
  Your browser downloads a file named like:  my-app.2026-11-05.private-key.pem
  The short code listed on that page (SHA256:...) is only a fingerprint, not the key.
EOF
if ! keep_existing "$KEY" "The private key"; then
  found=$(find "$USER_HOME" "$PWD" -maxdepth 2 -name '*.private-key.pem' -type f 2>/dev/null | head -n 1 || true)
  server=$(hostname -I 2>/dev/null | awk '{print $1}')
  echo
  echo "  How do you want to provide it?"
  echo "    1) Paste it: open the .pem file in a text editor (Notepad, TextEdit), copy everything"
  echo "    2) Use a file on this server${found:+ (found: $found)}"
  default=1
  [ -n "$found" ] && default=2
  while true; do
    read -rp "  Choose 1 or 2 [$default]: " choice
    case ${choice:-$default} in
      1) paste_pem && break ;;
      2)
        echo "  To copy it here, run on your own computer (adjust the file name):"
        echo "      scp ~/Downloads/my-app.2026-11-05.private-key.pem ${SUDO_USER:-user}@${server:-this-server}:~/"
        read -rp "  Path to the .pem file on this server${found:+ [$found]}: " pem
        if [[ $pem == -----BEGIN* ]]; then
          paste_pem "$pem" && break
          continue
        fi
        pem=$(expand_path "${pem:-$found}")
        if [ ! -f "$pem" ]; then
          if [[ -n $pem && $pem != */* && $pem != *.pem ]]; then
            warn "'$pem' is not a file path. Enter the path of the downloaded .pem file, e.g. ~/my-app.2026-11-05.private-key.pem"
          else
            warn "File not found: ${pem:-<empty>}. Copy it to this server first, or choose 1 to paste it."
          fi
        elif ! grep -q 'PRIVATE KEY-----' "$pem"; then
          warn "$pem is not a private key file. Use the .pem downloaded from 'Generate a private key'."
        else
          install -m 400 "$pem" "$KEY"
          ok "Copied to $KEY"
          ask_yes "Delete the original $pem now that it has been copied?" N && rm -f -- "$pem" && ok "Deleted $pem"
          break
        fi
        ;;
      *) warn "Enter 1 or 2." ;;
    esac
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
