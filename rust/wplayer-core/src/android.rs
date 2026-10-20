//! JNI entry points that UniFFI (JNA-based) cannot express.

use std::ffi::{c_int, c_void};
use std::ptr;

use jni_sys::{JNI_VERSION_1_6, JNIEnv, JavaVM, jclass, jint, jlong, jobject};
use libloading::Library;

/// Runs when Kotlin calls `System.loadLibrary("wplayer_core")`.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    crate::logging::init();
    register_vm_with_ffmpeg(vm);
    JNI_VERSION_1_6
}

/// FFmpeg's MediaCodec decoder and mpv's Android outputs find the JavaVM
/// through `av_jni_get_java_vm`, so it must be registered before playback.
fn register_vm_with_ffmpeg(vm: *mut JavaVM) {
    type SetJavaVm = unsafe extern "C" fn(*mut c_void, *mut c_void) -> c_int;
    // SAFETY: loading libavcodec runs no initializers with preconditions.
    let lib = match unsafe { Library::new("libavcodec.so") } {
        Ok(lib) => lib,
        Err(e) => {
            log::warn!("libavcodec unavailable, hardware decoding disabled: {e}");
            return;
        }
    };
    // SAFETY: signature matches libavcodec/jni.h.
    match unsafe { lib.get::<SetJavaVm>(c"av_jni_set_java_vm") } {
        Ok(set_java_vm) => {
            let rc = unsafe { set_java_vm(vm.cast(), ptr::null_mut()) };
            if rc < 0 {
                log::warn!("av_jni_set_java_vm failed: {rc}");
            }
        }
        Err(e) => log::warn!("av_jni_set_java_vm missing: {e}"),
    }
    // libavcodec keeps the VM in a static; keep it loaded for the process lifetime.
    std::mem::forget(lib);
}

/// `SurfaceBridge.nativeAcquire(surface: Surface): Long`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wplayer_core_player_SurfaceBridge_nativeAcquire(
    env: *mut JNIEnv,
    _class: jclass,
    surface: jobject,
) -> jlong {
    // SAFETY: env is the valid JNIEnv for this call; surface is a live local ref.
    unsafe { ((**env).v1_1.NewGlobalRef)(env, surface) as jlong }
}

/// `SurfaceBridge.nativeRelease(reference: Long)`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wplayer_core_player_SurfaceBridge_nativeRelease(
    env: *mut JNIEnv,
    _class: jclass,
    reference: jlong,
) {
    if reference != 0 {
        // SAFETY: reference was produced by nativeAcquire and is released once.
        unsafe { ((**env).v1_1.DeleteGlobalRef)(env, reference as jobject) };
    }
}
