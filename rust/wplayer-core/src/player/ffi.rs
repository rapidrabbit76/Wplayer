//! Minimal libmpv client API (mpv/client.h, mpv/stream_cb.h; API 2.5, mpv 0.41).
//!
//! libmpv is resolved at runtime so the core builds and its tests run on hosts
//! without libmpv. Only the declarations this crate uses are mirrored here.

#![allow(non_camel_case_types)]

use std::ffi::{CStr, c_char, c_double, c_int, c_ulong, c_void};
use std::sync::OnceLock;

use libloading::Library;

#[repr(C)]
pub struct mpv_handle {
    _private: [u8; 0],
}

pub const MPV_ERROR_SUCCESS: c_int = 0;
pub const MPV_ERROR_LOADING_FAILED: c_int = -13;
pub const MPV_ERROR_UNSUPPORTED: c_int = -18;
pub const MPV_ERROR_GENERIC: c_int = -20;

pub const MPV_FORMAT_STRING: c_int = 1;
pub const MPV_FORMAT_FLAG: c_int = 3;
pub const MPV_FORMAT_INT64: c_int = 4;
pub const MPV_FORMAT_DOUBLE: c_int = 5;

pub const MPV_EVENT_NONE: c_int = 0;
pub const MPV_EVENT_SHUTDOWN: c_int = 1;
pub const MPV_EVENT_LOG_MESSAGE: c_int = 2;
pub const MPV_EVENT_START_FILE: c_int = 6;
pub const MPV_EVENT_END_FILE: c_int = 7;
pub const MPV_EVENT_FILE_LOADED: c_int = 8;
pub const MPV_EVENT_VIDEO_RECONFIG: c_int = 17;
pub const MPV_EVENT_SEEK: c_int = 20;
pub const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;
pub const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

pub const MPV_END_FILE_REASON_EOF: c_int = 0;
pub const MPV_END_FILE_REASON_STOP: c_int = 2;
pub const MPV_END_FILE_REASON_QUIT: c_int = 3;
pub const MPV_END_FILE_REASON_ERROR: c_int = 4;

#[repr(C)]
pub struct mpv_event {
    pub event_id: c_int,
    pub error: c_int,
    pub reply_userdata: u64,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct mpv_event_property {
    pub name: *const c_char,
    pub format: c_int,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct mpv_event_log_message {
    pub prefix: *const c_char,
    pub level: *const c_char,
    pub text: *const c_char,
    pub log_level: c_int,
}

#[repr(C)]
pub struct mpv_event_end_file {
    pub reason: c_int,
    pub error: c_int,
    pub playlist_entry_id: i64,
    pub playlist_insert_id: i64,
    pub playlist_insert_num_entries: c_int,
}

pub type mpv_stream_cb_read_fn = unsafe extern "C" fn(*mut c_void, *mut c_char, u64) -> i64;
pub type mpv_stream_cb_seek_fn = unsafe extern "C" fn(*mut c_void, i64) -> i64;
pub type mpv_stream_cb_size_fn = unsafe extern "C" fn(*mut c_void) -> i64;
pub type mpv_stream_cb_close_fn = unsafe extern "C" fn(*mut c_void);
pub type mpv_stream_cb_cancel_fn = unsafe extern "C" fn(*mut c_void);

#[repr(C)]
pub struct mpv_stream_cb_info {
    pub cookie: *mut c_void,
    pub read_fn: Option<mpv_stream_cb_read_fn>,
    pub seek_fn: Option<mpv_stream_cb_seek_fn>,
    pub size_fn: Option<mpv_stream_cb_size_fn>,
    pub close_fn: Option<mpv_stream_cb_close_fn>,
    pub cancel_fn: Option<mpv_stream_cb_cancel_fn>,
}

pub type mpv_stream_cb_open_ro_fn = unsafe extern "C" fn(*mut c_void, *mut c_char, *mut mpv_stream_cb_info) -> c_int;

macro_rules! mpv_api {
    ($($name:ident: fn($($arg:ty),*) $(-> $ret:ty)?;)*) => {
        pub struct MpvApi {
            _lib: Library,
            $(pub $name: unsafe extern "C" fn($($arg),*) $(-> $ret)?,)*
        }

        impl MpvApi {
            unsafe fn load(lib: Library) -> Result<Self, libloading::Error> {
                unsafe {
                    Ok(Self {
                        $($name: *lib.get(concat!(stringify!($name), "\0").as_bytes())?,)*
                        _lib: lib,
                    })
                }
            }
        }
    };
}

mpv_api! {
    mpv_client_api_version: fn() -> c_ulong;
    mpv_error_string: fn(c_int) -> *const c_char;
    mpv_free: fn(*mut c_void);
    mpv_create: fn() -> *mut mpv_handle;
    mpv_initialize: fn(*mut mpv_handle) -> c_int;
    mpv_terminate_destroy: fn(*mut mpv_handle);
    mpv_set_option: fn(*mut mpv_handle, *const c_char, c_int, *mut c_void) -> c_int;
    mpv_set_option_string: fn(*mut mpv_handle, *const c_char, *const c_char) -> c_int;
    mpv_set_property: fn(*mut mpv_handle, *const c_char, c_int, *mut c_void) -> c_int;
    mpv_set_property_string: fn(*mut mpv_handle, *const c_char, *const c_char) -> c_int;
    mpv_get_property_string: fn(*mut mpv_handle, *const c_char) -> *mut c_char;
    mpv_command: fn(*mut mpv_handle, *mut *const c_char) -> c_int;
    mpv_observe_property: fn(*mut mpv_handle, u64, *const c_char, c_int) -> c_int;
    mpv_unobserve_property: fn(*mut mpv_handle, u64) -> c_int;
    mpv_request_log_messages: fn(*mut mpv_handle, *const c_char) -> c_int;
    mpv_wait_event: fn(*mut mpv_handle, c_double) -> *mut mpv_event;
    mpv_stream_cb_add_ro: fn(*mut mpv_handle, *const c_char, *mut c_void, mpv_stream_cb_open_ro_fn) -> c_int;
}

static API: OnceLock<Result<MpvApi, String>> = OnceLock::new();

/// Loads `libmpv.so` once per process.
pub fn api() -> Result<&'static MpvApi, String> {
    API.get_or_init(|| {
        // SAFETY: libmpv has no initialization routines with preconditions.
        let lib = unsafe { Library::new("libmpv.so") }.map_err(|e| e.to_string())?;
        // SAFETY: the signatures above mirror mpv/client.h for API 2.x.
        let api = unsafe { MpvApi::load(lib) }.map_err(|e| e.to_string())?;
        let version = unsafe { (api.mpv_client_api_version)() };
        if version >> 16 != 2 {
            return Err(format!(
                "unsupported libmpv client API {}.{}",
                version >> 16,
                version & 0xffff
            ));
        }
        Ok(api)
    })
    .as_ref()
    .map_err(Clone::clone)
}

pub fn error_string(api: &MpvApi, code: c_int) -> String {
    // SAFETY: mpv_error_string returns a static string for any code.
    unsafe { CStr::from_ptr((api.mpv_error_string)(code)) }
        .to_string_lossy()
        .into_owned()
}
