#!/usr/bin/env bash
# Wraps "dist/DeskPilot Server.app" in a drag-to-Applications DMG.
# Optional signing + notarization when these env vars are set (Apple Developer account needed):
#   MACOS_SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)"
#   APPLE_ID, APPLE_TEAM_ID, APPLE_APP_PASSWORD   (app-specific password)
set -euo pipefail
OUT_NAME="$1"                                   # e.g. DeskPilotServer-macOS-AppleSilicon.dmg
APP="dist/DeskPilot Server.app"
STAGE="build/dmg"

if [[ -n "${MACOS_SIGN_IDENTITY:-}" ]]; then
  codesign --force --deep --options runtime --timestamp -s "$MACOS_SIGN_IDENTITY" "$APP"
else
  codesign --force --deep -s - "$APP"           # ad-hoc: required for Apple Silicon to launch it
fi

rm -rf "$STAGE" && mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
cat > "$STAGE/READ ME FIRST.txt" <<'TXT'
1. Drag "DeskPilot Server" onto Applications.
2. Open it from Applications. If macOS says it can't be opened, right-click the app > Open > Open.
3. When asked, allow DeskPilot Server in System Settings > Privacy & Security > Accessibility
   (needed to move the mouse and type). For screen mirroring also allow Screen Recording.
4. Look for the DeskPilot icon in the menu bar, then connect from the Android app.
TXT
mkdir -p dist/release
hdiutil create -volname "DeskPilot Server" -srcfolder "$STAGE" -ov -format UDZO "dist/release/$OUT_NAME"

if [[ -n "${MACOS_SIGN_IDENTITY:-}" && -n "${APPLE_ID:-}" ]]; then
  codesign -s "$MACOS_SIGN_IDENTITY" --timestamp "dist/release/$OUT_NAME"
  xcrun notarytool submit "dist/release/$OUT_NAME" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" \
    --password "$APPLE_APP_PASSWORD" --wait
  xcrun stapler staple "dist/release/$OUT_NAME"
fi
echo "built dist/release/$OUT_NAME"
