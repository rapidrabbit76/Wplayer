//! Seek-bar previews: a paused, headless mpv kept open on the file being
//! played. Each request seeks it to a keyframe; vo_image writes the frame it
//! then shows as a JPEG. Keeping it open avoids reopening (and, for network
//! sources, reconnecting and re-reading the index) on every request.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use super::Mpv;
use super::ffi::*;
use super::thumbnail::start_image_output;
use crate::error::{CoreError, CoreResult};

#[derive(uniffi::Object)]
pub struct SeekPreview {
    /// Also keeps event waiting to one caller at a time.
    mpv: Mutex<Mpv>,
    out_dir: PathBuf,
}

#[uniffi::export]
impl SeekPreview {
    /// Opens `uri` for previews `width` pixels wide, written into the empty directory `out_dir`.
    /// Blocks until the file is open; call from a background thread.
    #[uniffi::constructor]
    pub fn new(uri: String, out_dir: String, width: u32, timeout_ms: u32) -> CoreResult<std::sync::Arc<Self>> {
        let api = super::ffi::api().map_err(|detail| CoreError::Player { detail })?;
        let handle = unsafe { (api.mpv_create)() };
        if handle.is_null() {
            return Err(CoreError::Player {
                detail: "mpv_create failed".into(),
            });
        }
        let mpv = Mpv { api, handle };
        let open = || -> CoreResult<()> {
            start_image_output(
                &mpv,
                &out_dir,
                width,
                &[("pause", "yes".to_owned()), ("keep-open", "always".to_owned())],
            )?;
            mpv.command(&["loadfile", uri.as_str()])?;
            wait_for(
                &mpv,
                MPV_EVENT_PLAYBACK_RESTART,
                Duration::from_millis(timeout_ms.into()),
            )
        };
        if let Err(e) = open() {
            unsafe { (api.mpv_terminate_destroy)(handle) };
            return Err(e);
        }
        Ok(std::sync::Arc::new(Self {
            mpv: Mutex::new(mpv),
            out_dir: PathBuf::from(out_dir),
        }))
    }

    /// Writes the frame at the keyframe nearest `seconds` and returns its path.
    /// The caller owns (and should delete) the file. Blocks.
    pub fn frame_at(&self, seconds: f64, timeout_ms: u32) -> CoreResult<String> {
        let mpv = self.mpv.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let before = newest_image(&self.out_dir);
        mpv.command(&["seek", &format!("{:.3}", seconds.max(0.0)), "absolute+keyframes"])?;
        let timeout = Duration::from_millis(timeout_ms.into());
        let deadline = Instant::now() + timeout;
        wait_for(&mpv, MPV_EVENT_PLAYBACK_RESTART, timeout)?;
        // The image is written while the frame is shown, normally before the restart event.
        loop {
            if let Some(newest) = newest_image(&self.out_dir).filter(|newest| Some(newest) != before.as_ref()) {
                return Ok(newest.to_string_lossy().into_owned());
            }
            if Instant::now() >= deadline {
                return Err(CoreError::Connection {
                    detail: "preview timed out".into(),
                });
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

impl Drop for SeekPreview {
    fn drop(&mut self) {
        let mpv = self.mpv.get_mut().unwrap_or_else(std::sync::PoisonError::into_inner);
        // Also cancels a network read still in progress (the stream's cancel callback).
        unsafe { (mpv.api.mpv_terminate_destroy)(mpv.handle) };
    }
}

/// Waits for `event_id`; a failed load (END_FILE) or the timeout is an error.
fn wait_for(mpv: &Mpv, event_id: i32, timeout: Duration) -> CoreResult<()> {
    let deadline = Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(CoreError::Connection {
                detail: "preview timed out".into(),
            });
        }
        let event = unsafe { &*(mpv.api.mpv_wait_event)(mpv.handle, remaining.as_secs_f64()) };
        match event.event_id {
            id if id == event_id => return Ok(()),
            MPV_EVENT_END_FILE => {
                let end = unsafe { &*event.data.cast::<mpv_event_end_file>() };
                return Err(CoreError::Player {
                    detail: format!("preview: {}", error_string(mpv.api, end.error)),
                });
            }
            _ => {}
        }
    }
}

/// vo_image numbers its files (`00000001.jpg`, ...), so the newest sorts last.
fn newest_image(dir: &Path) -> Option<PathBuf> {
    std::fs::read_dir(dir)
        .ok()?
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|path| path.extension().is_some_and(|ext| ext == "jpg"))
        .max()
}
