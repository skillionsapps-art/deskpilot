#!/bin/bash
# DeskPilot Server - macOS installer. Double-click (first time: right-click > Open).
# Creates "DeskPilot Server" in your Applications folder (~/Applications) and starts it.
set -e
KIT="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="DeskPilot Server"
SUPPORT="$HOME/Library/Application Support/$APP_NAME"
VENV="$SUPPORT/venv"
APP="$HOME/Applications/$APP_NAME.app"
say() { printf "  \033[36m%s\033[0m\n" "$1"; }

echo; echo "  DeskPilot Server setup"; echo "  ----------------------"

ok_python() { "$1" -c 'import sys, venv; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; }
PY=""
for c in /Library/Frameworks/Python.framework/Versions/3.13/bin/python3 \
         /Library/Frameworks/Python.framework/Versions/3.12/bin/python3 \
         /Library/Frameworks/Python.framework/Versions/3.11/bin/python3 \
         /opt/homebrew/bin/python3 /usr/local/bin/python3; do
  if [ -x "$c" ] && ok_python "$c"; then PY="$c"; break; fi
done
# Apple's own python3 only exists once the Command Line Tools are installed
if [ -z "$PY" ] && xcode-select -p >/dev/null 2>&1 && ok_python /usr/bin/python3; then PY=/usr/bin/python3; fi

if [ -z "$PY" ]; then
  say "Python not found - downloading Python 3.12 from python.org (about 45 MB)..."
  PKG="/tmp/python-3.12.8-macos11.pkg"
  curl -fL --progress-bar -o "$PKG" "https://www.python.org/ftp/python/3.12.8/python-3.12.8-macos11.pkg"
  say "Installing Python - enter your Mac password if asked."
  sudo installer -pkg "$PKG" -target /
  PY=/Library/Frameworks/Python.framework/Versions/3.12/bin/python3
  ok_python "$PY" || { echo "Python install failed."; exit 1; }
fi
say "Using Python: $PY"

pkill -f "deskpilot_server" 2>/dev/null || true
say "Copying files..."
mkdir -p "$SUPPORT"
rm -rf "$SUPPORT/app"
cp -R "$KIT/server" "$SUPPORT/app"
cp "$KIT/uninstall.command" "$SUPPORT/"

say "Creating a private Python environment..."
"$PY" -m venv "$VENV"
say "Installing DeskPilot Server and its components (1-2 minutes)..."
"$VENV/bin/python" -m pip install --upgrade pip -q --disable-pip-version-check
"$VENV/bin/python" -m pip install "$SUPPORT/app" -q --disable-pip-version-check

say "Creating the app in ~/Applications..."
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$KIT/deskpilot.icns" "$APP/Contents/Resources/deskpilot.icns"
cat > "$APP/Contents/MacOS/DeskPilotServer" <<EOF
#!/bin/bash
exec "$VENV/bin/python" -m deskpilot_server "\$@"
EOF
chmod +x "$APP/Contents/MacOS/DeskPilotServer"
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>DeskPilot Server</string>
  <key>CFBundleDisplayName</key><string>DeskPilot Server</string>
  <key>CFBundleIdentifier</key><string>com.skillions.deskpilot.server</string>
  <key>CFBundleExecutable</key><string>DeskPilotServer</string>
  <key>CFBundleIconFile</key><string>deskpilot</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>LSUIElement</key><true/>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
</dict></plist>
EOF
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

say "Starting DeskPilot Server..."
open "$APP"
echo
printf "  \033[32mDone! DeskPilot Server is in your menu bar and in ~/Applications.\033[0m\n"
echo "  Its settings page opens in your browser - choose a password there."
echo "  macOS will ask to allow it under Privacy & Security > Accessibility:"
echo "  switch it on so your phone can move the mouse and type."
echo
