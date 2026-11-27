#!/bin/bash
# Removes DeskPilot Server (settings in ~/.deskpilot are kept).
pkill -f deskpilot_server 2>/dev/null || true
rm -rf "$HOME/Applications/DeskPilot Server.app"
rm -f "$HOME/Library/LaunchAgents/com.skillions.deskpilot.server.plist"
rm -rf "$HOME/Library/Application Support/DeskPilot Server"
echo "DeskPilot Server has been removed."
