# Security policy

## Supported versions

Only the [latest release](https://github.com/rapidrabbit76/Wplayer/releases/latest) receives fixes. The app
updates itself from GitHub releases, so please update before reporting.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/rapidrabbit76/Wplayer/security/advisories/new)
with the affected version, the steps to reproduce and the impact you expect. You should get a reply within a week;
fixes are released as soon as they are ready and credited in the advisory unless you prefer otherwise.

## How Wplayer handles sensitive data

- **Server passwords** are encrypted with an AES-GCM key held in the Android Keystore before they are stored, and
  are excluded from backups and device transfer (they could not be decrypted elsewhere).
- **TLS** (FTPS, WebDAVS) verifies certificates. Accepting a self-signed certificate is an explicit per-server
  option that still encrypts traffic but does not check the server's identity.
- **Locked folders** open only after the device's biometric or screen-lock check; their contents stay out of
  history, search results and the thumbnail cache.
- **Updates** come from this repository's GitHub releases. The download's SHA-256 is checked against the release
  manifest, and Android installs it only if it is signed with the same key as the installed app.
- **AI subtitles** run entirely on the device; no audio or video leaves it. Models are downloaded from Hugging Face
  and checked against pinned SHA-256 hashes.
- The app has no analytics, crash reporting or other telemetry.
