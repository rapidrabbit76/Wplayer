# Third-party software

Wplayer is licensed under the GNU General Public License, version 3 or (at your option) any later
version; see [LICENSE](LICENSE). The APK contains the software below. Every license listed is
compatible with GPL-3.0; the GPL is required because mpv is built with its GPL parts (`-Dgpl=true`).

## Native libraries (built by native/build.sh)

| Component | Version | License | Source |
|---|---|---|---|
| mpv (libmpv) | v0.41.0 | GPL-2.0-or-later | https://github.com/mpv-player/mpv |
| FFmpeg | n9.0.2 | LGPL-2.1-or-later (built without `--enable-gpl`) | https://ffmpeg.org |
| dav1d | 1.5.4 | BSD-2-Clause | https://code.videolan.org/videolan/dav1d |
| libplacebo | v7.360.1 | LGPL-2.1-or-later | https://code.videolan.org/videolan/libplacebo |
| libass | 0.17.5 | ISC | https://github.com/libass/libass |
| FreeType | VER-2-15-0 | FTL (FreeType License) | https://freetype.org |
| HarfBuzz | 14.6.0 | MIT ("Old MIT") | https://github.com/harfbuzz/harfbuzz |
| FriBidi | v1.0.17 | LGPL-2.1-or-later | https://github.com/fribidi/fribidi |
| LLVM libc++ (`libc++_shared.so`, Android NDK) | NDK 30 | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

## Video shaders (app/src/main/assets/shaders, unmodified)

| Component | License | Source |
|---|---|---|
| AMD FidelityFX FSR 1, mpv port by agyild (FSR.glsl) | MIT | https://github.com/GPUOpen-Effects/FidelityFX-FSR |
| FSRCNNX (FSRCNNX_x2_8-0-4-1.glsl) | LGPL-3.0-or-later | https://github.com/igv/FSRCNN-TensorFlow |
| Anime4K (Anime4K_*.glsl) | MIT; AutoDownscalePre: Unlicense | https://github.com/bloc97/Anime4K |
| AMD FidelityFX CAS, mpv port by agyild (CAS.glsl) | MIT | https://gist.github.com/agyild/bbb4e58298b2f86aa24da3032a0d2ee6 |
| Chroma from Luma prediction (CfL_Prediction_Lite.glsl) | MIT | https://github.com/Artoriuz/glsl-chroma-from-luma-prediction |

## Rust core (rust/, versions in Cargo.lock)

| Component | License | Source |
|---|---|---|
| whisper.cpp / ggml (through whisper-rs, whisper-rs-sys) | MIT (whisper-rs: Unlicense) | https://github.com/ggml-org/whisper.cpp |
| UniFFI | MPL-2.0 | https://github.com/mozilla/uniffi-rs |
| smb, smb-transport (patched copies in rust/patches) | MIT | https://github.com/afiffon/smb-rs |
| Tokio, reqwest, rustls and the remaining crates | MIT, Apache-2.0, BSD, ISC, Zlib, Unicode-3.0, MPL-2.0, Unlicense, CDLA-Permissive-2.0 | see `cargo metadata` |

`(cd rust && cargo metadata --format-version 1 --filter-platform aarch64-linux-android)` lists every
crate in the APK with its license.

## Android (versions in gradle/libs.versions.toml)

| Component | License | Source |
|---|---|---|
| AndroidX, Jetpack Compose, Material 3, Media3, Room, WorkManager, DataStore, Hilt/Dagger | Apache-2.0 | https://developer.android.com/jetpack |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | https://kotlinlang.org |
| JNA | Apache-2.0 OR LGPL-2.1-or-later | https://github.com/java-native-access/jna |

## Downloaded at run time (not in the APK)

| Component | License | Source |
|---|---|---|
| whisper.cpp models (ggml-small-q8_0, ggml-large-v3-turbo-q5_0) | MIT | https://huggingface.co/ggerganov/whisper.cpp |
| Silero VAD for whisper.cpp | MIT | https://huggingface.co/ggml-org/whisper-vad |
