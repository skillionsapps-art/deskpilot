# DeskPilot – universal remote for PC, Mac and Linux

A native Android app (Kotlin + Jetpack Compose) plus a cross-platform desktop server (Python),
built to match the feature set of Unified Remote Full.

```
deskpilot/
├── .github/workflows/server-release.yml   builds Windows/macOS/Linux/Raspberry Pi installers
├── website/download.html   download page (auto-detects the visitor's OS)
├── android/   Android Studio project  (app = phone, wear = Wear OS)   JDK 17 · AGP 8.7 · minSdk 26 · targetSdk 35
└── server/    Desktop server for Windows / macOS / Linux             Python 3.9+
```

## Feature map (Unified Remote Full → DeskPilot)

| Feature | Where |
|---|---|
| Easy setup, automatic server detection | UDP discovery `net/Discovery.kt` ↔ `server/discovery.py` |
| Password protection + encryption | AES-256-GCM, PBKDF2 key — `net/Crypto.kt` ↔ `server/crypto.py` (interop-tested) |
| Wi-Fi **or** Bluetooth | `TcpTransport` / `BluetoothTransport` (Bluetooth is experimental) |
| Single & multi-touch mouse | `ui/controls/InputControls.kt` – tap, 2-finger scroll, 2-finger tap = right click, 3-finger = middle, hold-and-move = drag, acceleration |
| Keyboard (any language) | Phone keyboard + PC keys, F1-F12, sticky Ctrl/Alt/Shift/Win |
| Wake On LAN | Bolt button on each saved computer + Power remote |
| Light & dark themes, dynamic colour | Settings |
| 90+ remotes | **103 server remotes** generated from the official list + phone-side Custom/IR remotes |
| Floating remotes (over other apps) | `services/FloatingRemoteService` – draggable bubble + media pad |
| Custom remotes / Custom keys | "Custom & IR remotes": key combos, text, media keys, mouse, URLs, commands |
| Widgets & Quick Actions | Home-screen media widget, Quick Settings tile, launcher long-press shortcuts, pin any remote/button |
| Voice commands | Mic in the top bar: "volume up 5", "pause", "open YouTube", "search for…", "type…", "shut down" (confirms) |
| IR actions | Phone IR blaster: Samsung32, NEC and Pronto codes (sample Samsung TV remote included) |
| NFC actions | Long-press any button → *Write to NFC tag*; tapping the tag runs it |
| Android Wear | `wear/` module: media, volume, voice, mini trackpad, relayed via the phone |
| Auto-pause on calls | Settings → Pause media on incoming calls (READ_PHONE_STATE) |
| Haptic feedback, keep-awake | Settings |
| Screen mirroring, File Manager, Task Manager, Terminal, Power, Monitor | Server built-ins in `server/deskpilot_server/builtins.py` |

### Remote coverage
Essentials (Mouse, Keyboard, Basic Input, Function keys, Navigation, Numpad, Scroll Wheel, Send Text, Volume, Media, Power, Monitor, Screen) ·
Music (Spotify, iTunes, Music, Winamp, foobar2000, MusicBee, MediaMonkey, J River, Groove, Pandora, Napster, Google Music, SoundCloud) ·
Video (VLC, VLC Advanced, MPC-HC, MPC-BE, PotPlayer, KMPlayer ×2, GOM, BSPlayer, WMP, Movies & TV, QuickTime, MPlayerX, Popcorn Time, Stremio, Powder Player, Porthole) ·
Streaming (YouTube, Netflix ×3, Hulu ×2, Prime Video, Disney+, HBO, Twitch, Crunchyroll) ·
Media Center (Kodi ×4, Plex ×2, Windows Media Center, MediaPortal, DVBViewer, EyeTV) ·
Presentations (PowerPoint Basic/Advanced, Keynote, Google Slides, Slide Show, Beamer) ·
Browsers (Chrome, Firefox, Edge, Opera, Safari, IE) · Photos (5) ·
System (File Manager, Task Manager, Performance, Command, Custom Run/Scripts/HTTP, Launcher, CD/DVD Tray, Pixel Color, Magnifier, Start, Windows, macOS, Server Manager) ·
Apps (Zoom, Anki, Minecraft, Blender ×2, Pyxel Edit ×2).

Left out on purpose: hardware add-ons (TellStick, USB-UIRT, Arduino Yún) and discontinued legacy remotes (Boxee, XBMC, Picasa, Skype legacy, Windows 8).

## Build the Android app

1. Open `android/` in Android Studio (Ladybug or newer). Set **Gradle JDK = 17**.
2. Run the `app` configuration on a phone. For the watch, run `wear` on a paired Wear OS device.
3. Release: *Build → Generate Signed App Bundle*.

## Try it end to end

1. On the computer: install DeskPilot Server (installer from the Releases page, or `cd server && pip install . && deskpilot-server`). It opens a settings page; set the password there.
2. On the phone (same Wi-Fi): open DeskPilot → it lists the computer → enter the password → pick a remote.

## Before Play Store

- Rename if needed: app name is in `res/values/strings.xml`, package `com.skillions.deskpilot`.
- Declare `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE` and the `specialUse` foreground service in the Play Console permissions form.
- Passwords are stored in app-private storage; move them to Android Keystore-backed encryption if you want defence against rooted devices.
- Free vs Full: every remote carries a `"tier"` field (`free` / `full`) so you can gate them with Play Billing later.
