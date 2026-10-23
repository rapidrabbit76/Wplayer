//! Exercises the network file systems against real servers.
//!
//! Ignored by default. Each test reads its server from an environment variable
//! holding `host:port` and expects the tree created by `scripts/test-servers.sh start`:
//! `/영화/sample #1.mkv` (5 MiB + 77 bytes, byte `i` = `i % 251`) and `/영화/Season 2/`.
//!
//! ```sh
//! eval "$(scripts/test-servers.sh start)" && (cd rust && cargo test -p wplayer-core --test live_sources -- --ignored)
//! ```

use std::sync::Arc;

use wplayer_core::vfs::{Protocol, RandomReader, SourceConfig, Vfs, connect};

const SAMPLE_LEN: u64 = 5 * 1024 * 1024 + 77;

fn config(protocol: Protocol, address: &str, username: Option<&str>, password: Option<&str>) -> SourceConfig {
    let (host, port) = address.rsplit_once(':').expect("host:port");
    SourceConfig {
        protocol,
        host: host.to_owned(),
        port: Some(port.parse().expect("port")),
        root_path: "/".to_owned(),
        username: username.map(str::to_owned),
        password: password.map(str::to_owned),
        allow_untrusted_certificates: false,
    }
}

fn exercise(config: SourceConfig) {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let vfs: Arc<dyn Vfs> = runtime
        .block_on(async { tokio::spawn(connect(config)).await.unwrap() })
        .unwrap();

    let listing_vfs = Arc::clone(&vfs);
    let entries = runtime
        .block_on(async {
            tokio::spawn(async move { listing_vfs.list("/영화").await })
                .await
                .unwrap()
        })
        .unwrap();
    let sample = entries
        .iter()
        .find(|e| e.name == "sample #1.mkv")
        .expect("sample listed");
    assert_eq!(sample.path, "/영화/sample #1.mkv");
    assert_eq!(sample.size, SAMPLE_LEN);
    assert!(!sample.is_dir);
    assert!(entries.iter().any(|e| e.name == "Season 2" && e.is_dir));

    let mut reader = vfs.open(&sample.path).unwrap();
    assert_eq!(reader.size(), Some(SAMPLE_LEN));
    let mut buf = vec![0u8; 64 * 1024];
    // Sequential start, a forward seek, a backward seek and the tail, like a demuxer probing a file.
    for offset in [0, 65_536, 4_000_000, 1_000, SAMPLE_LEN - 100] {
        let n = reader.read_at(offset, &mut buf).unwrap();
        assert!(n > 0, "no data at {offset}");
        for (i, byte) in buf[..n].iter().enumerate() {
            assert_eq!(*byte, ((offset + i as u64) % 251) as u8, "offset {offset} + {i}");
        }
    }
    assert_eq!(reader.read_at(SAMPLE_LEN, &mut buf).unwrap(), 0);

    // The whole file in consecutive reads, as playback streams it: catches
    // readers that lose or repeat bytes between reads of one stream.
    assert_stream(reader.as_mut(), 0, SAMPLE_LEN);

    // Matroska probing: stream part of the file, jump to the index at the
    // tail, hit EOF, then stream again from just after the header.
    assert_stream(reader.as_mut(), 0, 2_414_272);
    assert_stream(reader.as_mut(), SAMPLE_LEN - 88, 88);
    assert_eq!(reader.read_at(SAMPLE_LEN, &mut buf).unwrap(), 0);
    assert_stream(reader.as_mut(), 752, 3_000_000);
}

/// Reads `[start, start + len)` in consecutive odd-sized reads and checks every byte.
fn assert_stream(reader: &mut dyn RandomReader, start: u64, len: u64) {
    let mut buf = vec![0u8; 7_919];
    let end = (start + len).min(SAMPLE_LEN);
    let mut offset = start;
    while offset < end {
        let n = reader.read_at(offset, &mut buf).unwrap();
        assert!(n > 0, "stream ended early at {offset}");
        for (i, byte) in buf[..n].iter().enumerate() {
            assert_eq!(
                *byte,
                ((offset + i as u64) % 251) as u8,
                "stream from {start}: offset {offset} + {i}"
            );
        }
        offset += n as u64;
    }
}

#[test]
#[ignore = "needs WPLAYER_TEST_FTP"]
fn ftp_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_FTP").expect("WPLAYER_TEST_FTP");
    exercise(config(Protocol::Ftp, &address, Some("tester"), Some("secret")));
}

#[test]
#[ignore = "needs WPLAYER_TEST_FTP_LIST (a server without MLSD)"]
fn ftp_without_mlsd_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_FTP_LIST").expect("WPLAYER_TEST_FTP_LIST");
    exercise(config(Protocol::Ftp, &address, Some("tester"), Some("secret")));
}

#[test]
#[ignore = "needs WPLAYER_TEST_FTP_CP949 (a server naming files in CP949)"]
fn ftp_with_cp949_names_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_FTP_CP949").expect("WPLAYER_TEST_FTP_CP949");
    exercise(config(Protocol::Ftp, &address, Some("tester"), Some("secret")));
}

#[test]
#[ignore = "needs WPLAYER_TEST_WEBDAV"]
fn webdav_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_WEBDAV").expect("WPLAYER_TEST_WEBDAV");
    exercise(config(Protocol::WebDav, &address, None, None));
}

#[test]
#[ignore = "needs WPLAYER_TEST_SMB (share named `share`)"]
fn smb_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_SMB").expect("WPLAYER_TEST_SMB");
    let mut config = config(Protocol::Smb, &address, Some("tester"), Some("secret"));
    config.root_path = "/share".to_owned();
    exercise(config);
}

#[test]
#[ignore = "needs WPLAYER_TEST_SMB_SMALL_READS (a server taking reads of 64 KiB)"]
fn smb_with_small_reads_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_SMB_SMALL_READS").expect("WPLAYER_TEST_SMB_SMALL_READS");
    let mut config = config(Protocol::Smb, &address, Some("tester"), Some("secret"));
    config.root_path = "/share".to_owned();
    exercise(config);
}

#[test]
#[ignore = "needs WPLAYER_TEST_SMB (shares `share` and `public`)"]
fn smb_whole_server_lists_its_shares() {
    // An empty path makes the source the whole server: the root lists the shares.
    let address = std::env::var("WPLAYER_TEST_SMB").expect("WPLAYER_TEST_SMB");
    let config = config(Protocol::Smb, &address, Some("tester"), Some("secret"));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let vfs: Arc<dyn Vfs> = runtime
        .block_on(async { tokio::spawn(connect(config)).await.unwrap() })
        .unwrap();

    let root_vfs = Arc::clone(&vfs);
    let shares = runtime
        .block_on(async { tokio::spawn(async move { root_vfs.list("/").await }).await.unwrap() })
        .unwrap();
    let names: Vec<&str> = shares.iter().map(|e| e.name.as_str()).collect();
    assert!(names.contains(&"share") && names.contains(&"public"), "{names:?}");
    assert!(
        !names.iter().any(|n| n.ends_with('$')),
        "hidden shares listed: {names:?}"
    );
    assert!(shares.iter().all(|e| e.is_dir && e.path == format!("/{}", e.name)));

    let folder_vfs = Arc::clone(&vfs);
    let entries = runtime
        .block_on(async {
            tokio::spawn(async move { folder_vfs.list("/share/영화").await })
                .await
                .unwrap()
        })
        .unwrap();
    let sample = entries
        .iter()
        .find(|e| e.name == "sample #1.mkv")
        .expect("sample listed");
    assert_eq!(sample.path, "/share/영화/sample #1.mkv");
    let mut reader = vfs.open(&sample.path).unwrap();
    assert_eq!(reader.size(), Some(SAMPLE_LEN));
    assert_stream(reader.as_mut(), 0, SAMPLE_LEN);
    drop(reader);
    // The smb crate disconnects its trees on drop, which needs the runtime.
    runtime.block_on(async move { drop(vfs) });
}

#[test]
#[ignore = "needs WPLAYER_TEST_NFS (portmapper port; export `/export`)"]
fn nfs_lists_and_reads() {
    let address = std::env::var("WPLAYER_TEST_NFS").expect("WPLAYER_TEST_NFS");
    let mut config = config(Protocol::Nfs, &address, None, None);
    config.root_path = "/export".to_owned();
    exercise(config);
}

#[test]
#[ignore = "needs WPLAYER_TEST_SMB (guest share named `public`)"]
fn smb_guest_is_reported_as_unsupported() {
    // smb 0.12 cannot finish a guest session setup; the error must say so clearly.
    let address = std::env::var("WPLAYER_TEST_SMB").expect("WPLAYER_TEST_SMB");
    let mut config = config(Protocol::Smb, &address, None, None);
    config.root_path = "/public".to_owned();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let result = runtime.block_on(async { tokio::spawn(connect(config)).await.unwrap() });
    assert!(
        matches!(result, Err(wplayer_core::CoreError::Unsupported { .. })),
        "{:?}",
        result.err()
    );
}

#[test]
#[ignore = "needs WPLAYER_TEST_SMB"]
fn smb_wrong_password_is_an_authentication_error() {
    let address = std::env::var("WPLAYER_TEST_SMB").expect("WPLAYER_TEST_SMB");
    let mut config = config(Protocol::Smb, &address, Some("tester"), Some("wrong"));
    config.root_path = "/share".to_owned();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let result = runtime.block_on(async { tokio::spawn(connect(config)).await.unwrap() });
    assert!(
        matches!(result, Err(wplayer_core::CoreError::AuthenticationFailed)),
        "{:?}",
        result.err()
    );
}

/// Regression: dropping an SMB client outside the tokio runtime used to panic
/// inside `unregister_source` and poison the source registry.
#[test]
#[ignore = "needs WPLAYER_TEST_SMB"]
fn smb_source_can_be_unregistered_from_a_plain_thread() {
    use wplayer_core::source::{media_uri, register_source, unregister_source};

    let address = std::env::var("WPLAYER_TEST_SMB").expect("WPLAYER_TEST_SMB");
    let mut config = config(Protocol::Smb, &address, Some("tester"), Some("secret"));
    config.root_path = "/share".to_owned();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    for round in 0..3 {
        let id = format!("smb-{round}");
        runtime.block_on(register_source(id.clone(), config.clone())).unwrap();
        std::thread::spawn(move || unregister_source(id))
            .join()
            .expect("unregister must not panic");
    }
    // The registry still works afterwards.
    runtime.block_on(register_source("smb-final".into(), config)).unwrap();
    assert!(media_uri("smb-final".into(), "/a.mkv".into()).is_ok());
    std::thread::spawn(|| unregister_source("smb-final".into()))
        .join()
        .unwrap();
}
