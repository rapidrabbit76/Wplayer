//! FTP and explicit FTPS (AUTH TLS) via `suppaftp`'s blocking client.
//!
//! Listing and deleting share one control connection; every opened file gets its own,
//! because a running transfer occupies the control channel.

use std::io::{self, Read};
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, UNIX_EPOCH};

use async_trait::async_trait;
use encoding_rs::EUC_KR;
use suppaftp::list::{File as ListEntry, ListParser};
use suppaftp::types::FileType;
use suppaftp::{
    Encoding, FtpError, FtpStream, ImplFtpStream, RustlsConnector, RustlsFtpStream, Status, TlsStream, TransferStream,
};

use super::chunked::ChunkedReader;
use super::{Entry, RandomReader, SourceConfig, Vfs, delete_files_under, path, refuse_root, tls};
use crate::error::{CoreError, CoreResult};

const DEFAULT_PORT: u16 = 21;
const TIMEOUT: Duration = Duration::from_secs(15);

pub struct FtpVfs {
    config: SourceConfig,
    secure: bool,
    names: NameEncoding,
    listing: Mutex<Option<Connection>>,
}

enum Connection {
    Plain(FtpStream),
    Secure(RustlsFtpStream),
}

impl FtpVfs {
    pub fn connect(config: &SourceConfig, secure: bool) -> CoreResult<Self> {
        let names = NameEncoding::default();
        let connection = open_connection(config, secure, &names)?;
        Ok(Self {
            config: config.clone(),
            secure,
            names,
            listing: Mutex::new(Some(connection)),
        })
    }

    fn remote_path(&self, path: &str) -> CoreResult<(String, String)> {
        let relative = path::normalize(path)?;
        let root = path::normalize(&self.config.root_path)?;
        let full = if root == "/" {
            relative.clone()
        } else {
            format!("{root}{}", relative.trim_end_matches('/'))
        };
        Ok((relative, full))
    }

    /// Runs `request` for `remote`, again with CP949 names when the path is not
    /// found: only a listing shows that the server names files in CP949, so a
    /// folder or file asked for before any listing (a Korean root path, a file
    /// opened from history) is looked up in it as well.
    fn with_names_retry<T>(&self, remote: &str, request: impl Fn() -> CoreResult<T>) -> CoreResult<T> {
        match request() {
            Err(CoreError::NotFound { .. }) if !remote.is_ascii() && !self.names.is_legacy() => {
                self.names.set_legacy(true);
                request().inspect_err(|_| self.names.set_legacy(false))
            }
            result => result,
        }
    }

    fn list_blocking(&self, path: &str) -> CoreResult<Vec<Entry>> {
        let (relative, remote) = self.remote_path(path)?;
        self.with_names_retry(&remote, || self.list_remote(&relative, &remote))
    }

    fn list_remote(&self, relative: &str, remote: &str) -> CoreResult<Vec<Entry>> {
        let items = self.on_control(relative, |connection| match connection {
            Connection::Plain(s) => list_entries(s, remote),
            Connection::Secure(s) => list_entries(s, remote),
        })?;
        Ok(items
            .into_iter()
            .filter(|f| f.name() != "." && f.name() != "..")
            .map(|f| to_entry(relative, &f))
            .collect())
    }

    /// Deletes a file with DELE, or an empty folder with RMD when `folder` is set.
    fn delete_blocking(&self, path: &str, folder: bool) -> CoreResult<()> {
        let (relative, remote) = self.remote_path(path)?;
        self.with_names_retry(&remote, || {
            self.on_control(&relative, |connection| match (connection, folder) {
                (Connection::Plain(s), false) => s.rm(&remote),
                (Connection::Secure(s), false) => s.rm(&remote),
                (Connection::Plain(s), true) => s.rmdir(&remote),
                (Connection::Secure(s), true) => s.rmdir(&remote),
            })
        })
    }

    /// Runs `request` on the shared control connection, reconnecting once if
    /// the server dropped it while idle.
    fn on_control<T>(
        &self,
        relative: &str,
        mut request: impl FnMut(&mut Connection) -> Result<T, FtpError>,
    ) -> CoreResult<T> {
        let mut guard = self.listing.lock().expect("ftp listing lock poisoned");
        for attempt in 0..2 {
            if guard.is_none() {
                *guard = Some(open_connection(&self.config, self.secure, &self.names)?);
            }
            match request(guard.as_mut().expect("connection present")) {
                Ok(value) => return Ok(value),
                Err(FtpError::ConnectionError(_)) if attempt == 0 => *guard = None,
                Err(e) => return Err(map_error(e, relative)),
            }
        }
        unreachable!("second attempt always returns")
    }
}

#[async_trait]
impl Vfs for FtpVfs {
    async fn list(&self, path: &str) -> CoreResult<Vec<Entry>> {
        tokio::task::block_in_place(|| self.list_blocking(path))
    }

    fn open(&self, path: &str) -> CoreResult<Box<dyn RandomReader>> {
        let (relative, remote) = self.remote_path(path)?;
        self.with_names_retry(&remote, || self.open_reader(&relative, &remote))
    }

    async fn delete(&self, path: &str) -> CoreResult<()> {
        tokio::task::block_in_place(|| self.delete_blocking(path, false))
    }

    async fn delete_folder(&self, path: &str) -> CoreResult<()> {
        let folder = path::normalize(path)?;
        refuse_root(&folder)?;
        for dir in delete_files_under(self, &folder).await? {
            tokio::task::block_in_place(|| self.delete_blocking(&dir, true))?;
        }
        Ok(())
    }
}

impl FtpVfs {
    fn open_reader(&self, relative: &str, remote: &str) -> CoreResult<Box<dyn RandomReader>> {
        let config = self.config.clone();
        let names = self.names.clone();
        let remote = remote.to_owned();
        Ok(if self.secure {
            let connect = Box::new(move || connect_secure(&config, &names));
            Box::new(ChunkedReader::new(FtpReader::new(connect, remote, relative)?))
        } else {
            let connect = Box::new(move || connect_plain(&config, &names));
            Box::new(ChunkedReader::new(FtpReader::new(connect, remote, relative)?))
        })
    }
}

fn open_connection(config: &SourceConfig, secure: bool, names: &NameEncoding) -> CoreResult<Connection> {
    Ok(if secure {
        Connection::Secure(connect_secure(config, names)?)
    } else {
        Connection::Plain(connect_plain(config, names)?)
    })
}

fn connect_plain(config: &SourceConfig, names: &NameEncoding) -> CoreResult<FtpStream> {
    let address = resolve(&config.host, config.port.unwrap_or(DEFAULT_PORT))?;
    let stream = FtpStream::connect_timeout(address, TIMEOUT).map_err(|e| map_error(e, "/"))?;
    login(stream, config, names)
}

fn connect_secure(config: &SourceConfig, names: &NameEncoding) -> CoreResult<RustlsFtpStream> {
    let address = resolve(&config.host, config.port.unwrap_or(DEFAULT_PORT))?;
    let tls = tls::client_config(config.allow_untrusted_certificates, &[]);
    let stream = RustlsFtpStream::connect_timeout(address, TIMEOUT)
        .and_then(|s| s.into_secure(RustlsConnector::from(Arc::new(tls)), &config.host))
        .map_err(|e| map_error(e, "/"))?;
    login(stream, config, names)
}

fn login<T: TlsStream>(
    mut stream: ImplFtpStream<T>,
    config: &SourceConfig,
    names: &NameEncoding,
) -> CoreResult<ImplFtpStream<T>> {
    let user = config.username.as_deref().unwrap_or("anonymous");
    let password = config.password.as_deref().unwrap_or("");
    stream.set_encoding(names.clone());
    stream.login(user, password).map_err(|e| map_error(e, "/"))?;
    // Servers such as Serv-U send names in UTF-8 only when asked; the others
    // answer with an error, and NameEncoding still reads their CP949 names.
    if let Err(e) = stream.custom_command("OPTS UTF8 ON", &[Status::CommandOk]) {
        log::debug!("ftp server kept its name encoding: {e}");
    }
    stream.transfer_type(FileType::Binary).map_err(|e| map_error(e, "/"))?;
    // Servers behind NAT or in containers often advertise an internal address
    // in their PASV reply; connect to the server we already reach instead.
    stream.set_passive_nat_workaround(true);
    Ok(stream)
}

fn resolve(host: &str, port: u16) -> CoreResult<SocketAddr> {
    (host, port)
        .to_socket_addrs()
        .map_err(|e| CoreError::Connection {
            detail: format!("{host}: {e}"),
        })?
        .next()
        .ok_or_else(|| CoreError::Connection {
            detail: format!("{host}: no address"),
        })
}

/// Prefers machine-readable `MLSD`; falls back to `LIST` for servers without
/// it, such as vsftpd, which reject it as an unknown command.
fn list_entries<T: TlsStream>(stream: &mut ImplFtpStream<T>, remote: &str) -> Result<Vec<ListEntry>, FtpError> {
    match stream.mlsd(Some(remote)) {
        Ok(lines) => Ok(lines.iter().filter_map(|l| ListParser::parse_mlsd(l).ok()).collect()),
        Err(FtpError::UnexpectedResponse(r))
            if matches!(
                r.status,
                Status::BadCommand
                    | Status::BadArguments
                    | Status::NotImplemented
                    | Status::NotImplementedParameter
                    | Status::CommandNotImplemented
            ) =>
        {
            let lines = stream.list(Some(remote))?;
            Ok(lines.iter().filter_map(|l| l.parse::<ListEntry>().ok()).collect())
        }
        Err(e) => Err(e),
    }
}

fn to_entry(dir: &str, file: &ListEntry) -> Entry {
    let is_dir = file.is_directory();
    Entry {
        name: file.name().to_owned(),
        path: path::join(dir, file.name()),
        is_dir,
        size: if is_dir { 0 } else { file.size() as u64 },
        modified_epoch_ms: file
            .modified()
            .duration_since(UNIX_EPOCH)
            .ok()
            .map(|d| d.as_millis() as i64),
    }
}

/// File names in UTF-8, or in CP949 as older Korean servers (Windows, NAS)
/// send them. Once a name arrives in CP949, names are sent in CP949 too, on
/// every connection of the source.
#[derive(Debug, Clone, Default)]
struct NameEncoding {
    legacy: Arc<AtomicBool>,
}

impl NameEncoding {
    fn is_legacy(&self) -> bool {
        self.legacy.load(Ordering::Relaxed)
    }

    fn set_legacy(&self, legacy: bool) {
        self.legacy.store(legacy, Ordering::Relaxed);
    }
}

impl Encoding for NameEncoding {
    fn decode(&self, bytes: &[u8]) -> String {
        if let Ok(text) = std::str::from_utf8(bytes) {
            return text.to_owned();
        }
        match EUC_KR.decode_without_bom_handling_and_without_replacement(bytes) {
            Some(text) => {
                self.set_legacy(true);
                text.into_owned()
            }
            None => String::from_utf8_lossy(bytes).into_owned(),
        }
    }

    fn encode(&self, text: &str) -> io::Result<Vec<u8>> {
        if self.is_legacy() {
            let (bytes, _, unmappable) = EUC_KR.encode(text);
            if !unmappable {
                return Ok(bytes.into_owned());
            }
        }
        Ok(text.as_bytes().to_vec())
    }
}

type Connect<T> = Box<dyn Fn() -> CoreResult<ImplFtpStream<T>> + Send>;

/// Streams one file, restarting the transfer with `REST` when the reader seeks.
struct FtpReader<T: TlsStream + Send> {
    connect: Connect<T>,
    stream: ImplFtpStream<T>,
    remote: String,
    size: u64,
    transfer: Option<(u64, TransferStream<T>)>,
}

impl<T: TlsStream + Send> FtpReader<T> {
    fn new(connect: Connect<T>, remote: String, relative: &str) -> CoreResult<Self> {
        let mut stream = connect()?;
        let size = stream.size(&remote).map_err(|e| map_error(e, relative))? as u64;
        Ok(Self {
            connect,
            stream,
            remote,
            size,
            transfer: None,
        })
    }

    fn restart(&mut self, offset: u64) -> CoreResult<()> {
        if let Some((_, transfer)) = self.transfer.take() {
            // Closing the data connection ends the transfer without ABOR, whose
            // reply varies by server. Any reply (226 done, 426 aborted) leaves the
            // control channel usable; a broken channel is replaced.
            match transfer.finish() {
                Ok(()) | Err(FtpError::UnexpectedResponse(_)) => {}
                Err(e) => {
                    log::debug!("ftp transfer did not close cleanly, reconnecting: {e}");
                    self.stream = (self.connect)()?;
                }
            }
        }
        self.stream
            .resume_transfer(offset as usize)
            .map_err(|e| map_error(e, &self.remote))?;
        let transfer = self
            .stream
            .retr_as_stream(&self.remote)
            .map_err(|e| map_error(e, &self.remote))?;
        self.transfer = Some((offset, transfer));
        Ok(())
    }
}

impl<T: TlsStream + Send> RandomReader for FtpReader<T> {
    fn size(&self) -> Option<u64> {
        Some(self.size)
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        if offset >= self.size {
            return Ok(0);
        }
        if !matches!(&self.transfer, Some((position, _)) if *position == offset) {
            self.restart(offset)?;
        }
        let (position, transfer) = self.transfer.as_mut().expect("transfer started");
        let n = transfer.read(buf)?;
        *position += n as u64;
        Ok(n)
    }
}

fn map_error(err: FtpError, path: &str) -> CoreError {
    match err {
        FtpError::UnexpectedResponse(r) if r.status == Status::NotLoggedIn => CoreError::AuthenticationFailed,
        FtpError::UnexpectedResponse(r) if r.status == Status::FileUnavailable => {
            CoreError::NotFound { path: path.to_owned() }
        }
        FtpError::ConnectionError(e) => CoreError::Connection { detail: e.to_string() },
        other => CoreError::Connection {
            detail: other.to_string(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_utf8_names_and_keeps_sending_utf8() {
        let names = NameEncoding::default();
        assert_eq!(names.decode("영화".as_bytes()), "영화");
        assert!(!names.is_legacy());
        assert_eq!(names.encode("영화").unwrap(), "영화".as_bytes());
    }

    #[test]
    fn switches_to_cp949_once_a_name_arrives_in_it() {
        let names = NameEncoding::default();
        let cp949 = b"\xbf\xb5\xc8\xad"; // 영화
        assert_eq!(names.decode(cp949), "영화");
        assert!(names.clone().is_legacy(), "shared with the source's other connections");
        assert_eq!(names.encode("CWD /영화").unwrap(), b"CWD /\xbf\xb5\xc8\xad");
        // Characters CP949 lacks still go out, as UTF-8.
        assert_eq!(names.encode("🎬").unwrap(), "🎬".as_bytes());
    }
}
