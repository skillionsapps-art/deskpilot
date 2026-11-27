#!/usr/bin/env bash
# Removes DeskPilot Server (settings in ~/.deskpilot are kept).
systemctl --user disable --now deskpilot-server 2>/dev/null || true
rm -f "$HOME/.config/systemd/user/deskpilot-server.service"
pkill -f deskpilot_server 2>/dev/null || true
rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/deskpilot-server"
rm -f "$HOME/.local/bin/deskpilot-server" "$HOME/.local/share/applications/deskpilot-server.desktop" \
      "$HOME/.local/share/icons/hicolor/256x256/apps/deskpilot-server.png" "$HOME/.config/autostart/deskpilot-server.desktop"
echo "DeskPilot Server has been removed."
