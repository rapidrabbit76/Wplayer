//! Link plugins: small JavaScript programs that turn the address of a web
//! page into the video streams on it. The app keeps, updates and picks the
//! plugins; this module runs one in QuickJS, which can do nothing but what the
//! host gives it here: HTTP requests with a cookie jar, and logging.
//!
//! A plugin defines `resolve(url)` and returns
//! `{ title?, thumbnail?, duration?, streams: [{ url, quality?, height?, headers? }] }`.
//! Its HTTP calls are synchronous:
//!
//! ```js
//! const page = http.get(url, { headers: { Cookie: "a=1" } });  // { status, url, headers, body, json() }
//! http.post(url, body, options); http.request({ method, url, headers, body });
//! http.cookies(url);  // the jar's Cookie header for url, "" when none
//! // A Cookie header joins the jar's cookies (set by earlier responses) instead of replacing them.
//! console.log("...");
//! ```

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use reqwest::cookie::{CookieStore, Jar};
use reqwest::{Client, Method, Url};
use rquickjs::{CatchResultExt, Context, Function, Object, Runtime};
use serde::{Deserialize, Serialize};

use crate::error::{CoreError, CoreResult};
use crate::runtime;

/// The host API plugins are written against. Raised only when it changes in a
/// way older plugins would notice; plugins say the lowest one they need.
pub const LINK_PLUGIN_API: u32 = 1;

/// How long one plugin may take in all, HTTP included.
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(60);
/// How long one HTTP request may take.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
/// The largest response body a plugin is handed.
const MAX_BODY_BYTES: usize = 32 * 1024 * 1024;
const MEMORY_LIMIT_BYTES: usize = 256 * 1024 * 1024;
const STACK_LIMIT_BYTES: usize = 1024 * 1024;
/// Sites serve the full page to browsers; plugins may send their own.
const BROWSER_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

/// The JavaScript side of the host API, run before the plugin's script.
const PRELUDE: &str = r#"
"use strict";
const http = (() => {
  const call = (request) => {
    const response = JSON.parse(__wplayer.request(JSON.stringify(request)));
    if (response.error !== undefined) throw new Error(response.error);
    response.json = () => JSON.parse(response.body);
    return response;
  };
  return Object.freeze({
    request: call,
    get: (url, options = {}) => call({ ...options, method: "GET", url }),
    post: (url, body, options = {}) =>
      call({ ...options, method: "POST", url, body: typeof body === "string" ? body : JSON.stringify(body) }),
    cookies: (url) => __wplayer.cookies(url),
  });
})();
const console = Object.freeze({
  log: (...parts) => __wplayer.log(parts.map((p) => (typeof p === "string" ? p : JSON.stringify(p))).join(" ")),
});
function __wplayerRun(url) {
  return JSON.stringify(resolve(url));
}
"#;

#[uniffi::export]
pub fn link_plugin_api() -> u32 {
    LINK_PLUGIN_API
}

/// A plugin as the app hands it over to run.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LinkPlugin {
    /// Names the plugin in logs and errors.
    pub id: String,
    /// The plugin's script, which defines `resolve(url)`.
    pub script: String,
    /// Hosts, with their subdomains, contacted without sending the server
    /// name (SNI) in the TLS handshake. Their default certificate must still
    /// be valid for the name.
    pub without_sni: Vec<String>,
}

/// One way to play what a link shows.
#[derive(Debug, Clone, PartialEq, Deserialize, uniffi::Record)]
pub struct LinkStream {
    pub url: String,
    /// The plugin's label for it, such as "1080p".
    #[serde(default)]
    pub quality: Option<String>,
    /// Lines of video, for picking among several.
    #[serde(default)]
    pub height: Option<u32>,
    /// Headers the stream's server wants with every request.
    #[serde(default)]
    pub headers: HashMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Deserialize, uniffi::Record)]
pub struct ResolvedLink {
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub thumbnail: Option<String>,
    #[serde(default, rename = "duration")]
    pub duration_seconds: Option<f64>,
    pub streams: Vec<LinkStream>,
}

/// Runs [plugin] on [url]. Cancelling the call stops the script.
#[uniffi::export]
pub async fn resolve_link(plugin: LinkPlugin, url: String) -> CoreResult<ResolvedLink> {
    let cancelled = Arc::new(AtomicBool::new(false));
    // Dropped with the call's future, which stops a script still running.
    let _stop = StopOnDrop(Arc::clone(&cancelled));
    let (sender, receiver) = tokio::sync::oneshot::channel();
    // QuickJS runs on a thread of its own: it blocks on HTTP, which must not
    // happen on the runtime's workers.
    std::thread::Builder::new()
        .name("wplayer-link".into())
        .spawn(move || {
            let _ = sender.send(run(&plugin, &url, &cancelled));
        })
        .map_err(CoreError::from)?;
    receiver.await.map_err(|_| CoreError::Cancelled)?
}

struct StopOnDrop(Arc<AtomicBool>);

impl Drop for StopOnDrop {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Relaxed);
    }
}

/// What a script's exception says, for people: its first line without the
/// `Error: ` prefix. The stack trace goes to the log only.
fn headline(detail: &str) -> &str {
    let line = detail.lines().next().unwrap_or_default().trim();
    line.strip_prefix("Error: ").unwrap_or(line)
}

fn plugin_error(plugin: &LinkPlugin, detail: impl std::fmt::Display) -> CoreError {
    CoreError::Plugin {
        detail: format!("{}: {detail}", plugin.id),
    }
}

fn run(plugin: &LinkPlugin, url: &str, cancelled: &Arc<AtomicBool>) -> CoreResult<ResolvedLink> {
    let deadline = Instant::now() + RESOLVE_TIMEOUT;
    let http = Arc::new(Http::new(&plugin.without_sni, Arc::clone(cancelled), deadline)?);
    let rt = Runtime::new().map_err(|e| plugin_error(plugin, e))?;
    rt.set_memory_limit(MEMORY_LIMIT_BYTES);
    rt.set_max_stack_size(STACK_LIMIT_BYTES);
    let stop = Arc::clone(cancelled);
    rt.set_interrupt_handler(Some(Box::new(move || {
        stop.load(Ordering::Relaxed) || Instant::now() > deadline
    })));
    let ctx = Context::full(&rt).map_err(|e| plugin_error(plugin, e))?;
    let json = ctx.with(|ctx| -> Result<String, String> {
        let host = Object::new(ctx.clone()).map_err(|e| e.to_string())?;
        let requests = Arc::clone(&http);
        let request =
            Function::new(ctx.clone(), move |json: String| requests.request_json(&json)).map_err(|e| e.to_string())?;
        let jar = Arc::clone(&http);
        let cookies =
            Function::new(ctx.clone(), move |url: String| jar.cookie_header(&url)).map_err(|e| e.to_string())?;
        let id = plugin.id.clone();
        let log = Function::new(ctx.clone(), move |message: String| {
            log::info!("link plugin {id}: {message}")
        })
        .map_err(|e| e.to_string())?;
        host.set("request", request).map_err(|e| e.to_string())?;
        host.set("cookies", cookies).map_err(|e| e.to_string())?;
        host.set("log", log).map_err(|e| e.to_string())?;
        ctx.globals().set("__wplayer", host).map_err(|e| e.to_string())?;
        ctx.eval::<(), _>(PRELUDE).catch(&ctx).map_err(|e| e.to_string())?;
        ctx.eval::<(), _>(plugin.script.as_str())
            .catch(&ctx)
            .map_err(|e| e.to_string())?;
        let entry: Function = ctx.globals().get("__wplayerRun").map_err(|e| e.to_string())?;
        entry.call::<_, String>((url,)).catch(&ctx).map_err(|e| e.to_string())
    });
    if cancelled.load(Ordering::Relaxed) {
        return Err(CoreError::Cancelled);
    }
    let json = json.map_err(|detail| {
        log::warn!("link plugin {}: {detail}", plugin.id);
        plugin_error(plugin, headline(&detail))
    })?;
    let resolved: ResolvedLink = serde_json::from_str(&json)
        .map_err(|e| plugin_error(plugin, format!("resolve() returned {json:.200}: {e}")))?;
    if resolved.streams.is_empty() {
        return Err(plugin_error(plugin, "no streams on this page"));
    }
    Ok(resolved)
}

#[derive(Deserialize)]
struct HttpRequest {
    #[serde(default = "get")]
    method: String,
    url: String,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    body: Option<String>,
}

fn get() -> String {
    "GET".into()
}

#[derive(Serialize)]
struct HttpResponse {
    status: u16,
    /// Where the request ended up after redirects.
    url: String,
    /// Lower-case names; repeated headers joined with ", ", except `set-cookie`.
    headers: HashMap<String, String>,
    #[serde(rename = "setCookies")]
    set_cookies: Vec<String>,
    body: String,
}

/// The HTTP side of one plugin run: one cookie jar, shared by a client that
/// sends the server name and one that leaves it out for [without_sni] hosts.
struct Http {
    jar: Arc<Jar>,
    client: Client,
    client_without_sni: Client,
    without_sni: Vec<String>,
    cancelled: Arc<AtomicBool>,
    deadline: Instant,
}

impl Http {
    fn new(without_sni: &[String], cancelled: Arc<AtomicBool>, deadline: Instant) -> CoreResult<Self> {
        let jar = Arc::new(Jar::default());
        let builder = || {
            Client::builder()
                .cookie_provider(Arc::clone(&jar))
                .user_agent(BROWSER_USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .connect_timeout(Duration::from_secs(15))
        };
        let connection = |e: reqwest::Error| CoreError::Connection { detail: e.to_string() };
        let client = builder().use_rustls_tls().build().map_err(connection)?;
        let client_without_sni = builder()
            .use_preconfigured_tls(tls_without_sni().clone())
            .build()
            .map_err(connection)?;
        Ok(Self {
            jar,
            client,
            client_without_sni,
            without_sni: without_sni
                .iter()
                .map(|h| h.trim().trim_start_matches('.').to_ascii_lowercase())
                .collect(),
            cancelled,
            deadline,
        })
    }

    /// A request from the script as JSON, answered as JSON; failures come
    /// back as `{ "error": … }`, which the prelude throws.
    fn request_json(&self, json: &str) -> String {
        let response = serde_json::from_str::<HttpRequest>(json)
            .map_err(|e| format!("bad request: {e}"))
            .and_then(|request| self.request(request));
        match response {
            Ok(response) => serde_json::to_string(&response).unwrap_or_else(|e| error_json(&e.to_string())),
            Err(error) => error_json(&error),
        }
    }

    fn request(&self, request: HttpRequest) -> Result<HttpResponse, String> {
        if self.cancelled.load(Ordering::Relaxed) {
            return Err("cancelled".into());
        }
        let left = self.deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err("out of time".into());
        }
        let url = Url::parse(&request.url).map_err(|e| format!("{}: {e}", request.url))?;
        if !matches!(url.scheme(), "http" | "https") {
            return Err(format!("{}: only http and https", request.url));
        }
        let method = Method::from_bytes(request.method.to_ascii_uppercase().as_bytes()).map_err(|e| e.to_string())?;
        let client = if url.host_str().is_some_and(|host| self.skips_sni(host)) {
            &self.client_without_sni
        } else {
            &self.client
        };
        // A Cookie header from the script would replace the jar's; send both, as a browser would.
        let jar_cookies = self.cookie_header(url.as_str());
        let mut builder = client.request(method, url);
        for (name, value) in &request.headers {
            if name.eq_ignore_ascii_case("cookie") {
                builder = builder.header(name, merge_cookies(&jar_cookies, value));
            } else {
                builder = builder.header(name, value);
            }
        }
        if let Some(body) = request.body {
            builder = builder.body(body);
        }
        let cancelled = Arc::clone(&self.cancelled);
        let fetch = async move {
            let response = builder.send().await.map_err(|e| describe(&e))?;
            let status = response.status().as_u16();
            let final_url = response.url().to_string();
            let mut headers = HashMap::<String, String>::new();
            let mut set_cookies = Vec::new();
            for (name, value) in response.headers() {
                let value = String::from_utf8_lossy(value.as_bytes()).into_owned();
                if name == reqwest::header::SET_COOKIE {
                    set_cookies.push(value);
                    continue;
                }
                headers
                    .entry(name.as_str().to_owned())
                    .and_modify(|joined| {
                        joined.push_str(", ");
                        joined.push_str(&value);
                    })
                    .or_insert(value);
            }
            let mut body = Vec::new();
            let mut chunks = response.bytes_stream();
            while let Some(chunk) = chunks.next().await {
                if cancelled.load(Ordering::Relaxed) {
                    return Err("cancelled".to_owned());
                }
                let chunk = chunk.map_err(|e| describe(&e))?;
                if body.len() + chunk.len() > MAX_BODY_BYTES {
                    return Err(format!("response over {} MB", MAX_BODY_BYTES / (1024 * 1024)));
                }
                body.extend_from_slice(&chunk);
            }
            Ok(HttpResponse {
                status,
                url: final_url,
                headers,
                set_cookies,
                body: String::from_utf8_lossy(&body).into_owned(),
            })
        };
        runtime::block_on(async move {
            tokio::time::timeout(left, fetch)
                .await
                .unwrap_or_else(|_| Err("out of time".to_owned()))
        })
    }

    fn cookie_header(&self, url: &str) -> String {
        Url::parse(url)
            .ok()
            .and_then(|url| self.jar.cookies(&url))
            .and_then(|value| value.to_str().ok().map(str::to_owned))
            .unwrap_or_default()
    }

    fn skips_sni(&self, host: &str) -> bool {
        let host = host.to_ascii_lowercase();
        self.without_sni
            .iter()
            .any(|h| host == *h || host.strip_suffix(h.as_str()).is_some_and(|rest| rest.ends_with('.')))
    }
}

/// The jar's cookies with the script's, the script's winning on a name both have.
fn merge_cookies(jar: &str, script: &str) -> String {
    let name = |pair: &str| pair.split('=').next().unwrap_or_default().trim().to_owned();
    let mine: Vec<&str> = script.split(';').map(str::trim).filter(|p| !p.is_empty()).collect();
    let names: Vec<String> = mine.iter().map(|p| name(p)).collect();
    jar.split(';')
        .map(str::trim)
        .filter(|p| !p.is_empty() && !names.contains(&name(p)))
        .chain(mine.iter().copied())
        .collect::<Vec<_>>()
        .join("; ")
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

/// The error with its causes, which say what actually failed (DNS, TLS, …).
fn describe(error: &dyn std::error::Error) -> String {
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        text.push_str(": ");
        text.push_str(&cause.to_string());
        source = cause.source();
    }
    text
}

/// TLS that verifies certificates as usual but leaves out the server name.
fn tls_without_sni() -> &'static rustls::ClientConfig {
    static CONFIG: OnceLock<rustls::ClientConfig> = OnceLock::new();
    CONFIG.get_or_init(|| {
        let mut config = crate::vfs::tls::client_config(false, &[b"h2", b"http/1.1"]);
        config.enable_sni = false;
        config
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plugin(script: &str) -> LinkPlugin {
        LinkPlugin {
            id: "test".into(),
            script: script.into(),
            without_sni: vec!["example.com".into()],
        }
    }

    fn resolve(script: &str, url: &str) -> CoreResult<ResolvedLink> {
        run(&plugin(script), url, &Arc::new(AtomicBool::new(false)))
    }

    #[test]
    fn a_plugin_returns_its_streams() {
        let resolved = resolve(
            r#"function resolve(url) {
                const id = url.match(/v=(\d+)/)[1];
                return { title: "Video " + id, duration: 61.5, streams: [
                    { url: "https://cdn.test/" + id + "/720.m3u8", quality: "720p", height: 720, headers: { Referer: url } },
                    { url: "https://cdn.test/" + id + "/480.m3u8", height: 480 },
                ] };
            }"#,
            "https://site.test/watch?v=42",
        )
        .unwrap();
        assert_eq!(resolved.title.as_deref(), Some("Video 42"));
        assert_eq!(resolved.duration_seconds, Some(61.5));
        assert_eq!(resolved.streams.len(), 2);
        assert_eq!(resolved.streams[0].height, Some(720));
        assert_eq!(resolved.streams[0].headers["Referer"], "https://site.test/watch?v=42");
        assert_eq!(resolved.streams[1].quality, None);
    }

    #[test]
    fn a_plugin_may_bind_resolve_with_const() {
        let resolved = resolve(
            r#"const resolve = (url) => ({ streams: [{ url }] });"#,
            "https://a.test/v.mp4",
        )
        .unwrap();
        assert_eq!(resolved.streams[0].url, "https://a.test/v.mp4");
    }

    #[test]
    fn a_throwing_plugin_says_why() {
        let error = resolve(
            r#"function resolve() { throw new Error("video removed"); }"#,
            "https://a.test",
        )
        .unwrap_err();
        assert!(
            matches!(&error, CoreError::Plugin { detail } if detail.contains("video removed")),
            "{error:?}"
        );
    }

    #[test]
    fn an_exception_reaches_people_without_its_stack() {
        let error = resolve(
            r#"function resolve() { throw new Error("not in this country"); }"#,
            "https://a.test",
        )
        .unwrap_err();
        let CoreError::Plugin { detail } = error else {
            panic!("{error:?}")
        };
        assert_eq!(detail, "test: not in this country");
    }

    #[test]
    fn a_page_without_streams_is_an_error() {
        let error = resolve(r#"function resolve() { return { streams: [] }; }"#, "https://a.test").unwrap_err();
        assert!(
            matches!(&error, CoreError::Plugin { detail } if detail.contains("no streams")),
            "{error:?}"
        );
    }

    #[test]
    fn http_failures_reach_the_script_as_exceptions() {
        let resolved = resolve(
            r#"function resolve() {
                try { http.get("ftp://a.test/x"); } catch (e) { return { title: e.message, streams: [{ url: "x" }] }; }
            }"#,
            "https://a.test",
        )
        .unwrap();
        assert!(resolved.title.unwrap().contains("only http and https"));
    }

    #[test]
    fn an_endless_plugin_is_stopped() {
        let cancelled = Arc::new(AtomicBool::new(false));
        let stopper = Arc::clone(&cancelled);
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(200));
            stopper.store(true, Ordering::Relaxed);
        });
        let error = run(
            &plugin("function resolve() { for (;;) {} }"),
            "https://a.test",
            &cancelled,
        )
        .unwrap_err();
        assert!(matches!(error, CoreError::Cancelled), "{error:?}");
    }

    #[test]
    fn a_scripts_cookies_join_the_jars_and_win_on_the_same_name() {
        assert_eq!(
            merge_cookies("sid=1; lang=de", "lang=en; age=1"),
            "sid=1; lang=en; age=1"
        );
        assert_eq!(merge_cookies("", "a=1"), "a=1");
        assert_eq!(merge_cookies("sid=1", ""), "sid=1");
    }

    #[test]
    fn hosts_without_sni_include_their_subdomains_only() {
        let http = Http::new(
            &["example.com".into()],
            Arc::new(AtomicBool::new(false)),
            Instant::now(),
        )
        .unwrap();
        assert!(http.skips_sni("example.com"));
        assert!(http.skips_sni("www.EXAMPLE.com"));
        assert!(!http.skips_sni("badexample.com"));
        assert!(!http.skips_sni("example.com.evil"));
    }
}
