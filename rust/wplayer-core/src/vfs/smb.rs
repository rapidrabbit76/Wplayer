//! SMB2/3 via the pure-Rust `smb` crate.
//!
//! `root_path` is `/share[/sub/dir]`, or empty for the whole server: its root
//! then lists the server's shares, and paths are `/share/...`.

use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures_util::StreamExt;
use smb::{
    Client, ClientConfig, Directory, File, FileAccessMask, FileCreateArgs, FileDirectoryInformation,
    FileDispositionInformation, GetLen, IoctlBuffer, PipeTransceiveRequest, Resource, UncPath,
};

use super::chunked::ChunkedReader;
use super::{Entry, RandomReader, SourceConfig, Vfs, path, srvsvc};
use crate::error::{CoreError, CoreResult};
use crate::runtime;

/// Milliseconds between 1601-01-01 (FILETIME epoch) and 1970-01-01.
const FILETIME_UNIX_OFFSET_MS: u128 = 11_644_473_600_000;

pub struct SmbVfs {
    client: Client,
    host: String,
    username: String,
    password: String,
    /// The share this source starts in; None when the source is the whole server.
    fixed: Option<FixedShare>,
    /// Shares already connected on `client` (each needs one tree connect).
    connected: Mutex<HashSet<String>>,
}

struct FixedShare {
    name: String,
    /// Directory inside the share that acts as this source's root, without slashes at the ends.
    base: String,
}

impl SmbVfs {
    pub async fn connect(config: &SourceConfig) -> CoreResult<Self> {
        let normalized = path::normalize(&config.root_path)?;
        let mut parts = normalized.trim_start_matches('/').splitn(2, '/');
        let fixed = parts.next().filter(|s| !s.is_empty()).map(|name| FixedShare {
            name: name.to_owned(),
            base: parts.next().unwrap_or("").to_owned(),
        });

        let mut client_config = ClientConfig::default();
        client_config.connection.port = config.port;
        client_config.connection.timeout = Some(Duration::from_secs(15));
        client_config.connection.allow_unsigned_guest_access = config.username.is_none();

        let vfs = Self {
            client: Client::new(client_config),
            host: config.host.clone(),
            username: config.username.clone().unwrap_or_else(|| "guest".to_owned()),
            password: config.password.clone().unwrap_or_default(),
            fixed,
            connected: Mutex::new(HashSet::new()),
        };
        // Signs in now so a wrong password shows when the source is added, not on first browse.
        match &vfs.fixed {
            Some(share) => vfs.ensure_share(&share.name).await?,
            None => vfs
                .client
                .ipc_connect(&vfs.host, &vfs.username, vfs.password.clone())
                .await
                .map_err(map_error)?,
        }
        Ok(vfs)
    }

    /// The share and the path inside it for a source path; None for the server root of a whole-server source.
    fn locate(&self, path: &str) -> Option<(String, String)> {
        let relative = path.trim_matches('/');
        match &self.fixed {
            Some(share) => {
                let inner = match (share.base.is_empty(), relative.is_empty()) {
                    (true, _) => relative.to_owned(),
                    (false, true) => share.base.clone(),
                    (false, false) => format!("{}/{relative}", share.base),
                };
                Some((share.name.clone(), inner))
            }
            None if relative.is_empty() => None,
            None => {
                let (name, inner) = relative.split_once('/').unwrap_or((relative, ""));
                Some((name.to_owned(), inner.to_owned()))
            }
        }
    }

    async fn ensure_share(&self, name: &str) -> CoreResult<()> {
        if self.connected.lock().unwrap().contains(name) {
            return Ok(());
        }
        let share = self.share_unc(name)?;
        self.client
            .share_connect(&share, &self.username, self.password.clone())
            .await
            .map_err(map_error)?;
        self.connected.lock().unwrap().insert(name.to_owned());
        Ok(())
    }

    fn share_unc(&self, name: &str) -> CoreResult<UncPath> {
        UncPath::new(&self.host)
            .and_then(|unc| unc.with_share(name))
            .map_err(map_error)
    }

    async fn open_resource(&self, path: &str, access: FileAccessMask) -> CoreResult<Resource> {
        let (share, inner) = self.locate(path).ok_or_else(|| CoreError::InvalidArgument {
            detail: format!("not inside a share: {path}"),
        })?;
        self.ensure_share(&share).await?;
        let unc = self.share_unc(&share)?;
        let unc = if inner.is_empty() {
            unc.with_no_path()
        } else {
            unc.with_path(&inner)
        };
        let args = FileCreateArgs::make_open_existing(access);
        self.client.create_file(&unc, &args).await.map_err(map_error)
    }

    /// The server's disk shares as folders, without hidden ones such as `C$` and `IPC$`.
    async fn list_shares(&self) -> CoreResult<Vec<Entry>> {
        let pipe = self
            .client
            .open_pipe(&self.host, srvsvc::PIPE_NAME)
            .await
            .map_err(map_error)?;
        let transceive = |request: Vec<u8>| {
            let pipe = &pipe;
            async move {
                let reply = pipe
                    .fsctl_with_options(
                        PipeTransceiveRequest::from(IoctlBuffer::from(request)),
                        srvsvc::MAX_FRAGMENT.into(),
                    )
                    .await
                    .map_err(map_error)?;
                CoreResult::Ok(reply.as_slice().to_vec())
            }
        };
        let result = async {
            srvsvc::check_bind_ack(&transceive(srvsvc::bind_request()).await?)?;
            srvsvc::parse_share_enum_response(&transceive(srvsvc::share_enum_request(&self.host)).await?)
        }
        .await;
        if let Err(e) = pipe.close().await {
            log::debug!("closing srvsvc pipe: {e}");
        }
        Ok(result?
            .into_iter()
            .filter(srvsvc::Share::is_browsable)
            .map(|share| Entry {
                path: path::join("/", &share.name),
                name: share.name,
                is_dir: true,
                size: 0,
                modified_epoch_ms: None,
            })
            .collect())
    }
}

#[async_trait]
impl Vfs for SmbVfs {
    async fn list(&self, path: &str) -> CoreResult<Vec<Entry>> {
        bounded("listing", self.list_folder(path)).await
    }

    fn open(&self, path: &str) -> CoreResult<Box<dyn RandomReader>> {
        let file_path = path::normalize(path)?;
        let (file, size, max_read) = runtime::block_on(bounded("open", async {
            match self.open_resource(&file_path, read_access()).await? {
                Resource::File(file) => {
                    let size = file.get_len().await.map_err(map_error)?;
                    Ok((file, size, self.max_read().await))
                }
                _ => Err(CoreError::InvalidArgument {
                    detail: format!("not a file: {file_path}"),
                }),
            }
        }))?;
        Ok(Box::new(ChunkedReader::new(SmbReader {
            file: Arc::new(file),
            size,
            max_read,
        })))
    }

    async fn delete(&self, path: &str) -> CoreResult<()> {
        let file_path = path::normalize(path)?;
        bounded("delete", async {
            let access = FileAccessMask::new().with_generic_read(true).with_delete(true);
            let file = match self.open_resource(&file_path, access).await? {
                Resource::File(file) => file,
                _ => {
                    return Err(CoreError::InvalidArgument {
                        detail: format!("not a file: {file_path}"),
                    });
                }
            };
            // The server removes the file once the handle marked for deletion closes.
            let marked = file
                .set_info(FileDispositionInformation::default())
                .await
                .map_err(map_error);
            let closed = file.close().await.map_err(map_error);
            marked.and(closed)
        })
        .await
    }
}

fn read_access() -> FileAccessMask {
    FileAccessMask::new().with_generic_read(true)
}

impl SmbVfs {
    /// The longest READ the server accepts, as it said when the connection was
    /// negotiated. Servers reject a longer one instead of shortening it.
    async fn max_read(&self) -> usize {
        let negotiated = match self.client.get_connection(&self.host).await {
            Ok(connection) => connection
                .conn_info()
                .map(|info| info.negotiation.max_read_size as usize),
            Err(e) => {
                log::debug!("SMB connection info: {e}");
                None
            }
        };
        negotiated
            .filter(|&n| n > 0)
            .map_or(MIN_READ_BYTES, |n| n.min(MAX_READ_BYTES))
    }

    async fn list_folder(&self, path: &str) -> CoreResult<Vec<Entry>> {
        let dir_path = path::normalize(path)?;
        if self.locate(&dir_path).is_none() {
            return self.list_shares().await;
        }
        let directory = match self.open_resource(&dir_path, read_access()).await? {
            Resource::Directory(directory) => Arc::new(directory),
            _ => {
                return Err(CoreError::InvalidArgument {
                    detail: format!("not a directory: {dir_path}"),
                });
            }
        };

        let mut entries = Vec::new();
        {
            let mut items = Directory::query::<FileDirectoryInformation>(&directory, "*")
                .await
                .map_err(map_error)?;
            while let Some(item) = items.next().await {
                let info = item.map_err(map_error)?;
                let name = info.file_name.to_string();
                if name == "." || name == ".." {
                    continue;
                }
                let is_dir = info.file_attributes.directory();
                let modified = info.last_write_time.since_epoch().as_millis();
                entries.push(Entry {
                    path: path::join(&dir_path, &name),
                    name,
                    is_dir,
                    size: if is_dir { 0 } else { info.end_of_file },
                    modified_epoch_ms: modified.checked_sub(FILETIME_UNIX_OFFSET_MS).map(|ms| ms as i64),
                });
            }
        }
        if let Err(e) = directory.close().await {
            log::debug!("closing {dir_path}: {e}");
        }
        Ok(entries)
    }
}

/// Reads are at most 1 MiB, which keeps seeks quick; ChunkedReader asks again for the rest.
const MAX_READ_BYTES: usize = 1024 * 1024;
/// SMB 2.0.2 servers, such as old NAS and routers, may accept no more than 64 KiB.
const MIN_READ_BYTES: usize = 64 * 1024;

/// Longest one SMB request may take. A connection that stops answering then
/// fails with a connection error, which the app answers by reconnecting,
/// instead of leaving a folder or video loading forever.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

async fn bounded<T>(what: &str, request: impl std::future::Future<Output = CoreResult<T>>) -> CoreResult<T> {
    tokio::time::timeout(REQUEST_TIMEOUT, request)
        .await
        .map_err(|_| CoreError::Connection {
            detail: format!("SMB {what} timed out"),
        })?
}

struct SmbReader {
    file: Arc<File>,
    size: u64,
    max_read: usize,
}

impl RandomReader for SmbReader {
    fn size(&self) -> Option<u64> {
        Some(self.size)
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        let len = buf.len().min(self.max_read);
        let file = &self.file;
        runtime::block_on(bounded("read", async {
            file.read_block(&mut buf[..len], offset, None, false)
                .await
                .map_err(Into::into)
        }))
    }
}

impl Drop for SmbReader {
    fn drop(&mut self) {
        let file = Arc::clone(&self.file);
        runtime::spawn(async move {
            if let Err(e) = file.close().await {
                log::debug!("closing SMB file: {e}");
            }
        });
    }
}

fn map_error(err: smb::Error) -> CoreError {
    // NTSTATUS codes from MS-ERREF.
    const ACCESS_DENIED: u32 = 0xC000_0022;
    const OBJECT_NAME_NOT_FOUND: u32 = 0xC000_0034;
    const OBJECT_PATH_NOT_FOUND: u32 = 0xC000_003A;
    const NO_SUCH_USER: u32 = 0xC000_0064;
    const WRONG_PASSWORD: u32 = 0xC000_006A;
    const LOGON_FAILURE: u32 = 0xC000_006D;
    const ACCOUNT_RESTRICTION: u32 = 0xC000_006E;
    const ACCOUNT_DISABLED: u32 = 0xC000_0072;
    const BAD_NETWORK_NAME: u32 = 0xC000_00CC;

    let status = match &err {
        smb::Error::UnexpectedMessageStatus(code) | smb::Error::ReceivedErrorMessage(code, _) => Some(*code),
        _ => None,
    };
    match status {
        Some(NO_SUCH_USER | WRONG_PASSWORD | LOGON_FAILURE | ACCOUNT_RESTRICTION | ACCOUNT_DISABLED) => {
            CoreError::AuthenticationFailed
        }
        Some(ACCESS_DENIED) => CoreError::PermissionDenied { path: err.to_string() },
        Some(OBJECT_NAME_NOT_FOUND | OBJECT_PATH_NOT_FOUND | BAD_NETWORK_NAME) => {
            CoreError::NotFound { path: err.to_string() }
        }
        // smb 0.12 aborts when a server completes setup as a guest session.
        _ if matches!(&err, smb::Error::InvalidState(m) if m.contains("before SSPI authentication completed")) => {
            CoreError::Unsupported {
                detail: "guest SMB sessions; sign in with an account".into(),
            }
        }
        _ => CoreError::Connection {
            detail: err.to_string(),
        },
    }
}
