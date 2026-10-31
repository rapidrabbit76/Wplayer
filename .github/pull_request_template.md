## What and why

<!-- What this changes and the reason for it. Link the issue if there is one. -->

## How it was tested

<!-- Device or emulator, Android version, protocol, sample files. -->

- [ ] `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` and `cargo test` pass in `rust/` for `wplayer-core`
- [ ] `./gradlew :app:testDebugUnitTest :core:native:testDebugUnitTest :app:lintRelease` passes (in `android/`)
- [ ] New strings are in `values/strings.xml` (English) and translated in `values-ko/strings.xml` where possible
