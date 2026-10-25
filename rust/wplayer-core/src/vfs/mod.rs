//! Virtual file systems: one implementation per protocol.
//!
//! Paths are always absolute, `/`-separated and relative to the source root
//! (see [`path::normalize`]); implementations never see `..` components.

mod chunked;
mod ftp;
mod local;
mod nfs;
pub mod path;
mod smb;
mod srvsvc;
mod tls;
mod webdav;

use std::sync::Arc;

use async_trait::async_trait;

use crate::error::CoreResult;

pub use local::LocalVfs;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Protocol {
    Local,
    Smb,
    Nfs,
    Ftp,
    Ftps,
    WebDav,
    WebDavs,
}

/// Connection settings for one source. `root_path` is the share/export/base path.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SourceConfig {
    pub protocol: Protocol,
    pub host: String,
    pub port: Option<u16>,
    pub root_path: String,
    pub username: Option<String>,
    pub password: Option<String>,
    /// Accept self-signed or otherwise unverifiable TLS certificates (FTPS, WebDAVS).
    pub allow_untrusted_certificates: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Entry {
    pub name: String,
    /// Absolute path within the source, usable with `list` or as a media path.
    pub path: String,
    pub is_dir: bool,
    pub size: u64,
    pub modified_epoch_ms: Option<i64>,
}

#[async_trait]
pub trait Vfs: Send + Sync {
    async fn list(&self, path: &str) -> CoreResult<Vec<Entry>>;

    /// Opens a file for random access. Called from player threads, so it blocks.
    fn open(&self, path: &str) -> CoreResult<Box<dyn RandomReader>>;

    /// Deletes a file. Folders are refused.
    async fn delete(&self, path: &str) -> CoreResult<()>;
}

/// Blocking random-access reader backing a libmpv stream.
pub trait RandomReader: Send {
    fn size(&self) -> Option<u64>;

    /// Reads up to `buf.len()` bytes at `offset`; returns 0 at end of file.
    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize>;
}

/// Connects to a source. Runs on the I/O runtime; blocking clients use `spawn_blocking`.
pub async fn connect(config: SourceConfig) -> CoreResult<Arc<dyn Vfs>> {
    Ok(match config.protocol {
        Protocol::Local => Arc::new(LocalVfs::new(&config.root_path)?),
        Protocol::Smb => Arc::new(smb::SmbVfs::connect(&config).await?),
        Protocol::Nfs => Arc::new(nfs::NfsVfs::connect(&config).await?),
        Protocol::WebDav => Arc::new(webdav::WebDavVfs::new(&config, false)?),
        Protocol::WebDavs => Arc::new(webdav::WebDavVfs::new(&config, true)?),
        Protocol::Ftp | Protocol::Ftps => {
            let secure = config.protocol == Protocol::Ftps;
            Arc::new(tokio::task::spawn_blocking(move || ftp::FtpVfs::connect(&config, secure)).await??)
        }
    })
}
