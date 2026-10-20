//! Registry of connected sources, keyed by a Kotlin-assigned id.
//!
//! Media is addressed as `wplayer://<source-id>/<percent-encoded path>` so the
//! player can resolve it back to a [`Vfs`] inside the libmpv stream callback.
//! Text subtitles use `wplayersub://` and are converted to UTF-8 on open.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, PoisonError, RwLock, RwLockReadGuard, RwLockWriteGuard};

use percent_encoding::{AsciiSet, CONTROLS, percent_decode_str, utf8_percent_encode};

use crate::error::{CoreError, CoreResult};
use crate::runtime;
use crate::vfs::{self, SourceConfig, Vfs, path};

pub(crate) const MEDIA_SCHEME: &str = "wplayer";
pub(crate) const SUBTITLE_SCHEME: &str = "wplayersub";

/// Characters escaped inside one path segment.
const SEGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'/')
    .add(b'?')
    .add(b'<')
    .add(b'>')
    .add(b'`')
    .add(b'{')
    .add(b'}');

static SOURCES: LazyLock<RwLock<HashMap<String, Arc<dyn Vfs>>>> = LazyLock::new(|| RwLock::new(HashMap::new()));

/// The registry only maps ids to handles, so a panic elsewhere while holding
/// the lock cannot leave it inconsistent; keep serving instead of cascading.
fn registry_read() -> RwLockReadGuard<'static, HashMap<String, Arc<dyn Vfs>>> {
    SOURCES.read().unwrap_or_else(PoisonError::into_inner)
}

fn registry_write() -> RwLockWriteGuard<'static, HashMap<String, Arc<dyn Vfs>>> {
    SOURCES.write().unwrap_or_else(PoisonError::into_inner)
}

/// Drops a removed source on the I/O runtime: network clients (e.g. `smb`)
/// spawn tokio tasks from `Drop`, which panics on a plain Kotlin thread.
fn release(vfs: Option<Arc<dyn Vfs>>) {
    if let Some(vfs) = vfs {
        runtime::spawn(async move { drop(vfs) });
    }
}

/// Connects (or reconnects) a source under `id`, replacing any previous one.
#[uniffi::export]
pub async fn register_source(id: String, config: SourceConfig) -> CoreResult<()> {
    let vfs = runtime::spawn(vfs::connect(config)).await??;
    let previous = registry_write().insert(id, vfs);
    release(previous);
    Ok(())
}

#[uniffi::export]
pub fn unregister_source(id: String) {
    let removed = registry_write().remove(&id);
    release(removed);
}

/// Builds the URI the player uses to open `path` from source `source_id`.
#[uniffi::export]
pub fn media_uri(source_id: String, path: String) -> CoreResult<String> {
    build_uri(MEDIA_SCHEME, &source_id, &path)
}

/// Like [`media_uri`], for a text subtitle that should be re-encoded as UTF-8.
#[uniffi::export]
pub fn subtitle_uri(source_id: String, path: String) -> CoreResult<String> {
    build_uri(SUBTITLE_SCHEME, &source_id, &path)
}

fn build_uri(scheme: &str, source_id: &str, path: &str) -> CoreResult<String> {
    let normalized = path::normalize(path)?;
    let encoded: Vec<String> = normalized
        .split('/')
        .map(|s| utf8_percent_encode(s, SEGMENT).to_string())
        .collect();
    Ok(format!(
        "{scheme}://{}{}",
        utf8_percent_encode(source_id, SEGMENT),
        encoded.join("/")
    ))
}

pub(crate) fn get(id: &str) -> CoreResult<Arc<dyn Vfs>> {
    registry_read().get(id).cloned().ok_or_else(|| CoreError::NotFound {
        path: format!("source {id}"),
    })
}

/// Splits a `scheme://id/path` URI into `(source id, normalized path)`.
pub(crate) fn parse_uri(scheme: &str, uri: &str) -> CoreResult<(String, String)> {
    let invalid = || CoreError::InvalidArgument {
        detail: format!("not a {scheme} uri: {uri}"),
    };
    let rest = uri
        .strip_prefix(scheme)
        .and_then(|r| r.strip_prefix("://"))
        .ok_or_else(invalid)?;
    let (id, raw_path) = rest.split_once('/').unwrap_or((rest, ""));
    let decode = |s: &str| {
        percent_decode_str(s)
            .decode_utf8()
            .map(|c| c.into_owned())
            .map_err(|_| invalid())
    };
    let id = decode(id)?;
    let segments = raw_path.split('/').map(decode).collect::<CoreResult<Vec<_>>>()?;
    Ok((id, path::normalize(&segments.join("/"))?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_uri_round_trips_unicode_and_reserved_characters() {
        let uri = media_uri("server 1".into(), "/영화/a #1?.mkv".into()).unwrap();
        assert_eq!(uri, "wplayer://server%201/%EC%98%81%ED%99%94/a%20%231%3F.mkv");
        let (id, path) = parse_uri(MEDIA_SCHEME, &uri).unwrap();
        assert_eq!(id, "server 1");
        assert_eq!(path, "/영화/a #1?.mkv");
    }

    #[test]
    fn unregistering_from_a_plain_thread_does_not_poison_the_registry() {
        let dir = tempfile::tempdir().unwrap();
        let config = vfs::SourceConfig {
            protocol: vfs::Protocol::Local,
            host: String::new(),
            port: None,
            root_path: dir.path().to_str().unwrap().to_owned(),
            username: None,
            password: None,
            allow_untrusted_certificates: false,
        };
        runtime::block_on(register_source("t".into(), config)).unwrap();
        std::thread::spawn(|| unregister_source("t".into())).join().unwrap();
        assert!(get("t").is_err());
        assert!(!SOURCES.is_poisoned());
    }

    #[test]
    fn rejects_foreign_schemes() {
        assert!(parse_uri(MEDIA_SCHEME, "file:///etc/passwd").is_err());
        let subtitle = subtitle_uri("server".into(), "/a.smi".into()).unwrap();
        assert!(parse_uri(MEDIA_SCHEME, &subtitle).is_err());
        assert_eq!(
            parse_uri(SUBTITLE_SCHEME, &subtitle).unwrap(),
            ("server".into(), "/a.smi".into())
        );
    }
}
