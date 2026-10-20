//! Copies a file from a source to local storage, for the download manager.

use std::fs::OpenOptions;
use std::io::Write;
use std::sync::Arc;

use crate::error::{CoreError, CoreResult};
use crate::runtime;
use crate::source;

/// Implemented in Kotlin; called on the copying thread.
#[uniffi::export(foreign)]
pub trait TransferListener: Send + Sync {
    /// `copied` bytes of `total` (when known) are on disk. Return false to stop.
    fn on_progress(&self, copied: u64, total: Option<u64>) -> bool;
}

const CHUNK: usize = 1024 * 1024;

/// Copies `path` of a registered source to `dest_path`. An existing, shorter
/// `dest_path` is treated as an interrupted download and continued.
/// Blocks; call from a background thread. Stopping via the listener returns `Cancelled`.
#[uniffi::export]
pub fn download_file(
    source_id: String,
    path: String,
    dest_path: String,
    listener: Arc<dyn TransferListener>,
) -> CoreResult<()> {
    // Some readers spawn work (and clean up) on the runtime.
    let _runtime = runtime::enter();
    let mut reader = source::get(&source_id)?.open(&path)?;
    let total = reader.size();

    let mut file = OpenOptions::new().create(true).append(true).open(&dest_path)?;
    let mut offset = file.metadata()?.len();
    if total.is_some_and(|total| offset > total) {
        // Not a prefix of this file: start over.
        file.set_len(0)?;
        offset = 0;
    }

    let mut buf = vec![0u8; CHUNK];
    loop {
        if !listener.on_progress(offset, total) {
            file.flush()?;
            return Err(CoreError::Cancelled);
        }
        if total.is_some_and(|total| offset >= total) {
            break;
        }
        let n = reader.read_at(offset, &mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
        offset += n as u64;
    }
    file.sync_all()?;
    if total.is_some_and(|total| offset < total) {
        return Err(CoreError::Connection {
            detail: format!("ended early at {offset} of {} bytes", total.unwrap_or(0)),
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::*;
    use crate::vfs::{Protocol, SourceConfig};

    struct StopAfter(AtomicU64);

    impl TransferListener for StopAfter {
        fn on_progress(&self, copied: u64, _total: Option<u64>) -> bool {
            copied < self.0.load(Ordering::Relaxed)
        }
    }

    fn local_source(id: &str, root: &std::path::Path) {
        let config = SourceConfig {
            protocol: Protocol::Local,
            host: String::new(),
            port: None,
            root_path: root.to_string_lossy().into_owned(),
            username: None,
            password: None,
            allow_untrusted_certificates: false,
        };
        runtime::block_on(source::register_source(id.into(), config)).unwrap();
    }

    #[test]
    fn copies_and_resumes_an_interrupted_download() {
        let share = tempfile::tempdir().unwrap();
        let data: Vec<u8> = (0..3 * CHUNK + 123).map(|i| (i % 251) as u8).collect();
        std::fs::write(share.path().join("movie.mkv"), &data).unwrap();
        local_source("transfer-test", share.path());
        let dest = tempfile::tempdir().unwrap().keep().join("movie.mkv");
        let dest_path = dest.to_string_lossy().into_owned();

        // Stopped after the first chunk, like a cancelled or killed download.
        let stopped = download_file(
            "transfer-test".into(),
            "/movie.mkv".into(),
            dest_path.clone(),
            Arc::new(StopAfter(AtomicU64::new(1))),
        );
        assert!(matches!(stopped, Err(CoreError::Cancelled)));
        assert_eq!(std::fs::metadata(&dest).unwrap().len(), CHUNK as u64);

        download_file(
            "transfer-test".into(),
            "/movie.mkv".into(),
            dest_path,
            Arc::new(StopAfter(AtomicU64::new(u64::MAX))),
        )
        .unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), data);
    }
}
