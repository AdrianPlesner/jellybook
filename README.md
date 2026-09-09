# Jellybook

[![CI](https://github.com/AdrianPlesner/jellybook/actions/workflows/ci.yml/badge.svg)](https://github.com/AdrianPlesner/jellybook/actions/workflows/ci.yml)
[![Release APK](https://github.com/AdrianPlesner/jellybook/actions/workflows/release.yml/badge.svg)](https://github.com/AdrianPlesner/jellybook/actions/workflows/release.yml)

An Android audiobook player for [Jellyfin](https://jellyfin.org). Built with Kotlin, Jetpack Compose and Media3.

Download the latest APK from the [releases page](https://github.com/AdrianPlesner/jellybook/releases).

## Features

- Signs in to a Jellyfin server and lists every item in libraries with content type **Books** (Jellyfin's `AudioBook` items).
- Plays audiobooks in the background with a media notification, lock-screen controls, Bluetooth/headset buttons and 30 second skip buttons.
- **Chapters** for m4b files. Jellyfin's own chapter list is used when the server extracted one (Jellyfin 10.9+). Otherwise the
  app reads the chapter markers straight from the file with HTTP range requests: both QuickTime chapter tracks (`tref/chap`)
  and Nero `chpl` atoms are supported, so no full download is needed. Chapter-relative scrubbing, previous/next chapter and
  a highlighted chapter list.
- **Progress is stored on the Jellyfin server**, so it follows you across devices and shows up in Jellyfin's own UI. No plugin
  is required, see below.
- **Offline listening.** Download a book to the device (Media3 download cache, resumable, with a progress notification) and
  play it without a connection. Progress recorded offline is queued and published to the server when the network is back.
- **Conflict handling.** If the server position moved while this device had unsynced progress (you listened elsewhere in the
  meantime), the app shows both checkpoints and asks which one to keep.
- **Named bookmarks** per book, synced through the user's Jellyfin display preferences so they also follow you across devices.
- **Sleep timer**: stop after N minutes, or at the end of the current or any later chapter.
- Playback speed 0.75x to 2x, remembered between sessions.

## How progress is stored (why no plugin is needed)

Jellyfin already keeps a per-user `PlaybackPositionTicks` for every item and treats `AudioBook` items as resumable. Jellybook
reports playback exactly like the official clients do (`/Sessions/Playing`, `/Sessions/Playing/Progress`,
`/Sessions/Playing/Stopped`), so the server shows the book as "now playing", counts plays and marks it played at the end.

Jellyfin's audiobook heuristics round positions inside the first five minutes down to zero and mark the book played in the
last five minutes. To keep the exact position anyway the app additionally writes it through `POST /UserItems/{id}/UserData`
(Jellyfin 10.10+). On older servers that endpoint is skipped and the session reports alone apply.

Bookmarks live in `GET/POST /DisplayPreferences/jellybook?client=jellybook` under `CustomPrefs["bookmarks.<itemId>"]`, the
same per-user key/value store the web client uses for its settings.

### Sync rules

Every position update is written to a small local store first, flagged as pending until the server confirms it. When the
network returns the pending entries are compared with the server:

| Server position since our last sync | Result |
| --- | --- |
| unchanged (or only rounded by Jellyfin's heuristics) | local position is pushed |
| equal to the local position | nothing to do |
| moved to something else | a conflict is recorded and the user picks a checkpoint |

## Requirements

- Android 8.0 (API 26) or newer.
- Jellyfin 10.9 or newer. 10.10+ for exact positions inside the first/last five minutes.
- The audiobooks must be in a library whose content type is **Books**. Files in a Music library are `Audio` items, which
  Jellyfin does not treat as resumable.

## Building

```bash
./gradlew :app:assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`. Unit tests (chapter parser) run with `./gradlew :app:testDebugUnitTest`.

Toolchain: Gradle 9.7, AGP 9.4 (built-in Kotlin), compileSdk 37, JDK 17+.

### CI and releases

- `CI` (`.github/workflows/ci.yml`) runs the unit tests and builds a debug APK on every push to `main` and every pull
  request. The APK is available as a workflow artifact.
- `Release APK` (`.github/workflows/release.yml`) runs when a GitHub release is published (or manually via
  *Run workflow* with an existing tag). It runs the tests, builds a minified release APK with `versionName` taken from the
  tag and `versionCode` from the run number, and attaches `jellybook-<tag>.apk` to the release.

#### Signing

Releases are signed with a stable key, so each release installs as an update over the previous one. Verify a downloaded
APK with `apksigner verify --print-certs jellybook-<tag>.apk`; the certificate is

```
CN=Adrian Plesner, O=Jellybook, C=DK
SHA-256: 50:1B:7E:71:95:AC:77:67:F7:B8:65:D4:B8:98:9B:55:F2:5C:47:BE:9B:51:56:3B:11:9A:30:13:53:21:B4:BB
```

Signing reads four repository secrets: `RELEASE_KEYSTORE_BASE64` (the keystore, base64 encoded),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. If they are absent, as in a fork, the build
falls back to the runner's debug key and names the file `jellybook-<tag>-debugsigned.apk`. Such a build installs and runs,
but every run generates a different debug key, so those APKs cannot be updated in place. To set up your own key:

```bash
keytool -genkeypair -v -keystore jellybook.keystore -alias jellybook -keyalg RSA -keysize 4096 -validity 10000
openssl base64 -A -in jellybook.keystore | gh secret set RELEASE_KEYSTORE_BASE64
```

Keep the keystore and its password backed up outside the repository. Losing them means future releases are signed with a
different key and can no longer update an installed copy of the app.

### Test server

`tools/dev-jellyfin.sh` starts a throwaway Jellyfin in Docker with two generated m4b books (chapters and covers included),
completes the setup wizard and creates a Books library. From the Android emulator sign in with `http://10.0.2.2:8096`,
user `test`, password `test`.

## Project layout

```
app/src/main/java/dk/azp/jellybook/
  AppContainer.kt              hand-wired dependencies
  data/jellyfin/               thin OkHttp + kotlinx.serialization client for the Jellyfin REST API
  data/chapters/               MP4 chapter parser (chpl + chapter tracks) and the chapter repository
  data/progress/               progress sync: live reporting, offline queue, conflict detection
  data/bookmarks/              bookmark merge/sync through display preferences
  data/downloads/              Media3 DownloadManager wrapper and offline catalogue
  data/local/                  DataStore-backed tables (session, progress, catalogue, bookmarks)
  playback/                    MediaSessionService, ExoPlayer, progress reporter, sleep timer, download service
  ui/                          Compose screens: login, library, book player, dialogs
```

## Known limitations

- Multi-file audiobooks (a folder of mp3s) appear as one entry per file; single-file m4b books are the primary target.
- Bookmarks are merged by id; renaming the same bookmark on two devices while offline keeps the local name.
- No Android Auto browse tree yet (the media session itself works with Auto/Wear controls).
