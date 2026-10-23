//! libmpv stream callbacks that serve `wplayer://` (media) and `wplayersub://`
//! (text subtitles, re-encoded to UTF-8) URIs from registered sources.

use std::ffi::{CStr, c_char, c_int, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, PoisonError};

use super::ffi::*;
use crate::error::CoreResult;
use crate::runtime;
use crate::source;
use crate::subtitle;
use crate::vfs::RandomReader;

/// A fully buffered stream, used for converted subtitles.
struct MemoryReader(Vec<u8>);

impl RandomReader for MemoryReader {
    fn size(&self) -> Option<u64> {
        Some(self.0.len() as u64)
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        let start = (offset as usize).min(self.0.len());
        let n = buf.len().min(self.0.len() - start);
        buf[..n].copy_from_slice(&self.0[start..start + n]);
        Ok(n)
    }
}

/// Subtitles are read whole; anything larger is not a text subtitle.
const MAX_SUBTITLE_BYTES: u64 = 32 * 1024 * 1024;

pub(super) fn register(api: &MpvApi, handle: *mut mpv_handle) -> Result<(), String> {
    for (protocol, open_fn) in [
        (c"wplayer", open_media as mpv_stream_cb_open_ro_fn),
        (c"wplayersub", open_subtitle as mpv_stream_cb_open_ro_fn),
    ] {
        // SAFETY: handle is a live mpv handle; open_fn has the stream_cb.h signature.
        let rc = unsafe { (api.mpv_stream_cb_add_ro)(handle, protocol.as_ptr(), ptr::null_mut(), open_fn) };
        if rc < 0 {
            return Err(error_string(api, rc));
        }
    }
    Ok(())
}

/// State behind one opened stream. libmpv calls `read`/`seek`/`size` from the
/// demuxer thread and `cancel` from another thread, so all access is via `&self`.
struct StreamCookie {
    reader: Mutex<Box<dyn RandomReader>>,
    position: AtomicU64,
    cancelled: AtomicBool,
}

fn cookie_for(reader: Box<dyn RandomReader>) -> Box<StreamCookie> {
    Box::new(StreamCookie {
        reader: Mutex::new(reader),
        position: AtomicU64::new(0),
        cancelled: AtomicBool::new(false),
    })
}

fn open_media_uri(uri: &str) -> CoreResult<Box<StreamCookie>> {
    let (source_id, path) = source::parse_uri(source::MEDIA_SCHEME, uri)?;
    Ok(cookie_for(source::get(&source_id)?.open(&path)?))
}

fn open_subtitle_uri(uri: &str) -> CoreResult<Box<StreamCookie>> {
    let (source_id, path) = source::parse_uri(source::SUBTITLE_SCHEME, uri)?;
    let mut reader = source::get(&source_id)?.open(&path)?;
    let size = reader.size().unwrap_or(MAX_SUBTITLE_BYTES).min(MAX_SUBTITLE_BYTES);
    let mut bytes = vec![0u8; size as usize];
    let mut filled = 0;
    while filled < bytes.len() {
        let n = reader.read_at(filled as u64, &mut bytes[filled..])?;
        if n == 0 {
            break;
        }
        filled += n;
    }
    bytes.truncate(filled);
    let text = subtitle::to_utf8(&bytes);
    // FFmpeg's SAMI demuxer shows every language of a file at once; hand libmpv
    // SubRip with the Korean lines instead. A file that is not SAMI stays as it is.
    let text = match path.rsplit('.').next().map(str::to_ascii_lowercase).as_deref() {
        Some("smi" | "sami") => std::str::from_utf8(&text)
            .ok()
            .and_then(subtitle::sami::to_srt)
            .map_or(text, String::into_bytes),
        _ => text,
    };
    Ok(cookie_for(Box::new(MemoryReader(text))))
}

unsafe extern "C" fn open_media(user_data: *mut c_void, uri: *mut c_char, info: *mut mpv_stream_cb_info) -> c_int {
    unsafe { open_with(open_media_uri, user_data, uri, info) }
}

unsafe extern "C" fn open_subtitle(user_data: *mut c_void, uri: *mut c_char, info: *mut mpv_stream_cb_info) -> c_int {
    unsafe { open_with(open_subtitle_uri, user_data, uri, info) }
}

/// # Safety
/// Arguments must be those libmpv passed to an open callback.
unsafe fn open_with(
    open: fn(&str) -> CoreResult<Box<StreamCookie>>,
    _user_data: *mut c_void,
    uri: *mut c_char,
    info: *mut mpv_stream_cb_info,
) -> c_int {
    let _runtime = runtime::enter();
    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: libmpv passes a NUL-terminated URI valid for this call.
        let uri = unsafe { CStr::from_ptr(uri) }.to_string_lossy();
        open(&uri).inspect_err(|e| log::warn!("cannot open {uri}: {e}"))
    }));
    let Ok(Ok(cookie)) = result else {
        return MPV_ERROR_LOADING_FAILED;
    };
    // SAFETY: info points to a struct libmpv owns for the duration of this call.
    unsafe {
        *info = mpv_stream_cb_info {
            cookie: Box::into_raw(cookie).cast(),
            read_fn: Some(read),
            seek_fn: Some(seek),
            size_fn: Some(size),
            close_fn: Some(close),
            cancel_fn: Some(cancel),
        };
    }
    MPV_ERROR_SUCCESS
}

/// # Safety
/// `cookie` must come from `open_ro` and not yet be closed.
unsafe fn cookie<'a>(cookie: *mut c_void) -> &'a StreamCookie {
    unsafe { &*cookie.cast::<StreamCookie>() }
}

unsafe extern "C" fn read(cookie_ptr: *mut c_void, buf: *mut c_char, nbytes: u64) -> i64 {
    let state = unsafe { cookie(cookie_ptr) };
    if state.cancelled.load(Ordering::Acquire) {
        return -1;
    }
    // SAFETY: libmpv guarantees `buf` has room for `nbytes` bytes.
    let buf = unsafe { std::slice::from_raw_parts_mut(buf.cast::<u8>(), nbytes as usize) };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let offset = state.position.load(Ordering::Acquire);
        let mut reader = state.reader.lock().unwrap_or_else(PoisonError::into_inner);
        reader.read_at(offset, buf)
    }));
    match result {
        Ok(Ok(n)) => {
            state.position.fetch_add(n as u64, Ordering::AcqRel);
            n as i64
        }
        Ok(Err(e)) => {
            log::warn!("stream read failed: {e}");
            -1
        }
        Err(_) => -1,
    }
}

unsafe extern "C" fn seek(cookie_ptr: *mut c_void, offset: i64) -> i64 {
    let state = unsafe { cookie(cookie_ptr) };
    if offset < 0 || state.cancelled.load(Ordering::Acquire) {
        return MPV_ERROR_GENERIC as i64;
    }
    state.position.store(offset as u64, Ordering::Release);
    offset
}

unsafe extern "C" fn size(cookie_ptr: *mut c_void) -> i64 {
    let state = unsafe { cookie(cookie_ptr) };
    let size = catch_unwind(AssertUnwindSafe(|| {
        state.reader.lock().unwrap_or_else(PoisonError::into_inner).size()
    }));
    match size {
        Ok(Some(size)) => size as i64,
        _ => MPV_ERROR_UNSUPPORTED as i64,
    }
}

unsafe extern "C" fn close(cookie_ptr: *mut c_void) {
    // Readers may spawn cleanup (e.g. closing an SMB handle) as they drop.
    let _runtime = runtime::enter();
    // SAFETY: libmpv calls close exactly once, after all other callbacks.
    let state = unsafe { Box::from_raw(cookie_ptr.cast::<StreamCookie>()) };
    if catch_unwind(AssertUnwindSafe(move || drop(state))).is_err() {
        log::warn!("panic while closing a stream");
    }
}

unsafe extern "C" fn cancel(cookie_ptr: *mut c_void) {
    let state = unsafe { cookie(cookie_ptr) };
    state.cancelled.store(true, Ordering::Release);
}
