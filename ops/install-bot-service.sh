#!/usr/bin/env bash
# Install the Lichess bot as a launchd agent so it survives terminal sessions,
# logouts and crashes.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="$(/usr/libexec/java_home -v 25)/bin/java"
PLIST="$HOME/Library/LaunchAgents/com.strix.bot.plist"

[ -f "$REPO/.lichess-token" ] || { echo "missing $REPO/.lichess-token"; exit 1; }

( cd "$REPO" && ./gradlew -q jar )
[ -f "$REPO/build/libs/strix-0.1.0.jar" ] || { echo "jar not built"; exit 1; }

mkdir -p "$HOME/Library/LaunchAgents" "$REPO/logs"
sed -e "s|@JAVA@|$JAVA|g" -e "s|@REPO@|$REPO|g" \
    "$REPO/ops/com.strix.bot.plist.template" > "$PLIST"

launchctl bootout "gui/$(id -u)/com.strix.bot" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl kickstart -k "gui/$(id -u)/com.strix.bot"

echo "installed. useful commands:"
echo "  ops/bot status     ops/bot logs     ops/bot stop     ops/bot start"
