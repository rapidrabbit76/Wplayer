//! Video thumbnails: a short-lived, headless mpv decodes one frame and writes
//! it as a JPEG. Going through mpv means every source protocol (`wplayer://`)
//! and codec the player supports works here too.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use super::ffi::*;
use super::{Mpv, stream};
use crate::error::{CoreError, CoreResult};

/// Writes one frame of `uri`, taken at `position_percent` of its length and
/// scaled to `width` pixels wide, as a JPEG in the empty directory `out_dir`.
/// Returns the file's path. Blocks; call from a background thread.
#[uniffi::export]
pub fn render_thumbnail(
    uri: String,
    out_dir: String,
    width: u32,
    position_percent: u32,
    timeout_ms: u32,
) -> CoreResult<String> {
    let api = super::ffi::api().map_err(|detail| CoreError::Player { detail })?;
    let handle = unsafe { (api.mpv_create)() };
    if handle.is_null() {
        return Err(CoreError::Player {
            detail: "mpv_create failed".into(),
        });
    }
    let mpv = Mpv { api, handle };
    let result = render(
        &mpv,
        &uri,
        &out_dir,
        width,
        position_percent,
        Duration::from_millis(timeout_ms.into()),
    );
    // Also cancels a network read that is still waiting (the stream's cancel callback).
    unsafe { (api.mpv_terminate_destroy)(handle) };
    result
}

/**
 * Sets up `mpv` to decode video only and write each displayed frame as a JPEG,
 * `width` pixels wide, into `out_dir`; then starts it. Shared with the seek preview.
 */
pub(super) fn start_image_output(mpv: &Mpv, out_dir: &str, width: u32, extra: &[(&str, String)]) -> CoreResult<()> {
    let options = [
        ("config", "no".to_owned()),
        ("terminal", "no".to_owned()),
        ("idle", "yes".to_owned()),
        ("vo", "image".to_owned()),
        ("vo-image-format", "jpg".to_owned()),
        ("vo-image-jpeg-quality", "80".to_owned()),
        ("vo-image-outdir", out_dir.to_owned()),
        ("ao", "null".to_owned()),
        ("aid", "no".to_owned()),
        ("sid", "no".to_owned()),
        // Software decoding keeps the hardware decoder free for the player.
        ("hwdec", "no".to_owned()),
        // Keyframes only: much faster, and close enough for a preview image.
        ("hr-seek", "no".to_owned()),
        ("cache", "no".to_owned()),
        ("vf", format!("lavfi=[scale={width}:-2]")),
    ];
    for (name, value) in options.iter().chain(extra) {
        mpv.set_option(name, value)?;
    }
    mpv.check(unsafe { (mpv.api.mpv_initialize)(mpv.handle) }, "mpv_initialize")?;
    stream::register(mpv.api, mpv.handle).map_err(|detail| CoreError::Player { detail })
}

fn render(
    mpv: &Mpv,
    uri: &str,
    out_dir: &str,
    width: u32,
    position_percent: u32,
    timeout: Duration,
) -> CoreResult<String> {
    let extra = [
        ("frames", "1".to_owned()),
        ("start", format!("{}%", position_percent.min(95))),
    ];
    start_image_output(mpv, out_dir, width, &extra)?;
    mpv.command(&["loadfile", uri])?;

    let deadline = Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(CoreError::Connection {
                detail: "thumbnail timed out".into(),
            });
        }
        let event = unsafe { &*(mpv.api.mpv_wait_event)(mpv.handle, remaining.as_secs_f64()) };
        if event.event_id != MPV_EVENT_END_FILE {
            continue;
        }
        let end = unsafe { &*event.data.cast::<mpv_event_end_file>() };
        if end.reason != MPV_END_FILE_REASON_EOF {
            let detail = error_string(mpv.api, end.error);
            return Err(CoreError::Player {
                detail: format!("thumbnail: {detail}"),
            });
        }
        return first_image(Path::new(out_dir));
    }
}

/// vo_image names its files `00000001.jpg` and so on; the one frame asked for is the first.
fn first_image(dir: &Path) -> CoreResult<String> {
    let mut images: Vec<PathBuf> = std::fs::read_dir(dir)?
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|path| path.extension().is_some_and(|ext| ext == "jpg"))
        .collect();
    images.sort();
    images
        .into_iter()
        .next()
        .map(|path| path.to_string_lossy().into_owned())
        .ok_or_else(|| CoreError::Unsupported {
            detail: "no video frame".into(),
        })
}
