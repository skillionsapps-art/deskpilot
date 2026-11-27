DeskPilot website - ready to host
=================================

Upload this whole folder to any static web host. The page needs no server code:
  - your own domain (e.g. skillions.in/deskpilot) via cPanel / FTP
  - Netlify: drag the folder onto app.netlify.com/drop
  - GitHub Pages, Cloudflare Pages, Vercel (static)

Folder
  index.html     the page
  img/           icon + app screenshots
  downloads/     the four server kits (Windows, macOS, Linux, Raspberry Pi)

When the app is live on Google Play
  Open index.html, find this line near the bottom and paste your link:
      const PLAY_URL = "";
  e.g.  const PLAY_URL = "https://play.google.com/store/apps/details?id=com.skillions.deskpilot";
  The "Coming soon" badges turn into working "Get it on Google Play" buttons.

Updating the server downloads
  Rebuild them from the project:  python server/kits/build_kits.py website/downloads
  (keep the same file names). If you later publish signed installers through the
  GitHub release workflow, change the "file" entries in DOWNLOADS to those links.
