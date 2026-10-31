//! Runs a link plugin on a page, as the app would, and prints what it found.
//!
//! cargo run -p wplayer-core --example link -- <main.js> <url> [host-without-sni ...]

use wplayer_core::link::{LinkPlugin, resolve_link};

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [script, url, without_sni @ ..] = args.as_slice() else {
        eprintln!("usage: link <main.js> <url> [host-without-sni ...]");
        std::process::exit(2);
    };
    let plugin = LinkPlugin {
        id: script.clone(),
        script: std::fs::read_to_string(script).expect("cannot read the script"),
        without_sni: without_sni.to_vec(),
    };
    match resolve_link(plugin, url.clone()).await {
        Ok(link) => {
            println!("title\t{}", link.title.unwrap_or_default());
            println!("duration\t{}", link.duration_seconds.unwrap_or_default());
            for stream in link.streams {
                println!("stream\t{}\t{}", stream.height.unwrap_or_default(), stream.url);
                for (name, value) in stream.headers {
                    println!("header\t{name}\t{value}");
                }
            }
        }
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}
