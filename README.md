# ArrDeck

A small, fast Android client (~75 KB, no libraries) for **Radarr**, **Sonarr** and **Prowlarr**, with settings imported from an **nzb360 backup**.

## Features
- **Multiple servers**: each has its own Radarr/Sonarr/Prowlarr. Switch from the ⇄ chip at the top right of every screen; import a backup into the current server or as a new one.
- **Search tab** (opens on launch with the keyboard up): Radarr + Sonarr lookup mixed in one list (yellow = movie, blue = show), tap for an info page with ratings, synopsis, release dates and IMDb/TMDb/TVDb/trailer links.
- **Import nzb360 backup**: Settings → *Choose backup file*, or share/open the backup file with ArrDeck. The importer reads JSON, Android prefs XML, zip, gzip and base64 files, finds Radarr/Sonarr/Prowlarr URLs and API keys, and lets you pick which profile to import when there is more than one. Imported connections are tested automatically.
- **Movies (Radarr)** and **Shows (Sonarr)**
  - Library poster grid with status bars (green = downloaded, red = missing, blue = not released yet, grey = unmonitored), text filter, Missing/Downloaded/Monitored filters, and sorting.
  - Upcoming (calendar) and Queue (progress, ETA, remove or blocklist).
  - Add: search TMDb/TVDb, then pick quality profile, root folder (with free space), availability or monitor mode, series type and season folders, and whether to search right away. Your choices are remembered.
  - Details: automatic search, **interactive search** (pick a release, including rejected ones), monitor toggle, delete (optionally with files).
  - Sonarr seasons and episodes: per-season monitor, season search and interactive search, per-episode search and monitor.
- **Search (Prowlarr)**: search all indexers or chosen ones, torrents only by default. Filter by category; sort by seeders, size, age or grabs. Tap a result to send it to your download client through Prowlarr, open the magnet in a torrent app, copy the link, or open the indexer page.
- Plain-HTTP LAN servers, URL bases, basic auth, and an optional "allow self-signed certificate" setting per server.

## Build
Uses Ubuntu's packaged Android tools (no Gradle or Android Studio):
```
apt-get install aapt apksigner dalvik-exchange android-sdk-platform-23 zipalign
./build.sh   # -> ArrDeck.apk
```
`build.sh` creates `arrdeck.keystore` on first run. It's git-ignored, so keep your own copy safe: updates must be signed with the same key to install over an existing install. Set `KEYSTORE=/path/to/key` to use a different one.

Prebuilt APKs are attached to the GitHub releases.
