# Patched crates

Local copies of crates.io crates with fixes not yet released upstream, wired
in through `[patch.crates-io]` in `../Cargo.toml`. Each change is marked
`[wplayer patch]` in the source. Drop a copy once upstream ships the fix.

| Crate | Version | Fix |
|---|---|---|
| `smb` | 0.12.1 | `src/connection/worker/worker_trait.rs`: credits granted in an async request's interim (STATUS_PENDING) response were dropped. Samba grants them only there, so each slow read leaked credits until the connection hung (endless loading). |
| `smb-transport` | 0.12.1 | `src/tcp/transport.rs`: set `TCP_NODELAY`. Without it Nagle + delayed ACK added ~100 ms to every SMB round trip (a folder listing took ~0.4 s on a LAN). |
| `whisper-rs-sys` | 0.15.0 | `build.rs`: cross-compiling from macOS to Android linked the macOS-only `ggml-blas` (decided by the build machine, not the target); bindgen parses the NDK headers only with the API level in the target triple (`armv7a-linux-androideabi29`), so Android passes it; the bundled bindings fit only 64-bit targets. (It links `libc++_shared.so`, which the APK already ships for libmpv.) GPU backends the app never builds (CUDA, SYCL, CANN, WebGPU, Vulkan) were removed from `whisper.cpp/ggml/src` to keep the repository small. |

`smb` and `smb-transport` are MIT licensed, `whisper-rs-sys` is Unlicense (whisper.cpp itself is MIT); see their `Cargo.toml`.
