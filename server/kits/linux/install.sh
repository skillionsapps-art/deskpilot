#!/usr/bin/env bash
# DeskPilot Server - Linux & Raspberry Pi installer (installs for your user; asks for sudo only to add Python).
#   ./install.sh               desktop install: app menu entry + tray app
#   ./install.sh --headless    also run it as a background service (Raspberry Pi, media PCs, no screen)
set -e
KIT="$(cd "$(dirname "$0")" && pwd)"
PREFIX="${XDG_DATA_HOME:-$HOME/.local/share}/deskpilot-server"
VENV="$PREFIX/venv"
BIN="$HOME/.local/bin/deskpilot-server"
HEADLESS=0; [ "$1" = "--headless" ] && HEADLESS=1
say() { printf "  \033[36m%s\033[0m\n" "$1"; }

echo; echo "  DeskPilot Server setup"; echo "  ----------------------"

ok_python() { python3 -c 'import sys, venv, ensurepip; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; }
if ! ok_python; then
  say "Installing Python 3 (you may be asked for your password)..."
  if command -v apt-get >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y python3 python3-venv python3-pip
  elif command -v dnf >/dev/null; then sudo dnf install -y python3 python3-pip
  elif command -v pacman >/dev/null; then sudo pacman -S --needed --noconfirm python python-pip
  elif command -v zypper >/dev/null; then sudo zypper install -y python3 python3-pip
  else echo "Please install Python 3.9+ with the venv module, then run this again."; exit 1; fi
  ok_python || { echo "Python 3.9+ with venv is still missing."; exit 1; }
fi
say "Using $(python3 --version)"

pkill -f "deskpilot_server" 2>/dev/null || true
say "Copying files to $PREFIX ..."
mkdir -p "$PREFIX" "$HOME/.local/bin"
rm -rf "$PREFIX/app"
cp -r "$KIT/server" "$PREFIX/app"
cp "$KIT/uninstall.sh" "$PREFIX/"

say "Creating a private Python environment..."
python3 -m venv "$VENV"
say "Installing DeskPilot Server and its components (1-3 minutes on a Raspberry Pi)..."
"$VENV/bin/python" -m pip install --upgrade pip -q --disable-pip-version-check
"$VENV/bin/python" -m pip install -q --disable-pip-version-check cryptography psutil Pillow pystray python-xlib six
# pynput without evdev (that needs a C compiler); X11 input uses python-xlib
"$VENV/bin/python" -m pip install -q --disable-pip-version-check --no-deps "$PREFIX/app" pynput 2>&1 | grep -v -e "evdev" -e "dependency resolver" -e "^$" || true

cat > "$BIN" <<EOF
#!/bin/sh
exec "$VENV/bin/python" -m deskpilot_server "\$@"
EOF
chmod +x "$BIN"

mkdir -p "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/256x256/apps"
cp "$KIT/deskpilot-server.png" "$HOME/.local/share/icons/hicolor/256x256/apps/deskpilot-server.png"
cat > "$HOME/.local/share/applications/deskpilot-server.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=DeskPilot Server
Comment=Control this computer from the DeskPilot Android app
Exec=$BIN
Icon=deskpilot-server
Terminal=false
Categories=Utility;Network;
EOF
command -v update-desktop-database >/dev/null && update-desktop-database -q "$HOME/.local/share/applications" || true

if [ "$HEADLESS" = 1 ]; then
  printf "  Choose a password for your phone to connect: "; read -rs PW; echo
  "$BIN" --configure --password "$PW" >/dev/null
  mkdir -p "$HOME/.config/systemd/user"
  cat > "$HOME/.config/systemd/user/deskpilot-server.service" <<EOF
[Unit]
Description=DeskPilot Server
After=network-online.target

[Service]
ExecStart=$BIN --headless
Restart=on-failure
RestartSec=3
Environment=DISPLAY=:0

[Install]
WantedBy=default.target
EOF
  if systemctl --user daemon-reload 2>/dev/null && systemctl --user enable --now deskpilot-server 2>/dev/null; then
    sudo loginctl enable-linger "$USER" 2>/dev/null || true
    say "Background service enabled - it starts automatically at boot."
  else
    say "systemd user services aren't available here. Start it with:  deskpilot-server --headless"
  fi
elif [ -n "$DISPLAY$WAYLAND_DISPLAY" ]; then
  say "Starting DeskPilot Server..."
  nohup "$BIN" >/dev/null 2>&1 &
fi

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo
printf "  \033[32mDone! DeskPilot Server is installed.\033[0m\n"
echo "  Find it in your app menu, or run: deskpilot-server"
[ -n "$IP" ] && echo "  In the Android app, connect to: $IP:9512"
if [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
  echo
  echo "  Note: you are on Wayland. Mouse/keyboard control needs an X11 session:"
  echo "  Ubuntu: pick 'Ubuntu on Xorg' at login. Raspberry Pi OS: sudo raspi-config > Advanced > Wayland > X11."
fi
echo
