//! The part of libarchive's read API the comic reader needs, driven over a
//! [`RandomReader`].
//!
//! libarchive pulls bytes through callbacks, so an archive on a network source
//! is read in place: the zip central directory and the 7z headers are reached
//! by seeking instead of downloading the file.

use std::ffi::{CStr, c_char, c_int, c_void};
use std::ptr::{self, NonNull};
use std::sync::LazyLock;

use crate::error::{CoreError, CoreResult};
use crate::vfs::RandomReader;

#[repr(C)]
struct RawArchive {
    _opaque: [u8; 0],
}

#[repr(C)]
struct RawEntry {
    _opaque: [u8; 0],
}

type ReadCallback = unsafe extern "C" fn(*mut RawArchive, *mut c_void, *mut *const c_void) -> isize;
type SeekCallback = unsafe extern "C" fn(*mut RawArchive, *mut c_void, i64, c_int) -> i64;

const ARCHIVE_EOF: c_int = 1;
const ARCHIVE_WARN: c_int = -20;
const ARCHIVE_FATAL: c_int = -30;
const AE_IFREG: libc::mode_t = 0o100000;

unsafe extern "C" {
    fn archive_read_new() -> *mut RawArchive;
    fn archive_read_free(archive: *mut RawArchive) -> c_int;
    fn archive_read_support_format_7zip(archive: *mut RawArchive) -> c_int;
    fn archive_read_support_format_rar(archive: *mut RawArchive) -> c_int;
    fn archive_read_support_format_rar5(archive: *mut RawArchive) -> c_int;
    fn archive_read_support_format_zip_seekable(archive: *mut RawArchive) -> c_int;
    fn archive_read_set_read_callback(archive: *mut RawArchive, callback: Option<ReadCallback>) -> c_int;
    fn archive_read_set_seek_callback(archive: *mut RawArchive, callback: Option<SeekCallback>) -> c_int;
    fn archive_read_set_callback_data(archive: *mut RawArchive, data: *mut c_void) -> c_int;
    fn archive_read_open1(archive: *mut RawArchive) -> c_int;
    fn archive_read_next_header(archive: *mut RawArchive, entry: *mut *mut RawEntry) -> c_int;
    fn archive_read_data(archive: *mut RawArchive, buf: *mut c_void, len: usize) -> isize;
    fn archive_error_string(archive: *mut RawArchive) -> *const c_char;
    fn archive_entry_pathname(entry: *mut RawEntry) -> *const c_char;
    fn archive_entry_filetype(entry: *mut RawEntry) -> libc::mode_t;
    fn archive_entry_size(entry: *mut RawEntry) -> i64;
    fn archive_entry_size_is_set(entry: *mut RawEntry) -> c_int;
    fn archive_entry_is_encrypted(entry: *mut RawEntry) -> c_int;
}

/// One entry header, in archive order.
pub(super) struct Header {
    /// Position among all headers of the archive, directories included.
    pub index: u32,
    /// The stored name: UTF-8 when the format records the charset, raw bytes otherwise.
    pub raw_path: Vec<u8>,
    pub is_file: bool,
    pub size: Option<u64>,
    pub encrypted: bool,
}

/// An archive opened for reading. Headers and data come strictly in order;
/// going back means opening the archive again.
pub(super) struct Archive {
    raw: NonNull<RawArchive>,
    /// Boxed so the address libarchive holds for the callbacks stays put.
    source: Box<Source>,
    next_index: u32,
}

// SAFETY: a libarchive handle has no thread affinity, and `&mut self` keeps it on one thread at a time.
unsafe impl Send for Archive {}

impl Archive {
    pub fn open(reader: Box<dyn RandomReader>) -> CoreResult<Self> {
        let _names = Utf8Names::enter();
        let raw = NonNull::new(unsafe { archive_read_new() }).expect("libarchive could not allocate a reader");
        let mut archive = Self {
            raw,
            source: Box::new(Source {
                reader,
                position: 0,
                block: Vec::new(),
                next_len: FIRST_READ,
                error: None,
            }),
            next_index: 0,
        };
        let a = archive.raw.as_ptr();
        // SAFETY: `a` is a fresh reader, and the source outlives it (freed first in `drop`).
        let status = unsafe {
            archive_read_support_format_7zip(a);
            archive_read_support_format_rar(a);
            archive_read_support_format_rar5(a);
            archive_read_support_format_zip_seekable(a);
            archive_read_set_read_callback(a, Some(read));
            archive_read_set_seek_callback(a, Some(seek));
            archive_read_set_callback_data(a, ptr::from_mut(archive.source.as_mut()).cast());
            archive_read_open1(a)
        };
        archive.check(status)?;
        Ok(archive)
    }

    /// The next header, or `None` past the last one. Skips the rest of the current entry's data.
    pub fn next_header(&mut self) -> CoreResult<Option<Header>> {
        let _names = Utf8Names::enter();
        let mut entry = ptr::null_mut();
        let status = unsafe { archive_read_next_header(self.raw.as_ptr(), &mut entry) };
        if status == ARCHIVE_EOF {
            return Ok(None);
        }
        self.check(status)?;
        let index = self.next_index;
        self.next_index += 1;
        // SAFETY: `entry` stays valid until the next call on this archive.
        unsafe {
            let name = archive_entry_pathname(entry);
            Ok(Some(Header {
                index,
                raw_path: if name.is_null() {
                    Vec::new()
                } else {
                    CStr::from_ptr(name).to_bytes().to_vec()
                },
                is_file: archive_entry_filetype(entry) == AE_IFREG,
                size: (archive_entry_size_is_set(entry) != 0)
                    .then(|| u64::try_from(archive_entry_size(entry)).ok())
                    .flatten(),
                encrypted: archive_entry_is_encrypted(entry) != 0,
            }))
        }
    }

    /// Reads the current entry's data into `buf`; returns 0 at its end.
    pub fn read(&mut self, buf: &mut [u8]) -> CoreResult<usize> {
        let n = unsafe { archive_read_data(self.raw.as_ptr(), buf.as_mut_ptr().cast(), buf.len()) };
        usize::try_from(n).map_err(|_| self.error())
    }

    fn check(&mut self, status: c_int) -> CoreResult<()> {
        if status >= ARCHIVE_WARN {
            Ok(())
        } else {
            Err(self.error())
        }
    }

    /// A failed read from the source keeps its own error; anything else is
    /// libarchive rejecting the archive (unknown format, damage, unsupported method).
    fn error(&mut self) -> CoreError {
        self.source.error.take().unwrap_or_else(|| {
            let message = unsafe { archive_error_string(self.raw.as_ptr()) };
            let detail = if message.is_null() {
                "unreadable archive".to_owned()
            } else {
                unsafe { CStr::from_ptr(message) }.to_string_lossy().into_owned()
            };
            CoreError::Unsupported { detail }
        })
    }
}

impl Drop for Archive {
    fn drop(&mut self) {
        unsafe { archive_read_free(self.raw.as_ptr()) };
    }
}

/// Reads after a seek start small (a zip lists by visiting every local header)
/// and double while reading runs on, since each read is a network round trip.
const FIRST_READ: usize = 32 * 1024;
const MAX_READ: usize = 1024 * 1024;

struct Source {
    reader: Box<dyn RandomReader>,
    position: u64,
    /// Handed to libarchive, which only needs it until the next read.
    block: Vec<u8>,
    next_len: usize,
    /// The failure behind the last `ARCHIVE_FATAL` returned to libarchive.
    error: Option<CoreError>,
}

unsafe extern "C" fn read(_: *mut RawArchive, data: *mut c_void, out: *mut *const c_void) -> isize {
    // SAFETY: `data` is the `Source` registered in `Archive::open`.
    let source = unsafe { &mut *data.cast::<Source>() };
    source.block.resize(source.next_len, 0);
    match source.reader.read_at(source.position, &mut source.block) {
        Ok(n) => {
            source.position += n as u64;
            source.next_len = (source.next_len * 2).min(MAX_READ);
            unsafe { *out = source.block.as_ptr().cast() };
            n as isize
        }
        Err(e) => {
            source.error = Some(e);
            ARCHIVE_FATAL as isize
        }
    }
}

unsafe extern "C" fn seek(_: *mut RawArchive, data: *mut c_void, offset: i64, whence: c_int) -> i64 {
    // SAFETY: as in `read`.
    let source = unsafe { &mut *data.cast::<Source>() };
    let base = match whence {
        libc::SEEK_SET => Some(0),
        libc::SEEK_CUR => Some(source.position),
        libc::SEEK_END => source.reader.size(),
        _ => None,
    };
    match base.and_then(|base| base.checked_add_signed(offset)) {
        Some(position) => {
            source.position = position;
            source.next_len = FIRST_READ;
            position as i64
        }
        None => ARCHIVE_FATAL as i64,
    }
}

/// libarchive converts the names it decodes (zip UTF-8 names, 7z and RAR
/// Unicode names) to the calling thread's locale charset. A UTF-8 locale makes
/// that UTF-8 everywhere instead of depending on the process locale; names
/// stored without a charset pass through as raw bytes either way.
struct Utf8Names(libc::locale_t);

/// The `locale_t`, kept as an address so the static is `Sync`; 0 when no UTF-8 locale exists.
static UTF8_LOCALE: LazyLock<usize> = LazyLock::new(|| {
    [c"C.UTF-8", c"en_US.UTF-8"]
        .into_iter()
        .map(|name| unsafe { libc::newlocale(libc::LC_CTYPE_MASK, name.as_ptr(), ptr::null_mut()) })
        .find(|locale| !locale.is_null())
        .map_or(0, |locale| locale as usize)
});

impl Utf8Names {
    fn enter() -> Option<Self> {
        let locale = *UTF8_LOCALE;
        (locale != 0).then(|| Self(unsafe { libc::uselocale(locale as libc::locale_t) }))
    }
}

impl Drop for Utf8Names {
    fn drop(&mut self) {
        unsafe { libc::uselocale(self.0) };
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicU64, Ordering};

    use super::*;

    /// An in-memory file that counts the bytes its reader fetches.
    struct Counted {
        data: Vec<u8>,
        fetched: Arc<AtomicU64>,
    }

    impl RandomReader for Counted {
        fn size(&self) -> Option<u64> {
            Some(self.data.len() as u64)
        }

        fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
            let start = (offset as usize).min(self.data.len());
            let n = buf.len().min(self.data.len() - start);
            buf[..n].copy_from_slice(&self.data[start..start + n]);
            self.fetched.fetch_add(n as u64, Ordering::Relaxed);
            Ok(n)
        }
    }

    /// A zip of `count` stored entries of `size` zero bytes. Listing never checks CRCs, so they stay 0.
    fn stored_zip(count: u16, size: u32) -> Vec<u8> {
        let mut zip = Vec::new();
        let mut central = Vec::new();
        for i in 0..count {
            let name = format!("{i:03}.jpg");
            // Version needed, flags, method (stored), time, date, CRC, sizes, name and extra lengths.
            let mut fields = Vec::new();
            for v in [20u16, 0, 0, 0, 0] {
                fields.extend(v.to_le_bytes());
            }
            for v in [0, size, size] {
                fields.extend(v.to_le_bytes());
            }
            fields.extend((name.len() as u16).to_le_bytes());
            fields.extend(0u16.to_le_bytes());

            let offset = zip.len() as u32;
            zip.extend(0x04034b50u32.to_le_bytes());
            zip.extend(&fields);
            zip.extend(name.as_bytes());
            zip.resize(zip.len() + size as usize, 0);

            central.extend(0x02014b50u32.to_le_bytes());
            central.extend(20u16.to_le_bytes());
            central.extend(&fields);
            // Comment length, disk, internal and external attributes.
            central.extend([0u8; 10]);
            central.extend(offset.to_le_bytes());
            central.extend(name.as_bytes());
        }
        let central_offset = zip.len() as u32;
        zip.extend(&central);
        zip.extend(0x06054b50u32.to_le_bytes());
        zip.extend([0u8; 4]);
        zip.extend(count.to_le_bytes());
        zip.extend(count.to_le_bytes());
        zip.extend((central.len() as u32).to_le_bytes());
        zip.extend(central_offset.to_le_bytes());
        zip.extend(0u16.to_le_bytes());
        zip
    }

    #[test]
    fn listing_a_zip_fetches_its_headers_not_its_pages() {
        let data = stored_zip(50, 512 * 1024);
        let size = data.len() as u64;
        let fetched = Arc::new(AtomicU64::new(0));
        let reader = Counted {
            data,
            fetched: Arc::clone(&fetched),
        };
        let mut archive = Archive::open(Box::new(reader)).unwrap();
        let mut names = Vec::new();
        while let Some(header) = archive.next_header().unwrap() {
            names.push(String::from_utf8(header.raw_path).unwrap());
        }
        assert_eq!(names.len(), 50);
        assert_eq!(names[49], "049.jpg");
        let fetched = fetched.load(Ordering::Relaxed);
        assert!(fetched < size / 8, "fetched {fetched} of {size} bytes");
    }
}
