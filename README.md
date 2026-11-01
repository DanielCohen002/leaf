<p align="center"><img src="docs/leaflet-icon.png" alt="Leaflet icon" width="160"></p>

# Leaflet

A personal e-reader for Android that never touches the internet. The name is a double meaning: a leaflet is a small leaf of a plant,
and a printed leaflet.

**No network access.** The app declares no permissions at all (not even `INTERNET`), so Android itself blocks any
connection. There is no cloud sync, no analytics, and no web dictionary: lookups use a bundled offline dictionary.

## What it reads

| Kind | Formats |
| --- | --- |
| Books | EPUB, MOBI / AZW / AZW3 / PRC (DRM-free), FB2, TXT, HTML, Markdown |
| Page images | PDF, CBZ, CBR, CB7, CBT (RAR/7z/tar comics are unpacked once into a CBZ when imported) |

## Features

- Paged or continuous-scroll reading, chapter list and chapter skipping, volume keys turn pages
- Light, sepia and dark (inverted) colors, several fonts, text size and line spacing
- Comics and PDFs: one page, two pages side by side (landscape), or continuous scroll; right-to-left (manga) mode; zoom
- Long-press a word to select it, copy it, or look it up in the offline dictionary (WordNet 3.0)
- Reading position is remembered per book; reading settings are remembered per file format
- Library as a list or a grid of covers; books you have read come first (most recent on top), then the rest A to Z
- Optionally keep a folder (such as `Books`) in sync: chosen once with the system folder picker, so Leaflet still needs no storage permission

## Download

Get the latest `Leaflet-<version>.apk` from the
[Releases page](https://github.com/DanielCohen002/leaflet/releases) and open it on your phone (64-bit ARM,
Android 11 or newer). You may need to allow installs from your browser or file manager. Google Play Protect can
show an "App scan recommended" prompt because the app isn't from the Play Store: choose **Install without
scanning** (or **Scan app**). New versions install over the old one and keep your library.

## Building

Requirements: JDK 17, the Android SDK (platform 36, build-tools 36), and Python 3 for the dictionary.

```
# 1. Build the offline dictionary (not stored in git; ~23 MB). Download the full WordNet 3.0 tarball from
#    https://wordnet.princeton.edu/download/current-version, extract it, then:
python tools/build_dictionary.py path/to/WordNet-3.0/dict
cp path/to/WordNet-3.0/LICENSE app/src/main/assets/WORDNET-LICENSE.txt   # already committed; shown for reference

# 2. Debug build
./gradlew assembleDebug
```

### Release signing

Create a keystore and a `signing.properties` file in the project root (it is gitignored):

```
STORE_FILE=C:/path/to/release.jks
STORE_PASSWORD=...
KEY_ALIAS=leaf
KEY_PASSWORD=...
```

Then `./gradlew assembleRelease` produces `app/build/outputs/apk/release/app-release.apk`. Without
`signing.properties` the release build is simply unsigned.

## Notes

- Targets 64-bit ARM phones (`arm64-v8a`) and Android 11+ (`minSdk 30`).
- The only dependency is [libarchive-android](https://github.com/zhanghai/libarchive-android) (for RAR/7z/tar comics).
  Third-party licenses are in `app/src/main/assets/THIRD-PARTY.txt` and `WORDNET-LICENSE.txt`.
- No license has been chosen for Leaflet's own code yet.
