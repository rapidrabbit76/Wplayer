//! libmpv-backed player exposed to Kotlin.
//!
//! One [`Player`] owns one mpv core. A dedicated thread drains mpv events and
//! forwards them to the Kotlin [`PlayerListener`].

pub(crate) mod audio;
mod ffi;
mod preview;
mod stream;
mod thumbnail;
mod tracks;

use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread::JoinHandle;

use ffi::*;
pub use tracks::{Track, TrackKind};

use crate::error::{CoreError, CoreResult};

#[derive(Debug, Clone, uniffi::Record)]
pub struct PlayerConfig {
    /// Writable directory for mpv's cache and screenshots.
    pub data_dir: String,
    /// mpv `--hwdec` value, e.g. `mediacodec,mediacodec-copy` or `no`.
    pub hwdec: String,
    /// mpv `--vo` value used while a surface is attached (`gpu` or `gpu-next`).
    pub video_output: String,
    /// Extra mpv options applied after the defaults (override them).
    pub options: HashMap<String, String>,
    /// Lowest mpv log level forwarded to logcat and the listener (`warn`, `info`, `v`, ...).
    pub log_level: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum PropertyValue {
    Unavailable,
    Flag(bool),
    Int(i64),
    Double(f64),
    Text(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum EndReason {
    Eof,
    Stopped,
    Quit,
    Error,
    Other,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum PlayerEvent {
    StartFile,
    FileLoaded,
    PlaybackRestart,
    Seek,
    VideoReconfig,
    EndFile {
        reason: EndReason,
        error: Option<String>,
    },
    Log {
        level: String,
        prefix: String,
        text: String,
    },
    Shutdown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SeekMode {
    Absolute,
    Relative,
}

/// Implemented in Kotlin. Called on the player's event thread.
#[uniffi::export(foreign)]
pub trait PlayerListener: Send + Sync {
    fn on_property_changed(&self, name: String, value: PropertyValue);
    fn on_tracks_changed(&self, tracks: Vec<Track>);
    fn on_event(&self, event: PlayerEvent);
}

/// Properties pushed to the listener whenever they change.
const OBSERVED: &[(&CStr, c_int)] = &[
    (c"time-pos", MPV_FORMAT_DOUBLE),
    (c"duration", MPV_FORMAT_DOUBLE),
    (c"pause", MPV_FORMAT_FLAG),
    (c"paused-for-cache", MPV_FORMAT_FLAG),
    (c"eof-reached", MPV_FORMAT_FLAG),
    (c"seeking", MPV_FORMAT_FLAG),
    (c"speed", MPV_FORMAT_DOUBLE),
    (c"demuxer-cache-time", MPV_FORMAT_DOUBLE),
    (c"hwdec-current", MPV_FORMAT_STRING),
    (c"current-tracks/video/codec", MPV_FORMAT_STRING),
    (c"video-params/dw", MPV_FORMAT_INT64),
    (c"video-params/dh", MPV_FORMAT_INT64),
    (c"ab-loop-a", MPV_FORMAT_DOUBLE),
    (c"ab-loop-b", MPV_FORMAT_DOUBLE),
    (c"sub-delay", MPV_FORMAT_DOUBLE),
    // Non-empty while a subtitle line is on screen; drives the subtitle gestures.
    (c"sub-text", MPV_FORMAT_STRING),
    // Delivered as JSON in the event itself: calling back into mpv from the
    // event thread would block it whenever the core is busy.
    (c"track-list", MPV_FORMAT_STRING),
];

/// Playback statistics, observed only while the stats overlay is shown:
/// several of them change many times a second.
const STATS: &[(&CStr, c_int)] = &[
    (c"estimated-vf-fps", MPV_FORMAT_DOUBLE),
    (c"container-fps", MPV_FORMAT_DOUBLE),
    (c"frame-drop-count", MPV_FORMAT_INT64),
    (c"decoder-frame-drop-count", MPV_FORMAT_INT64),
    (c"video-bitrate", MPV_FORMAT_DOUBLE),
    (c"audio-bitrate", MPV_FORMAT_DOUBLE),
    (c"cache-speed", MPV_FORMAT_INT64),
    (c"demuxer-cache-duration", MPV_FORMAT_DOUBLE),
    (c"current-tracks/audio/codec", MPV_FORMAT_STRING),
    (c"video-params/pixelformat", MPV_FORMAT_STRING),
    (c"video-params/gamma", MPV_FORMAT_STRING),
];

/// Reply ids for [STATS], kept apart from the always-observed ones.
const STATS_ID_BASE: u64 = 1000;

/// Owned mpv handle. The libmpv client API is thread-safe.
struct Mpv {
    api: &'static MpvApi,
    handle: *mut mpv_handle,
}

// SAFETY: every libmpv client API function may be called from any thread.
unsafe impl Send for Mpv {}
unsafe impl Sync for Mpv {}

fn cstring(value: &str) -> CoreResult<CString> {
    CString::new(value).map_err(|_| CoreError::InvalidArgument {
        detail: format!("value contains NUL: {value:?}"),
    })
}

impl Mpv {
    fn check(&self, rc: c_int, what: &str) -> CoreResult<()> {
        if rc < 0 {
            return Err(CoreError::Player {
                detail: format!("{what}: {}", error_string(self.api, rc)),
            });
        }
        Ok(())
    }

    fn set_option(&self, name: &str, value: &str) -> CoreResult<()> {
        let (n, v) = (cstring(name)?, cstring(value)?);
        let rc = unsafe { (self.api.mpv_set_option_string)(self.handle, n.as_ptr(), v.as_ptr()) };
        self.check(rc, &format!("option {name}={value}"))
    }

    fn set_option_i64(&self, name: &str, mut value: i64) -> CoreResult<()> {
        let n = cstring(name)?;
        let data = (&raw mut value).cast::<c_void>();
        let rc = unsafe { (self.api.mpv_set_option)(self.handle, n.as_ptr(), MPV_FORMAT_INT64, data) };
        self.check(rc, &format!("option {name}"))
    }

    fn set_property(&self, name: &str, value: &str) -> CoreResult<()> {
        let (n, v) = (cstring(name)?, cstring(value)?);
        let rc = unsafe { (self.api.mpv_set_property_string)(self.handle, n.as_ptr(), v.as_ptr()) };
        self.check(rc, &format!("property {name}={value}"))
    }

    fn set_flag(&self, name: &str, value: bool) -> CoreResult<()> {
        let n = cstring(name)?;
        let mut flag: c_int = value.into();
        let data = (&raw mut flag).cast::<c_void>();
        let rc = unsafe { (self.api.mpv_set_property)(self.handle, n.as_ptr(), MPV_FORMAT_FLAG, data) };
        self.check(rc, &format!("property {name}"))
    }

    fn set_double(&self, name: &str, mut value: f64) -> CoreResult<()> {
        let n = cstring(name)?;
        let data = (&raw mut value).cast::<c_void>();
        let rc = unsafe { (self.api.mpv_set_property)(self.handle, n.as_ptr(), MPV_FORMAT_DOUBLE, data) };
        self.check(rc, &format!("property {name}"))
    }

    fn get_string(&self, name: &str) -> Option<String> {
        let n = cstring(name).ok()?;
        let raw = unsafe { (self.api.mpv_get_property_string)(self.handle, n.as_ptr()) };
        if raw.is_null() {
            return None;
        }
        let value = unsafe { CStr::from_ptr(raw) }.to_string_lossy().into_owned();
        unsafe { (self.api.mpv_free)(raw.cast()) };
        Some(value)
    }

    fn command<S: AsRef<str>>(&self, args: &[S]) -> CoreResult<()> {
        let owned = args
            .iter()
            .map(|a| cstring(a.as_ref()))
            .collect::<CoreResult<Vec<_>>>()?;
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|a| a.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        let rc = unsafe { (self.api.mpv_command)(self.handle, ptrs.as_mut_ptr()) };
        self.check(rc, &format!("command {}", args.first().map_or("", |a| a.as_ref())))
    }
}

#[derive(uniffi::Object)]
pub struct Player {
    mpv: Arc<Mpv>,
    video_output: String,
    destroyed: AtomicBool,
    event_thread: Mutex<Option<JoinHandle<()>>>,
}

#[uniffi::export]
impl Player {
    #[uniffi::constructor]
    pub fn new(config: PlayerConfig, listener: Arc<dyn PlayerListener>) -> CoreResult<Arc<Self>> {
        let api = ffi::api().map_err(|detail| CoreError::Player { detail })?;
        let handle = unsafe { (api.mpv_create)() };
        if handle.is_null() {
            return Err(CoreError::Player {
                detail: "mpv_create failed".into(),
            });
        }
        let mpv = Arc::new(Mpv { api, handle });

        // On failure `mpv` is dropped without terminate_destroy; release it explicitly.
        let configure = || -> CoreResult<()> {
            apply_defaults(&mpv, &config)?;
            for (name, value) in &config.options {
                mpv.set_option(name, value)?;
            }
            mpv.check(unsafe { (api.mpv_initialize)(handle) }, "mpv_initialize")?;
            stream::register(api, handle).map_err(|detail| CoreError::Player { detail })?;
            let level = cstring(&config.log_level)?;
            mpv.check(unsafe { (api.mpv_request_log_messages)(handle, level.as_ptr()) }, "log")?;
            for (id, (name, format)) in OBSERVED.iter().enumerate() {
                let rc = unsafe { (api.mpv_observe_property)(handle, id as u64, name.as_ptr(), *format) };
                mpv.check(rc, "observe")?;
            }
            Ok(())
        };
        if let Err(e) = configure() {
            unsafe { (api.mpv_terminate_destroy)(handle) };
            return Err(e);
        }

        let thread_mpv = Arc::clone(&mpv);
        let event_thread = std::thread::Builder::new()
            .name("wplayer-mpv-events".into())
            .spawn(move || event_loop(&thread_mpv, listener.as_ref()))
            .map_err(CoreError::from)?;

        Ok(Arc::new(Self {
            mpv,
            video_output: config.video_output,
            destroyed: AtomicBool::new(false),
            event_thread: Mutex::new(Some(event_thread)),
        }))
    }

    /// Starts rendering into the surface returned by `SurfaceBridge.acquire`.
    pub fn attach_surface(&self, surface_ref: i64) -> CoreResult<()> {
        self.mpv.set_option_i64("wid", surface_ref)?;
        self.mpv.set_option("force-window", "yes")?;
        self.mpv.set_property("vo", &self.video_output)
    }

    /// Stops rendering. Call before releasing the surface reference.
    pub fn detach_surface(&self) -> CoreResult<()> {
        self.mpv.set_property("vo", "null")?;
        self.mpv.set_option("force-window", "no")?;
        self.mpv.set_option_i64("wid", 0)
    }

    pub fn set_surface_size(&self, width: u32, height: u32) -> CoreResult<()> {
        self.mpv
            .set_property("android-surface-size", &format!("{width}x{height}"))
    }

    /// Opens `uri` (a `wplayer://` media URI or any URL mpv understands).
    pub fn load(&self, uri: String, start_seconds: Option<f64>) -> CoreResult<()> {
        let start = start_seconds.map_or_else(|| "none".to_owned(), |s| format!("{s:.3}"));
        self.mpv.set_property("start", &start)?;
        self.mpv.command(&["loadfile", uri.as_str(), "replace"])
    }

    /// Unloads the current file; playback state is reset by the following events.
    pub fn stop(&self) -> CoreResult<()> {
        self.mpv.command(&["stop"])
    }

    pub fn set_paused(&self, paused: bool) -> CoreResult<()> {
        self.mpv.set_flag("pause", paused)
    }

    pub fn seek(&self, seconds: f64, mode: SeekMode, precise: bool) -> CoreResult<()> {
        let flags = match (mode, precise) {
            (SeekMode::Absolute, true) => "absolute+exact",
            (SeekMode::Absolute, false) => "absolute+keyframes",
            (SeekMode::Relative, true) => "relative+exact",
            (SeekMode::Relative, false) => "relative+keyframes",
        };
        self.mpv.command(&["seek", &seconds.to_string(), flags])
    }

    pub fn set_speed(&self, speed: f64) -> CoreResult<()> {
        self.mpv.set_double("speed", speed)
    }

    /// Sets or clears (`None`) the A-B repeat points, in seconds.
    pub fn set_ab_loop(&self, a: Option<f64>, b: Option<f64>) -> CoreResult<()> {
        let fmt = |v: Option<f64>| v.map_or_else(|| "no".to_owned(), |s| format!("{s:.3}"));
        self.mpv.set_property("ab-loop-a", &fmt(a))?;
        self.mpv.set_property("ab-loop-b", &fmt(b))
    }

    /// Selects a track by mpv id, or disables the kind when `id` is `None`.
    pub fn select_track(&self, kind: TrackKind, id: Option<i64>) -> CoreResult<()> {
        let value = id.map_or_else(|| "no".to_owned(), |id| id.to_string());
        self.mpv.set_property(kind.property(), &value)
    }

    /// Adds an external subtitle; `select` makes it the active track.
    pub fn add_subtitle(&self, uri: String, select: bool) -> CoreResult<()> {
        self.mpv
            .command(&["sub-add", uri.as_str(), if select { "select" } else { "auto" }])
    }

    /// Shows a subtitle file that is still being written: added as `title`
    /// the first time, reloaded from disk after that.
    pub fn show_growing_subtitle(&self, path: String, title: String, select: bool) -> CoreResult<()> {
        let loaded = self
            .mpv
            .get_string("track-list")
            .and_then(|json| tracks::external_track_id(&json, &path));
        match loaded {
            Some(id) => self.mpv.command(&["sub-reload", &id.to_string()]),
            None => self.mpv.command(&[
                "sub-add",
                path.as_str(),
                if select { "select" } else { "auto" },
                title.as_str(),
            ]),
        }
    }

    pub fn set_subtitle_delay(&self, seconds: f64) -> CoreResult<()> {
        self.mpv.set_double("sub-delay", seconds)
    }

    /// Seeks to the start of the next (`lines` > 0) or previous subtitle line.
    pub fn step_subtitle(&self, lines: i32) -> CoreResult<()> {
        self.mpv.command(&["sub-seek", lines.to_string().as_str()])
    }

    /// Starts or stops reporting [STATS] through the listener.
    pub fn observe_stats(&self, enabled: bool) -> CoreResult<()> {
        for (index, (name, format)) in STATS.iter().enumerate() {
            let id = STATS_ID_BASE + index as u64;
            let rc = if enabled {
                unsafe { (self.mpv.api.mpv_observe_property)(self.mpv.handle, id, name.as_ptr(), *format) }
            } else {
                unsafe { (self.mpv.api.mpv_unobserve_property)(self.mpv.handle, id) }
            };
            // Unobserving returns how many observers it removed, not an error code.
            if enabled {
                self.mpv.check(rc, "observe stats")?;
            }
        }
        Ok(())
    }

    /// Escape hatch for mpv properties without a dedicated method.
    pub fn set_property(&self, name: String, value: String) -> CoreResult<()> {
        self.mpv.set_property(&name, &value)
    }

    pub fn get_property(&self, name: String) -> Option<String> {
        self.mpv.get_string(&name)
    }

    /// Shuts the core down and joins the event thread. Idempotent.
    pub fn shutdown(&self) {
        if self.destroyed.swap(true, Ordering::AcqRel) {
            return;
        }
        if let Err(e) = self.mpv.command(&["quit"]) {
            log::warn!("quit failed: {e}");
        }
        if let Some(thread) = self.event_thread.lock().unwrap_or_else(PoisonError::into_inner).take() {
            let _ = thread.join();
        }
        unsafe { (self.mpv.api.mpv_terminate_destroy)(self.mpv.handle) };
    }
}

impl Drop for Player {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn apply_defaults(mpv: &Mpv, config: &PlayerConfig) -> CoreResult<()> {
    let defaults = [
        ("vo", "null"),
        ("gpu-context", "android"),
        ("opengl-es", "yes"),
        ("hwdec", config.hwdec.as_str()),
        ("hwdec-codecs", "all"),
        ("ao", "audiotrack,opensles"),
        ("idle", "yes"),
        ("keep-open", "yes"),
        ("force-window", "no"),
        ("input-default-bindings", "no"),
        ("cache", "yes"),
        ("demuxer-max-bytes", "64MiB"),
        ("demuxer-max-back-bytes", "32MiB"),
        ("sub-ass-override", "no"),
        ("screenshot-dir", config.data_dir.as_str()),
        ("config", "no"),
    ];
    for (name, value) in defaults {
        mpv.set_option(name, value)?;
    }
    Ok(())
}

fn event_loop(mpv: &Mpv, listener: &dyn PlayerListener) {
    loop {
        let event = unsafe { &*(mpv.api.mpv_wait_event)(mpv.handle, -1.0) };
        match event.event_id {
            MPV_EVENT_NONE => {}
            MPV_EVENT_SHUTDOWN => {
                listener.on_event(PlayerEvent::Shutdown);
                return;
            }
            MPV_EVENT_PROPERTY_CHANGE => {
                let property = unsafe { &*event.data.cast::<mpv_event_property>() };
                let name = unsafe { CStr::from_ptr(property.name) }.to_string_lossy();
                if name == "track-list" {
                    let tracks = match property_value(property) {
                        PropertyValue::Text(json) => tracks::parse(&json),
                        _ => Vec::new(),
                    };
                    listener.on_tracks_changed(tracks);
                } else {
                    listener.on_property_changed(name.into_owned(), property_value(property));
                }
            }
            MPV_EVENT_END_FILE => {
                let end = unsafe { &*event.data.cast::<mpv_event_end_file>() };
                let reason = match end.reason {
                    MPV_END_FILE_REASON_EOF => EndReason::Eof,
                    MPV_END_FILE_REASON_STOP => EndReason::Stopped,
                    MPV_END_FILE_REASON_QUIT => EndReason::Quit,
                    MPV_END_FILE_REASON_ERROR => EndReason::Error,
                    _ => EndReason::Other,
                };
                let error = (end.error < 0).then(|| error_string(mpv.api, end.error));
                listener.on_event(PlayerEvent::EndFile { reason, error });
            }
            MPV_EVENT_LOG_MESSAGE => {
                let msg = unsafe { &*event.data.cast::<mpv_event_log_message>() };
                let text = |p: *const c_char| unsafe { CStr::from_ptr(p) }.to_string_lossy().trim_end().to_owned();
                let (level, prefix, body) = (text(msg.level), text(msg.prefix), text(msg.text));
                log::info!("mpv[{prefix}] {level}: {body}");
                listener.on_event(PlayerEvent::Log {
                    level,
                    prefix,
                    text: body,
                });
            }
            MPV_EVENT_START_FILE => listener.on_event(PlayerEvent::StartFile),
            MPV_EVENT_FILE_LOADED => listener.on_event(PlayerEvent::FileLoaded),
            MPV_EVENT_PLAYBACK_RESTART => listener.on_event(PlayerEvent::PlaybackRestart),
            MPV_EVENT_SEEK => listener.on_event(PlayerEvent::Seek),
            MPV_EVENT_VIDEO_RECONFIG => listener.on_event(PlayerEvent::VideoReconfig),
            _ => {}
        }
    }
}

fn property_value(property: &mpv_event_property) -> PropertyValue {
    // SAFETY: `data` points to a value of `format` for the lifetime of the event.
    unsafe {
        match property.format {
            MPV_FORMAT_FLAG => PropertyValue::Flag(*property.data.cast::<c_int>() != 0),
            MPV_FORMAT_INT64 => PropertyValue::Int(*property.data.cast::<i64>()),
            MPV_FORMAT_DOUBLE => PropertyValue::Double(*property.data.cast::<f64>()),
            MPV_FORMAT_STRING => {
                let s = *property.data.cast::<*const c_char>();
                PropertyValue::Text(CStr::from_ptr(s).to_string_lossy().into_owned())
            }
            _ => PropertyValue::Unavailable,
        }
    }
}
