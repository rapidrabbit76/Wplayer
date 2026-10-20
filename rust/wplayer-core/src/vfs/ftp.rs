//! FTP and explicit FTPS (AUTH TLS) via `suppaftp`'s blocking client.
//!
//! Listing shares one control connection; every opened file gets its own,
//! because a running transfer occupies the control channel.

use std::io::Read;
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::{Duration, UNIX_EPOCH};

use async_trait::async_trait;
use suppaftp::list::{File as ListEntry, ListParser};
use suppaftp::types::FileType;
use suppaftp::{
    FtpError, FtpStream, ImplFtpStream, RustlsConnector, RustlsFtpStream, Status, TlsStream, TransferStream,
};

use super::chunked::ChunkedReader;
use super::{Entry, RandomReader, SourceConfig, Vfs, path, tls};
use crate::error::{CoreError, CoreResult};

const DEFAULT_PORT: u16 = 21;
const TIMEOUT: Duration = Duration::from_secs(15);

pub struct FtpVfs {
    config: SourceConfig,
    secure: bool,
    listing: Mutex<Option<Connection>>,
}

enum Connection {
    Plain(FtpStream),
    Secure(RustlsFtpStream),
}

impl FtpVfs {
    pub fn connect(config: &SourceConfig, secure: bool) -> CoreResult<Self> {
        let connection = open_connection(config, secure)?;
        Ok(Self {
            config: config.clone(),
            secure,
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

    fn list_blocking(&self, path: &str) -> CoreResult<Vec<Entry>> {
        let (relative, remote) = self.remote_path(path)?;
        let mut guard = self.listing.lock().expect("ftp listing lock poisoned");
        // Reconnect once if the idle control connection was dropped by the server.
        for attempt in 0..2 {
            if guard.is_none() {
                *guard = Some(open_connection(&self.config, self.secure)?);
            }
            let result = match guard.as_mut().expect("connection present") {
                Connection::Plain(s) => list_entries(s, &remote),
                Connection::Secure(s) => list_entries(s, &remote),
            };
            match result {
                Ok(items) => {
                    return Ok(items
                        .into_iter()
                        .filter(|f| f.name() != "." && f.name() != "..")
                        .map(|f| to_entry(&relative, &f))
                        .collect());
                }
                Err(FtpError::ConnectionError(_)) if attempt == 0 => *guard = None,
                Err(e) => return Err(map_error(e, &relative)),
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
        let config = self.config.clone();
        Ok(if self.secure {
            let connect = Box::new(move || connect_secure(&config));
            Box::new(ChunkedReader::new(FtpReader::new(connect, remote, &relative)?))
        } else {
            let connect = Box::new(move || connect_plain(&config));
            Box::new(ChunkedReader::new(FtpReader::new(connect, remote, &relative)?))
        })
    }
}

fn open_connection(config: &SourceConfig, secure: bool) -> CoreResult<Connection> {
    Ok(if secure {
        Connection::Secure(connect_secure(config)?)
    } else {
        Connection::Plain(connect_plain(config)?)
    })
}

fn connect_plain(config: &SourceConfig) -> CoreResult<FtpStream> {
    let address = resolve(&config.host, config.port.unwrap_or(DEFAULT_PORT))?;
    let stream = FtpStream::connect_timeout(address, TIMEOUT).map_err(|e| map_error(e, "/"))?;
    login(stream, config)
}

fn connect_secure(config: &SourceConfig) -> CoreResult<RustlsFtpStream> {
    let address = resolve(&config.host, config.port.unwrap_or(DEFAULT_PORT))?;
    let tls = tls::client_config(config.allow_untrusted_certificates, &[]);
    let stream = RustlsFtpStream::connect_timeout(address, TIMEOUT)
        .and_then(|s| s.into_secure(RustlsConnector::from(Arc::new(tls)), &config.host))
        .map_err(|e| map_error(e, "/"))?;
    login(stream, config)
}

fn login<T: TlsStream>(mut stream: ImplFtpStream<T>, config: &SourceConfig) -> CoreResult<ImplFtpStream<T>> {
    let user = config.username.as_deref().unwrap_or("anonymous");
    let password = config.password.as_deref().unwrap_or("");
    stream.login(user, password).map_err(|e| map_error(e, "/"))?;
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

/// Prefers machine-readable `MLSD`; falls back to `LIST` for older servers.
fn list_entries<T: TlsStream>(stream: &mut ImplFtpStream<T>, remote: &str) -> Result<Vec<ListEntry>, FtpError> {
    match stream.mlsd(Some(remote)) {
        Ok(lines) => Ok(lines.iter().filter_map(|l| ListParser::parse_mlsd(l).ok()).collect()),
        Err(FtpError::UnexpectedResponse(r)) if r.status == Status::CommandNotImplemented => {
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
