//! Comic archives (zip/cbz, 7z/cb7, rar/cbr) on any source, read in place with libarchive.

mod libarchive;
mod natural_order;

use std::borrow::Cow;
use std::sync::{Mutex, PoisonError};

use encoding_rs::EUC_KR;
use icu_normalizer::ComposingNormalizer;

use crate::error::{CoreError, CoreResult};
use crate::{runtime, source};
use libarchive::Archive;

/// An image inside an archive.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ArchiveEntry {
    /// Path inside the archive, for [`read_archive_entry`].
    pub path: String,
    /// Uncompressed size, when the archive records it.
    pub size: Option<u64>,
    /// Position in the archive. Reading entries in increasing index order is
    /// cheapest: solid 7z and RAR archives decompress everything before an entry.
    pub index: u32,
}

const IMAGE_EXTENSIONS: [&str; 10] = [
    "jpg", "jpeg", "jpe", "png", "gif", "bmp", "webp", "heic", "heif", "avif",
];

/// Comic pages are far smaller; anything larger is refused rather than held in memory.
const MAX_ENTRY_SIZE: usize = 64 * 1024 * 1024;

const READ_CHUNK: usize = 256 * 1024;

/// Lists the images of archive `path` on a source, ordered by inner path the
/// way comic readers order pages (numbers by value, case ignored).
/// Blocks; call from a background thread.
#[uniffi::export]
pub fn list_archive_images(source_id: String, path: String) -> CoreResult<Vec<ArchiveEntry>> {
    // Some readers spawn work (and clean up) on the runtime.
    let _runtime = runtime::enter();
    let mut archive = open(&source_id, &path)?;
    let mut images = Vec::new();
    while let Some(header) = archive.next_header()? {
        let entry_path = decode_name(&header.raw_path);
        if !header.is_file || !is_page(&entry_path) {
            continue;
        }
        if header.encrypted {
            return Err(encrypted());
        }
        images.push(ArchiveEntry {
            path: entry_path,
            size: header.size,
            index: header.index,
        });
    }
    images.sort_by(|a, b| natural_order::compare(&a.path, &b.path));
    Ok(images)
}

/// Reads the file `entry_path` of archive `path` on a source. Fails with
/// `NotFound` when the archive has no such file, and with `Unsupported` for an
/// encrypted entry, one over 64 MiB, or an archive libarchive cannot read.
/// Blocks; call from a background thread.
#[uniffi::export]
pub fn read_archive_entry(source_id: String, path: String, entry_path: String) -> CoreResult<Vec<u8>> {
    let _runtime = runtime::enter();
    let key = (source_id, path);
    // An entry further on continues from the last read. Anything else (an earlier
    // entry, a failure on a stale connection) starts again from the beginning.
    if let Some(mut archive) = take_last_read(&key)
        && let Ok(Some(data)) = read_ahead(&mut archive, &entry_path)
    {
        keep_last_read(key, archive);
        return Ok(data);
    }
    let mut archive = open(&key.0, &key.1)?;
    let data = read_ahead(&mut archive, &entry_path)?.ok_or(CoreError::NotFound { path: entry_path })?;
    keep_last_read(key, archive);
    Ok(data)
}

type ArchiveKey = (String, String);

/// The archive the last entry was read from, positioned just after it, so
/// reading pages in order neither reopens the archive nor decompresses a solid
/// block again for every page. Taken out while in use, so concurrent readers
/// open their own instead of waiting on network reads.
static LAST_READ: Mutex<Option<(ArchiveKey, Archive)>> = Mutex::new(None);

fn take_last_read(key: &ArchiveKey) -> Option<Archive> {
    let mut last = LAST_READ.lock().unwrap_or_else(PoisonError::into_inner);
    if last.as_ref().is_some_and(|(last_key, _)| last_key == key) {
        last.take().map(|(_, archive)| archive)
    } else {
        None
    }
}

fn keep_last_read(key: ArchiveKey, archive: Archive) {
    *LAST_READ.lock().unwrap_or_else(PoisonError::into_inner) = Some((key, archive));
}

fn open(source_id: &str, path: &str) -> CoreResult<Archive> {
    Archive::open(source::get(source_id)?.open(path)?)
}

/// Reads file `entry_path` if it comes after the archive's current position.
fn read_ahead(archive: &mut Archive, entry_path: &str) -> CoreResult<Option<Vec<u8>>> {
    while let Some(header) = archive.next_header()? {
        if !header.is_file || decode_name(&header.raw_path) != entry_path {
            continue;
        }
        if header.encrypted {
            return Err(encrypted());
        }
        let expected = header.size.unwrap_or(0);
        if expected > MAX_ENTRY_SIZE as u64 {
            return Err(too_large());
        }
        let mut data = Vec::with_capacity(expected as usize + READ_CHUNK);
        loop {
            let len = data.len();
            data.resize(len + READ_CHUNK, 0);
            let n = archive.read(&mut data[len..])?;
            data.truncate(len + n);
            if n == 0 {
                return Ok(Some(data));
            }
            if data.len() > MAX_ENTRY_SIZE {
                return Err(too_large());
            }
        }
    }
    Ok(None)
}

/// Names stored without a charset are decoded like FTP names: UTF-8 when valid,
/// otherwise CP949, which Korean Windows zip tools write, otherwise lossily.
/// All names are then composed (NFC): macOS tools store Hangul decomposed.
fn decode_name(raw: &[u8]) -> String {
    let name = match std::str::from_utf8(raw) {
        Ok(name) => Cow::Borrowed(name),
        Err(_) => EUC_KR
            .decode_without_bom_handling_and_without_replacement(raw)
            .unwrap_or_else(|| String::from_utf8_lossy(raw)),
    };
    ComposingNormalizer::new_nfc().normalize(&name).into_owned()
}

/// Pages are images outside hidden files and folders (`._*` resource forks) and `__MACOSX/`.
fn is_page(path: &str) -> bool {
    let hidden = path
        .split('/')
        .any(|part| part == "__MACOSX" || (part.starts_with('.') && part != "."));
    !hidden
        && path
            .rsplit_once('.')
            .is_some_and(|(_, extension)| IMAGE_EXTENSIONS.iter().any(|e| extension.eq_ignore_ascii_case(e)))
}

fn encrypted() -> CoreError {
    CoreError::Unsupported {
        detail: "encrypted archive".to_owned(),
    }
}

fn too_large() -> CoreError {
    CoreError::Unsupported {
        detail: format!("archive entry larger than {} MiB", MAX_ENTRY_SIZE / (1024 * 1024)),
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Once;

    use super::*;
    use crate::vfs::{Protocol, SourceConfig};

    const SOURCE: &str = "archive-fixtures";

    fn fixtures() -> String {
        static REGISTER: Once = Once::new();
        REGISTER.call_once(|| {
            let config = SourceConfig {
                protocol: Protocol::Local,
                host: String::new(),
                port: None,
                root_path: concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/archive").to_owned(),
                username: None,
                password: None,
                allow_untrusted_certificates: false,
            };
            runtime::block_on(source::register_source(SOURCE.into(), config)).unwrap();
        });
        SOURCE.to_owned()
    }

    /// What make_fixtures.py stores in each page.
    fn page(name: &str) -> Vec<u8> {
        format!("page {}\n", name.rsplit('/').next().unwrap())
            .repeat(100)
            .into_bytes()
    }

    fn list(archive: &str) -> CoreResult<Vec<ArchiveEntry>> {
        list_archive_images(fixtures(), archive.into())
    }

    fn read(archive: &str, entry: &str) -> CoreResult<Vec<u8>> {
        read_archive_entry(fixtures(), archive.into(), entry.into())
    }

    fn paths(entries: &[ArchiveEntry]) -> Vec<&str> {
        entries.iter().map(|e| e.path.as_str()).collect()
    }

    const PAGES: [&str; 4] = ["책/1.png", "책/2.jpg", "책/10.JPG", "책/cover.webp"];

    #[test]
    fn lists_only_pages_in_reading_order() {
        for archive in ["/pages.zip", "/pages.7z"] {
            let entries = list(archive).unwrap();
            assert_eq!(paths(&entries), PAGES, "{archive}");
            for entry in &entries {
                assert_eq!(
                    entry.size,
                    Some(page(&entry.path).len() as u64),
                    "{archive} {}",
                    entry.path
                );
            }
        }
    }

    #[test]
    fn reads_pages_in_any_order() {
        for archive in ["/pages.zip", "/pages.7z"] {
            for entry in ["책/10.JPG", "책/1.png", "책/2.jpg", "책/2.jpg", "책/cover.webp"] {
                assert_eq!(read(archive, entry).unwrap(), page(entry), "{archive} {entry}");
            }
        }
    }

    #[test]
    fn decodes_cp949_names() {
        assert_eq!(paths(&list("/cp949.zip").unwrap()), ["만화/01.jpg", "만화/02.jpg"]);
        assert_eq!(read("/cp949.zip", "만화/02.jpg").unwrap(), page("02.jpg"));
    }

    #[test]
    fn reads_rar4_unicode_names() {
        assert_eq!(
            read("/rar4-unicode.rar", "abcdefghijklmnopqrsテスト.txt").unwrap(),
            b"aaaaaaaaaaaaaaa\n"
        );
        assert_eq!(
            read(
                "/rar4-unicode.rar",
                "表だよ/漢字長いファイル名long-filename-in-漢字.txt"
            )
            .unwrap(),
            b"kanji"
        );
    }

    #[test]
    fn reads_solid_rar5_forward_and_back() {
        // libarchive's own fixture: testN.bin holds the little-endian i32s i * (i - 1) + N - 1.
        let expected = |n: i32| -> Vec<u8> {
            (0..1024)
                .flat_map(|i: i32| (i * (i - 1) + n - 1).to_le_bytes())
                .collect()
        };
        for n in [3, 4, 1, 2] {
            assert_eq!(
                read("/rar5-solid.rar", &format!("test{n}.bin")).unwrap(),
                expected(n),
                "test{n}.bin"
            );
        }
    }

    #[test]
    fn refuses_encrypted_entries() {
        assert!(matches!(list("/encrypted.zip"), Err(CoreError::Unsupported { .. })));
        assert!(matches!(
            read("/encrypted.zip", "책/1.png"),
            Err(CoreError::Unsupported { .. })
        ));
        // libarchive's fixture encrypts b.txt and d.txt only.
        assert!(matches!(
            read("/rar4-encrypted.rar", "b.txt"),
            Err(CoreError::Unsupported { .. })
        ));
        assert_eq!(read("/rar4-encrypted.rar", "c.txt").unwrap(), b"This is from c.txt");
    }

    #[test]
    fn missing_entries_and_files_are_not_found() {
        assert!(matches!(
            read("/pages.zip", "책/3.jpg"),
            Err(CoreError::NotFound { .. })
        ));
        assert!(matches!(read("/pages.zip", "책"), Err(CoreError::NotFound { .. })));
        assert!(matches!(list("/missing.zip"), Err(CoreError::NotFound { .. })));
    }

    #[test]
    fn other_files_are_unsupported() {
        assert!(matches!(list("/make_fixtures.py"), Err(CoreError::Unsupported { .. })));
    }
}
