//! Wplayer native core.
//!
//! Exposes these boundaries to Kotlin:
//! - [`browser`]: directory listing for local and network sources (UniFFI).
//! - `archive`: the images inside comic archives, read in place (UniFFI).
//! - [`player`]: libmpv-backed playback control (UniFFI).
//! - `transcription`: speech to subtitle lines with whisper.cpp (UniFFI).
//! - `android`: raw JNI entry points that UniFFI cannot express (JavaVM, Surface).

uniffi::setup_scaffolding!();

mod archive;
pub mod browser;
mod error;
mod logging;
pub mod player;
mod runtime;
pub mod source;
mod subtitle;
mod transcription;
mod transfer;
pub mod vfs;

#[cfg(target_os = "android")]
mod android;

pub use error::CoreError;

/// Initializes process-wide state (logging). Safe to call more than once.
#[uniffi::export]
pub fn init_core() {
    logging::init();
}

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}
