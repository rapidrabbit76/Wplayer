<div align="center">

<img src="docs/icon.svg" width="96" alt="Wplayer" />

# Wplayer

[![CI](https://github.com/rapidrabbit76/Wplayer/actions/workflows/ci.yml/badge.svg)](https://github.com/rapidrabbit76/Wplayer/actions/workflows/ci.yml)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84)
[![GPL v3+](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE)

English · [한국어](docs/translations/README.ko.md)

</div>

Wplayer is an Android video player built with libmpv. Play local files or browse videos on your NAS
over SMB, NFS, FTP and WebDAV. Works on phones, tablets and Android TV.

## Download

Get the APK from [GitHub Releases](https://github.com/rapidrabbit76/Wplayer/releases/latest).
Requires Android 10 or later; supports 64-bit and 32-bit ARM devices.

For Android TV, install with `adb install` or a sideloading app.
You can check for updates in Settings → Updates.

## Screenshots

<img src="docs/screenshots/player.jpg" width="100%" alt="Video playback" />

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/browser.jpg" alt="Folder browser" /></td>
    <td width="50%"><img src="docs/screenshots/settings.jpg" alt="Settings" /></td>
  </tr>
</table>

Video: [Cyberpunk: Edgerunners — Official Trailer](https://www.youtube.com/watch?v=ax5YUmkWf_Y), Netflix.

## Features

- Hardware and software decoding with libmpv, FFmpeg and dav1d.
- SMB 2/3, NFSv3, FTP/FTPS, WebDAV and local storage.
- Folder browsing with list, grid and multi-column views; file-name search.
- Resume playback, playlists, shuffle, repeat and A-B repeat.
- Swipe to seek or adjust brightness and volume. Double-tap to skip; long-press for fast-forward.
- Frame previews while seeking.
- ASS, SRT and SMI subtitles, with encoding detection, styling and delay adjustment.
- On-device subtitle generation with whisper.cpp.
- HDR tone mapping, FSR, FSRCNNX and Anime4K upscaling.
- Background audio, picture-in-picture, headset controls and TV remote support.
- Network cache, resumable downloads and folder locking with device authentication.
- English and Korean. Dark, pure-black and wallpaper-color themes.

## Build

You need JDK 21, Android SDK platform 37, NDK `30.0.16248370`, stable Rust and `cargo-ndk`.
Add the Rust targets `aarch64-linux-android` and `armv7-linux-androideabi`.

The native libraries need `meson`, `ninja`, `cmake`, `nasm`, `pkg-config`, `git` and `libclang`.
See [CI](.github/workflows/ci.yml) for the Ubuntu setup.

```sh
source ./env.sh
native/build.sh
./gradlew :app:assembleDebug
```

Set the SDK and JDK paths in `env.sh` to match your machine.
Gradle builds the Rust core and generates the Kotlin bindings.
Native library versions are pinned in [`native/versions.env`](native/versions.env).

For release signing, put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in an untracked
`keystore.properties` at the repository root. Without it, release builds use the debug key.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development, tests and translations.
Report bugs in [Issues](https://github.com/rapidrabbit76/Wplayer/issues).
For security reports, see [SECURITY.md](SECURITY.md).

## License

[GPL-3.0-or-later](LICENSE). Third-party licenses are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and Settings → About.
