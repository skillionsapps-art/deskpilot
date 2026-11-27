#!/usr/bin/env bash
# Builds a .deb (Ubuntu, Debian, Mint, Raspberry Pi OS 64-bit) and a portable .tar.gz
# from dist/DeskPilotServer. Usage: build_deb.sh <version> <amd64|arm64> <label>
set -euo pipefail
VERSION="$1"; DEB_ARCH="$2"; LABEL="$3"       # LABEL: x64 | arm64
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG="build/deb/deskpilot-server"
rm -rf "$PKG" && mkdir -p "$PKG/DEBIAN" "$PKG/opt" "$PKG/usr/bin" "$PKG/usr/share/applications" \
  "$PKG/usr/share/icons/hicolor/256x256/apps" "$PKG/usr/lib/systemd/user"

cp -r dist/DeskPilotServer "$PKG/opt/deskpilot-server"
ln -s /opt/deskpilot-server/DeskPilotServer "$PKG/usr/bin/deskpilot-server"
cp "$HERE/deskpilot-server.desktop" "$PKG/usr/share/applications/"
cp build/icons/deskpilot-256.png "$PKG/usr/share/icons/hicolor/256x256/apps/deskpilot-server.png"
cp "$HERE/deskpilot-server.service" "$PKG/usr/lib/systemd/user/"
SIZE=$(du -sk "$PKG" | cut -f1)
GLIBC=$(ldd --version 2>&1 | head -1 | grep -oE "[0-9]+\.[0-9]+$" || echo "2.17")   # fallback to 2.17

cat > "$PKG/DEBIAN/control" <<EOF
Package: deskpilot-server
Version: $VERSION
Architecture: $DEB_ARCH
Maintainer: Skillions <support@skillions.in>
Installed-Size: $SIZE
Depends: libc6 (>= $GLIBC)
Recommends: libnotify-bin
Section: utils
Priority: optional
Homepage: https://skillions.in
Description: DeskPilot Server - control this computer from your Android phone
 Desktop companion for the DeskPilot Android app: wireless mouse and keyboard,
 media, presentations, power, file and task manager over Wi-Fi or Bluetooth.
 Run "deskpilot-server" (tray app) or enable the headless service with
 "systemctl --user enable --now deskpilot-server".
EOF
cat > "$PKG/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null && update-desktop-database -q || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q /usr/share/icons/hicolor || true
exit 0
EOF
chmod 755 "$PKG/DEBIAN/postinst"

mkdir -p dist/release
dpkg-deb --build "$PKG" "dist/release/deskpilot-server-linux-$LABEL.deb"

# portable tarball
TAR_DIR="build/tar/DeskPilotServer-Linux-$LABEL"
rm -rf "build/tar" && mkdir -p "$TAR_DIR"
cp -r dist/DeskPilotServer/* "$TAR_DIR/"
cat > "$TAR_DIR/README.txt" <<'EOF'
DeskPilot Server (portable)
  ./DeskPilotServer              tray app + settings page (http://127.0.0.1:9513)
  ./DeskPilotServer --headless   no desktop needed (SSH, Raspberry Pi Lite)
Input control needs an X11 session (on Wayland choose "Ubuntu on Xorg" at login,
or on Raspberry Pi OS: sudo raspi-config > Advanced > Wayland > X11).
EOF
tar -C build/tar -czf "dist/release/DeskPilotServer-Linux-$LABEL.tar.gz" "DeskPilotServer-Linux-$LABEL"
echo "built dist/release/deskpilot-server-linux-$LABEL.deb and .tar.gz"
