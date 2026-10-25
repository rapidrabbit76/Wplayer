//! NFSv3 via `nfs3_client`. `root_path` is the export path, e.g. `/volume1/video`.
//!
//! Android apps cannot bind privileged ports, so the export must allow
//! connections from non-privileged ports (`insecure` in /etc/exports).
//! `username` may carry the AUTH_SYS identity as `uid` or `uid:gid`.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use nfs3_client::net::Connector;
use nfs3_client::nfs3_types::nfs3::{
    GETATTR3args, LOOKUP3args, Nfs3Option, Nfs3Result, READ3args, READDIRPLUS3args, REMOVE3args, cookieverf3,
    diropargs3, fattr3, filename3, ftype3, nfs_fh3, nfsstat3,
};
use nfs3_client::nfs3_types::rpc::{auth_unix, opaque_auth};
use nfs3_client::nfs3_types::xdr_codec::Opaque;
use nfs3_client::tokio::TokioConnector;
use nfs3_client::{Nfs3Connection, Nfs3ConnectionBuilder};
use tokio::sync::Mutex;

use super::chunked::ChunkedReader;
use super::{Entry, RandomReader, SourceConfig, Vfs, path};
use crate::error::{CoreError, CoreResult};
use crate::runtime;

type Connection = Nfs3Connection<<TokioConnector as Connector>::Connection>;

const READ_SIZE: u32 = 1024 * 1024;
const READDIR_MAX_BYTES: u32 = 256 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

pub struct NfsVfs {
    shared: Arc<Shared>,
}

struct Shared {
    connection: Mutex<Connection>,
    root: nfs_fh3,
    /// Handles of already-resolved paths, so deep folders need not be walked again.
    handles: Mutex<HashMap<String, nfs_fh3>>,
}

impl NfsVfs {
    pub async fn connect(config: &SourceConfig) -> CoreResult<Self> {
        let (uid, gid) = parse_identity(config.username.as_deref())?;
        let credential = opaque_auth::auth_unix(&auth_unix {
            stamp: 0,
            machinename: Opaque::owned(b"wplayer".to_vec()),
            uid,
            gid,
            gids: vec![gid],
        });
        let mut builder = Nfs3ConnectionBuilder::new(TokioConnector, &config.host, path::normalize(&config.root_path)?)
            .connect_from_privileged_port(false)
            .credential(credential);
        if let Some(port) = config.port {
            builder = builder.portmapper_port(port);
        }
        let connection = tokio::time::timeout(CONNECT_TIMEOUT, builder.mount())
            .await
            .map_err(|_| CoreError::Connection {
                detail: format!("{}: timed out", config.host),
            })?
            .map_err(|e| CoreError::Connection { detail: e.to_string() })?;
        let root = connection.root_nfs_fh3();
        Ok(Self {
            shared: Arc::new(Shared {
                connection: Mutex::new(connection),
                root,
                handles: Mutex::new(HashMap::new()),
            }),
        })
    }
}

impl Shared {
    async fn attributes(&self, path: &str) -> CoreResult<(nfs_fh3, fattr3)> {
        let handle = self.resolve(path).await?;
        let reply = self
            .connection
            .lock()
            .await
            .getattr(&GETATTR3args { object: handle.clone() })
            .await
            .map_err(rpc_error)?;
        match reply {
            Nfs3Result::Ok(ok) => Ok((handle, ok.obj_attributes)),
            Nfs3Result::Err((status, _)) => Err(status_error(status, path)),
        }
    }

    /// Walks `path` from the export root with LOOKUP, caching every handle found.
    async fn resolve(&self, path: &str) -> CoreResult<nfs_fh3> {
        if path == "/" {
            return Ok(self.root.clone());
        }
        if let Some(handle) = self.handles.lock().await.get(path) {
            return Ok(handle.clone());
        }
        let mut handle = self.root.clone();
        let mut walked = String::new();
        for component in path.trim_start_matches('/').split('/') {
            walked.push('/');
            walked.push_str(component);
            if let Some(cached) = self.handles.lock().await.get(&walked) {
                handle = cached.clone();
                continue;
            }
            let args = LOOKUP3args {
                what: diropargs3 {
                    dir: handle,
                    name: filename3(Opaque::owned(component.as_bytes().to_vec())),
                },
            };
            let reply = self.connection.lock().await.lookup(&args).await.map_err(rpc_error)?;
            handle = match reply {
                Nfs3Result::Ok(ok) => ok.object,
                Nfs3Result::Err((status, _)) => return Err(status_error(status, &walked)),
            };
            self.handles.lock().await.insert(walked.clone(), handle.clone());
        }
        Ok(handle)
    }
}

#[async_trait]
impl Vfs for NfsVfs {
    async fn list(&self, path: &str) -> CoreResult<Vec<Entry>> {
        let dir_path = path::normalize(path)?;
        let dir = self.shared.resolve(&dir_path).await?;
        let mut entries = Vec::new();
        let mut cookie = 0;
        let mut cookieverf = cookieverf3::default();
        loop {
            let args = READDIRPLUS3args {
                dir: dir.clone(),
                cookie,
                cookieverf,
                dircount: READDIR_MAX_BYTES,
                maxcount: READDIR_MAX_BYTES,
            };
            let reply = self
                .shared
                .connection
                .lock()
                .await
                .readdirplus(&args)
                .await
                .map_err(rpc_error)?;
            let ok = match reply {
                Nfs3Result::Ok(ok) => ok,
                Nfs3Result::Err((status, _)) => return Err(status_error(status, &dir_path)),
            };
            for item in ok.reply.entries.0 {
                cookie = item.cookie;
                let name = String::from_utf8_lossy(item.name.0.as_ref()).into_owned();
                if name == "." || name == ".." {
                    continue;
                }
                let attributes = match item.name_attributes {
                    Nfs3Option::Some(attributes) => attributes,
                    Nfs3Option::None => continue,
                };
                let is_dir = attributes.type_ == ftype3::NF3DIR;
                let entry_path = path::join(&dir_path, &name);
                if let Nfs3Option::Some(handle) = item.name_handle {
                    self.shared.handles.lock().await.insert(entry_path.clone(), handle);
                }
                entries.push(Entry {
                    name,
                    path: entry_path,
                    is_dir,
                    size: if is_dir { 0 } else { attributes.size },
                    modified_epoch_ms: Some(
                        i64::from(attributes.mtime.seconds) * 1000 + i64::from(attributes.mtime.nseconds / 1_000_000),
                    ),
                });
            }
            if ok.reply.eof {
                break;
            }
            cookieverf = ok.cookieverf;
        }
        Ok(entries)
    }

    fn open(&self, path: &str) -> CoreResult<Box<dyn RandomReader>> {
        let file_path = path::normalize(path)?;
        let shared = Arc::clone(&self.shared);
        let (handle, attributes) = runtime::block_on(async move { shared.attributes(&file_path).await })?;
        Ok(Box::new(ChunkedReader::new(NfsReader {
            shared: Arc::clone(&self.shared),
            handle,
            size: attributes.size,
        })))
    }

    async fn delete(&self, path: &str) -> CoreResult<()> {
        let file_path = path::normalize(path)?;
        let (parent, name) = file_path.rsplit_once('/').ok_or_else(|| CoreError::InvalidArgument {
            detail: format!("not a file: {file_path}"),
        })?;
        // Some servers answer REMOVE on an empty folder by removing it, so look first.
        if self.shared.attributes(&file_path).await?.1.type_ == ftype3::NF3DIR {
            return Err(CoreError::InvalidArgument {
                detail: format!("not a file: {file_path}"),
            });
        }
        let dir = self
            .shared
            .resolve(if parent.is_empty() { "/" } else { parent })
            .await?;
        let args = REMOVE3args {
            object: diropargs3 {
                dir,
                name: filename3(Opaque::owned(name.as_bytes().to_vec())),
            },
        };
        let reply = self
            .shared
            .connection
            .lock()
            .await
            .remove(&args)
            .await
            .map_err(rpc_error)?;
        self.shared.handles.lock().await.remove(&file_path);
        match reply {
            Nfs3Result::Ok(_) => Ok(()),
            Nfs3Result::Err((status, _)) => Err(status_error(status, &file_path)),
        }
    }
}

struct NfsReader {
    shared: Arc<Shared>,
    handle: nfs_fh3,
    size: u64,
}

impl RandomReader for NfsReader {
    fn size(&self) -> Option<u64> {
        Some(self.size)
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        if offset >= self.size {
            return Ok(0);
        }
        let args = READ3args {
            file: self.handle.clone(),
            offset,
            count: (buf.len() as u32).min(READ_SIZE),
        };
        let reply =
            runtime::block_on(async { self.shared.connection.lock().await.read(&args).await }).map_err(rpc_error)?;
        match reply {
            Nfs3Result::Ok(ok) => {
                let data = ok.data.as_ref();
                let n = data.len().min(buf.len());
                buf[..n].copy_from_slice(&data[..n]);
                Ok(n)
            }
            Nfs3Result::Err((status, _)) => Err(status_error(status, "read")),
        }
    }
}

/// `"1000"` → (1000, 1000); `"1000:100"` → (1000, 100); none → (0, 0).
fn parse_identity(username: Option<&str>) -> CoreResult<(u32, u32)> {
    let Some(text) = username.filter(|s| !s.is_empty()) else {
        return Ok((0, 0));
    };
    let invalid = || CoreError::InvalidArgument {
        detail: format!("NFS identity must be uid or uid:gid, got {text}"),
    };
    let (uid, gid) = text.split_once(':').unwrap_or((text, text));
    Ok((uid.parse().map_err(|_| invalid())?, gid.parse().map_err(|_| invalid())?))
}

fn rpc_error(err: nfs3_client::RpcError) -> CoreError {
    CoreError::Connection {
        detail: err.to_string(),
    }
}

fn status_error(status: nfsstat3, path: &str) -> CoreError {
    match status {
        nfsstat3::NFS3ERR_NOENT => CoreError::NotFound { path: path.to_owned() },
        nfsstat3::NFS3ERR_ACCES | nfsstat3::NFS3ERR_PERM => CoreError::PermissionDenied { path: path.to_owned() },
        other => CoreError::Io {
            detail: format!("{other:?} at {path}"),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_identity() {
        assert_eq!(parse_identity(None).unwrap(), (0, 0));
        assert_eq!(parse_identity(Some("1000")).unwrap(), (1000, 1000));
        assert_eq!(parse_identity(Some("1026:100")).unwrap(), (1026, 100));
        assert!(parse_identity(Some("admin")).is_err());
    }
}
