//! Links libarchive. Android builds take the static libraries that
//! android/native/build.sh leaves in android/native/out/static/<abi>; host builds (tests, the
//! UniFFI bindings) link the system libarchive.

use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        println!("cargo:rustc-link-lib=archive");
        return;
    }
    let abi = match env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() {
        "aarch64" => "arm64-v8a",
        "arm" => "armeabi-v7a",
        arch => panic!("android/native/build.sh does not build for {arch}"),
    };
    let dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap())
        .join("../../android/native/out/static")
        .join(abi);
    for lib in ["archive", "lzma"] {
        let file = dir.join(format!("lib{lib}.a"));
        assert!(
            file.is_file(),
            "{} is missing: run android/native/build.sh",
            file.display()
        );
        println!("cargo:rerun-if-changed={}", file.display());
        println!("cargo:rustc-link-lib=static={lib}");
    }
    println!("cargo:rustc-link-search=native={}", dir.display());
    // libarchive's deflate support uses the NDK's zlib.
    println!("cargo:rustc-link-lib=z");
}
