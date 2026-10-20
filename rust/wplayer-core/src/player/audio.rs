//! Decodes a file's audio into raw PCM for speech recognition: a headless mpv
//! with `ao=pcm`, which runs as fast as it can decode rather than in real time.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread::JoinHandle;

use super::ffi::*;
use super::{Mpv, stream};
use crate::error::{CoreError, CoreResult};

/// Samples per second of the decoded audio: what whisper expects.
pub(crate) const PCM_SAMPLE_RATE: usize = 16_000;

/// A running decode into a file of mono, signed 16-bit little-endian samples.
pub(crate) struct AudioDump {
    state: Arc<DumpState>,
    thread: Option<JoinHandle<()>>,
}

#[derive(Default)]
struct DumpState {
    stop: AtomicBool,
    done: AtomicBool,
    error: Mutex<Option<CoreError>>,
}

impl AudioDump {
    /// Starts decoding `uri` from `start_seconds` to `end_seconds` (None: the
    /// end) into `out`, using mpv's audio track `audio_track` (None: the default one).
    pub(crate) fn start(
        uri: &str,
        out: &Path,
        start_seconds: f64,
        end_seconds: Option<f64>,
        audio_track: Option<i64>,
    ) -> CoreResult<Self> {
        let api = super::ffi::api().map_err(|detail| CoreError::Player { detail })?;
        let handle = unsafe { (api.mpv_create)() };
        if handle.is_null() {
            return Err(CoreError::Player {
                detail: "mpv_create failed".into(),
            });
        }
        let mpv = Mpv { api, handle };
        if let Err(e) =
            configure(&mpv, out, start_seconds, end_seconds, audio_track).and_then(|()| mpv.command(&["loadfile", uri]))
        {
            unsafe { (api.mpv_terminate_destroy)(handle) };
            return Err(e);
        }
        let state = Arc::new(DumpState::default());
        let thread_state = Arc::clone(&state);
        let thread = std::thread::Builder::new()
            .name("audio-dump".into())
            .spawn(move || wait_for_end(mpv, &thread_state))?;
        Ok(Self {
            state,
            thread: Some(thread),
        })
    }

    /// Whether all the audio is in the file (or decoding failed).
    pub(crate) fn is_done(&self) -> bool {
        self.state.done.load(Ordering::Acquire)
    }

    /// Waits for the decode to end and reports how it ended.
    pub(crate) fn finish(mut self) -> CoreResult<()> {
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
        self.state
            .error
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take()
            .map_or(Ok(()), Err)
    }
}

impl Drop for AudioDump {
    fn drop(&mut self) {
        self.state.stop.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

fn configure(
    mpv: &Mpv,
    out: &Path,
    start_seconds: f64,
    end_seconds: Option<f64>,
    audio_track: Option<i64>,
) -> CoreResult<()> {
    let options = [
        ("config", "no".to_owned()),
        ("terminal", "no".to_owned()),
        ("idle", "yes".to_owned()),
        ("vo", "null".to_owned()),
        ("vid", "no".to_owned()),
        ("sid", "no".to_owned()),
        ("aid", audio_track.map_or("auto".to_owned(), |id| id.to_string())),
        ("ao", "pcm".to_owned()),
        ("ao-pcm-file", out.to_string_lossy().into_owned()),
        ("ao-pcm-waveheader", "no".to_owned()),
        ("audio-format", "s16".to_owned()),
        ("audio-samplerate", PCM_SAMPLE_RATE.to_string()),
        ("audio-channels", "mono".to_owned()),
        ("start", format!("{start_seconds:.3}")),
        ("end", end_seconds.map_or("none".to_owned(), |end| format!("{end:.3}"))),
        ("hr-seek", "yes".to_owned()),
    ];
    for (name, value) in options {
        mpv.set_option(name, &value)?;
    }
    mpv.check(unsafe { (mpv.api.mpv_initialize)(mpv.handle) }, "mpv_initialize")?;
    stream::register(mpv.api, mpv.handle).map_err(|detail| CoreError::Player { detail })
}

/// Runs on the dump's thread until the file ends, fails or the dump is dropped.
fn wait_for_end(mpv: Mpv, state: &DumpState) {
    let result = loop {
        if state.stop.load(Ordering::Acquire) {
            break Err(CoreError::Cancelled);
        }
        let event = unsafe { &*(mpv.api.mpv_wait_event)(mpv.handle, 0.25) };
        if event.event_id != MPV_EVENT_END_FILE {
            continue;
        }
        let end = unsafe { &*event.data.cast::<mpv_event_end_file>() };
        break if end.reason == MPV_END_FILE_REASON_EOF {
            Ok(())
        } else {
            Err(CoreError::Player {
                detail: format!("audio decoding: {}", error_string(mpv.api, end.error)),
            })
        };
    };
    // Also cancels a network read that is still waiting (the stream's cancel callback).
    unsafe { (mpv.api.mpv_terminate_destroy)(mpv.handle) };
    if let Err(e) = result {
        *state.error.lock().unwrap_or_else(PoisonError::into_inner) = Some(e);
    }
    state.done.store(true, Ordering::Release);
}
