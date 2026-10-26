//! WebDAV over HTTP(S): `PROPFIND` for listings, ranged `GET` for playback.

use std::time::UNIX_EPOCH;

use async_trait::async_trait;
use bytes::Bytes;
use percent_encoding::{AsciiSet, CONTROLS, percent_decode_str, utf8_percent_encode};
use quick_xml::Reader;
use quick_xml::events::Event;
use reqwest::header::{CONTENT_LENGTH, CONTENT_TYPE, RANGE};
use reqwest::{Client, Method, RequestBuilder, Response, StatusCode};

use super::{Entry, RandomReader, SourceConfig, Vfs, path, refuse_root, tls};
use crate::error::{CoreError, CoreResult};
use crate::runtime;

const PATH_SEGMENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'%')
    .add(b'<')
    .add(b'>')
    .add(b'?')
    .add(b'`')
    .add(b'{')
    .add(b'}')
    .add(b'[')
    .add(b']')
    .add(b'^')
    .add(b'|')
    .add(b'\\');

const PROPFIND_BODY: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>
</d:prop></d:propfind>"#;

#[derive(Clone)]
pub struct WebDavVfs {
    client: Client,
    /// `scheme://host[:port]`, no trailing slash.
    origin: String,
    /// Normalized root path on the server, e.g. `/dav/movies`.
    root: String,
    username: Option<String>,
    password: Option<String>,
}

impl WebDavVfs {
    pub fn new(config: &SourceConfig, secure: bool) -> CoreResult<Self> {
        let mut builder = Client::builder().connect_timeout(std::time::Duration::from_secs(15));
        if secure {
            builder = builder.use_preconfigured_tls(tls::client_config(
                config.allow_untrusted_certificates,
                &[b"h2", b"http/1.1"],
            ));
        }
        let client = builder
            .build()
            .map_err(|e| CoreError::Connection { detail: e.to_string() })?;
        let scheme = if secure { "https" } else { "http" };
        let origin = match config.port {
            Some(port) => format!("{scheme}://{}:{port}", config.host),
            None => format!("{scheme}://{}", config.host),
        };
        Ok(Self {
            client,
            origin,
            root: path::normalize(&config.root_path)?,
            username: config.username.clone(),
            password: config.password.clone(),
        })
    }

    /// Server-side path (percent-encoded) for a source-relative path.
    fn server_path(&self, relative: &str) -> String {
        let joined = if self.root == "/" {
            relative.to_owned()
        } else {
            format!("{}{relative}", self.root)
        };
        joined
            .split('/')
            .map(|segment| utf8_percent_encode(segment, PATH_SEGMENT).to_string())
            .collect::<Vec<_>>()
            .join("/")
    }

    async fn propfind(&self, server_path: &str, depth: &str, relative: &str) -> CoreResult<Vec<DavItem>> {
        let response = self
            .request(Method::from_bytes(b"PROPFIND").expect("valid method"), server_path)
            .header("Depth", depth)
            .header(CONTENT_TYPE, "application/xml; charset=utf-8")
            .body(PROPFIND_BODY)
            .send()
            .await
            .map_err(map_reqwest)?;
        let body = check_status(response, relative)
            .await?
            .text()
            .await
            .map_err(map_reqwest)?;
        Ok(parse_multistatus(&body))
    }

    fn request(&self, method: Method, server_path: &str) -> RequestBuilder {
        let request = self.client.request(method, format!("{}{server_path}", self.origin));
        match &self.username {
            Some(user) => request.basic_auth(user, self.password.as_deref()),
            None => request,
        }
    }
}

#[async_trait]
impl Vfs for WebDavVfs {
    async fn list(&self, path: &str) -> CoreResult<Vec<Entry>> {
        let relative = path::normalize(path)?;
        let mut dir = self.server_path(&relative);
        if !dir.ends_with('/') {
            dir.push('/');
        }
        let items = self.propfind(&dir, "1", &relative).await?;
        let own_path = percent_decode(&dir);
        Ok(items
            .into_iter()
            .filter(|item| percent_decode(&item.href).trim_end_matches('/') != own_path.trim_end_matches('/'))
            .filter_map(|item| {
                let decoded = percent_decode(&item.href);
                let name = decoded.trim_end_matches('/').rsplit('/').next()?.to_owned();
                (!name.is_empty()).then(|| Entry {
                    path: path::join(&relative, &name),
                    name,
                    is_dir: item.is_collection,
                    size: if item.is_collection { 0 } else { item.content_length },
                    modified_epoch_ms: item.last_modified_ms,
                })
            })
            .collect())
    }

    fn open(&self, path: &str) -> CoreResult<Box<dyn RandomReader>> {
        let relative = path::normalize(path)?;
        let server_path = self.server_path(&relative);
        let size = runtime::block_on(async {
            let response = self
                .request(Method::HEAD, &server_path)
                .send()
                .await
                .map_err(map_reqwest)?;
            let response = check_status(response, &relative).await?;
            Ok::<_, CoreError>(
                response
                    .headers()
                    .get(CONTENT_LENGTH)
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.parse::<u64>().ok()),
            )
        })?;
        Ok(Box::new(WebDavReader {
            vfs: self.clone(),
            server_path,
            size,
            position: 0,
            response: None,
            pending: Bytes::new(),
        }))
    }

    async fn delete(&self, path: &str) -> CoreResult<()> {
        self.delete_item(&path::normalize(path)?, false).await
    }

    async fn delete_folder(&self, path: &str) -> CoreResult<()> {
        let folder = path::normalize(path)?;
        refuse_root(&folder)?;
        self.delete_item(&folder, true).await
    }
}

impl WebDavVfs {
    /// DELETE removes a collection with everything in it, so check the path is
    /// the kind asked for first.
    async fn delete_item(&self, relative: &str, folder: bool) -> CoreResult<()> {
        let mut server_path = self.server_path(relative);
        let is_collection = self
            .propfind(&server_path, "0", relative)
            .await?
            .iter()
            .any(|item| item.is_collection);
        if is_collection != folder {
            return Err(CoreError::InvalidArgument {
                detail: format!("not a {}: {relative}", if folder { "folder" } else { "file" }),
            });
        }
        if folder && !server_path.ends_with('/') {
            server_path.push('/');
        }
        let response = self
            .request(Method::DELETE, &server_path)
            .send()
            .await
            .map_err(map_reqwest)?;
        match response.status() {
            // 401 is a wrong password; 403 and 405 mean the account or server does not allow deleting.
            StatusCode::FORBIDDEN | StatusCode::METHOD_NOT_ALLOWED => Err(CoreError::PermissionDenied {
                path: relative.to_owned(),
            }),
            _ => check_status(response, relative).await.map(drop),
        }
    }
}

/// Streams a ranged `GET`, reopening it at the new offset whenever libmpv seeks.
struct WebDavReader {
    vfs: WebDavVfs,
    server_path: String,
    size: Option<u64>,
    position: u64,
    response: Option<Response>,
    pending: Bytes,
}

impl WebDavReader {
    fn restart(&mut self, offset: u64) -> CoreResult<()> {
        let request = self
            .vfs
            .request(Method::GET, &self.server_path)
            .header(RANGE, format!("bytes={offset}-"));
        let response = runtime::block_on(async {
            check_status(request.send().await.map_err(map_reqwest)?, &self.server_path).await
        })?;
        if offset > 0 && response.status() != StatusCode::PARTIAL_CONTENT {
            return Err(CoreError::Unsupported {
                detail: "server ignores HTTP range requests".into(),
            });
        }
        self.response = Some(response);
        self.pending = Bytes::new();
        self.position = offset;
        Ok(())
    }
}

impl RandomReader for WebDavReader {
    fn size(&self) -> Option<u64> {
        self.size
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        if self.size.is_some_and(|size| offset >= size) {
            return Ok(0);
        }
        if self.response.is_none() || offset != self.position {
            self.restart(offset)?;
        }
        if self.pending.is_empty() {
            let response = self.response.as_mut().expect("response started");
            match runtime::block_on(response.chunk()).map_err(map_reqwest)? {
                Some(chunk) => self.pending = chunk,
                None => return Ok(0),
            }
        }
        let n = self.pending.len().min(buf.len());
        buf[..n].copy_from_slice(&self.pending.split_to(n));
        self.position += n as u64;
        Ok(n)
    }
}

async fn check_status(response: Response, path: &str) -> CoreResult<Response> {
    match response.status() {
        s if s.is_success() => Ok(response),
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => Err(CoreError::AuthenticationFailed),
        StatusCode::NOT_FOUND => Err(CoreError::NotFound { path: path.to_owned() }),
        s => Err(CoreError::Connection {
            detail: format!("HTTP {s}"),
        }),
    }
}

fn map_reqwest(err: reqwest::Error) -> CoreError {
    CoreError::Connection {
        detail: err.to_string(),
    }
}

fn percent_decode(s: &str) -> String {
    // hrefs may be absolute URLs; keep only the path.
    let path = s
        .find("://")
        .and_then(|i| s[i + 3..].find('/').map(|j| &s[i + 3 + j..]))
        .unwrap_or(s);
    percent_decode_str(path).decode_utf8_lossy().into_owned()
}

#[derive(Debug, Default, PartialEq)]
struct DavItem {
    href: String,
    is_collection: bool,
    content_length: u64,
    last_modified_ms: Option<i64>,
}

/// Extracts `<response>` items from a `207 Multi-Status` body, ignoring namespaces.
fn parse_multistatus(xml: &str) -> Vec<DavItem> {
    let mut reader = Reader::from_str(xml);
    let mut items = Vec::new();
    let mut current: Option<DavItem> = None;
    // Text of the property being read; entity references arrive as separate events.
    let mut text: Option<String> = None;
    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) => match e.local_name().as_ref() {
                "response" => current = Some(DavItem::default()),
                "href" | "getcontentlength" | "getlastmodified" => text = Some(String::new()),
                "collection" => set_collection(&mut current),
                _ => {}
            },
            Ok(Event::Empty(e)) if e.local_name().as_ref() == "collection" => set_collection(&mut current),
            Ok(Event::Text(t)) => {
                if let Some(buf) = text.as_mut() {
                    buf.push_str(t.as_ref());
                }
            }
            Ok(Event::GeneralRef(r)) => {
                if let Some(buf) = text.as_mut() {
                    let entity = format!("&{};", r.into_inner());
                    buf.push_str(&quick_xml::escape::unescape(&entity).unwrap_or_default());
                }
            }
            Ok(Event::End(e)) => {
                let name = e.local_name();
                match (name.as_ref(), current.as_mut(), text.take()) {
                    ("response", _, _) => items.extend(current.take()),
                    ("href", Some(item), Some(value)) => item.href = value.trim().to_owned(),
                    ("getcontentlength", Some(item), Some(value)) => {
                        item.content_length = value.trim().parse().unwrap_or(0)
                    }
                    ("getlastmodified", Some(item), Some(value)) => {
                        item.last_modified_ms = httpdate::parse_http_date(value.trim())
                            .ok()
                            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                            .map(|d| d.as_millis() as i64);
                    }
                    (_, _, pending) => text = pending,
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                log::warn!("invalid PROPFIND response: {e}");
                break;
            }
            _ => {}
        }
    }
    items
}

fn set_collection(current: &mut Option<DavItem>) {
    if let Some(item) = current.as_mut() {
        item.is_collection = true;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response><D:href>/dav/movies/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
  <D:response><D:href>/dav/movies/%EC%98%81%ED%99%94%201.mkv</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>1234</D:getcontentlength>
    <D:getlastmodified>Mon, 12 Jan 2026 10:00:00 GMT</D:getlastmodified></D:prop></D:propstat></D:response>
  <D:response><D:href>https://server.local/dav/movies/Season%202/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection></D:collection></D:resourcetype></D:prop></D:propstat></D:response>
</D:multistatus>"#;

    #[test]
    fn parses_multistatus_with_any_prefix() {
        let items = parse_multistatus(SAMPLE);
        assert_eq!(items.len(), 3);
        assert!(items[0].is_collection);
        assert_eq!(items[1].content_length, 1234);
        assert_eq!(items[1].last_modified_ms, Some(1_768_212_000_000));
        assert!(items[2].is_collection);
    }

    #[test]
    fn decodes_relative_and_absolute_hrefs() {
        assert_eq!(
            percent_decode("/dav/movies/%EC%98%81%ED%99%94%201.mkv"),
            "/dav/movies/영화 1.mkv"
        );
        assert_eq!(
            percent_decode("https://server.local/dav/movies/Season%202/"),
            "/dav/movies/Season 2/"
        );
    }
}
