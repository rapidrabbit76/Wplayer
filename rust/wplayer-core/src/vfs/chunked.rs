use super::RandomReader;
use crate::error::CoreResult;

/// Serves libmpv's small reads from large chunks so a network reader pays one
/// round trip per [`CHUNK_SIZE`] instead of per read.
///
/// Several chunks are kept because demuxers do not always read in one place:
/// a file whose audio and video are stored far apart is read alternately at
/// two offsets, and with a single chunk every switch fetched it again (megabytes
/// per few kilobytes read, enough to starve playback on a fast network).
pub struct ChunkedReader<R> {
    inner: R,
    /// Most recently used last; each starts at a multiple of [`CHUNK_SIZE`].
    chunks: Vec<Chunk>,
}

struct Chunk {
    offset: u64,
    data: Vec<u8>,
}

const CHUNK_SIZE: usize = 2 * 1024 * 1024;

/// Chunks kept per reader: room for a few read positions plus the one moving ahead.
const MAX_CHUNKS: usize = 8;

impl<R: RandomReader> ChunkedReader<R> {
    pub fn new(inner: R) -> Self {
        Self {
            inner,
            chunks: Vec::with_capacity(MAX_CHUNKS),
        }
    }

    /// Reads the chunk starting at `offset`, tolerating short reads from `inner`.
    fn fetch(&mut self, offset: u64) -> CoreResult<Chunk> {
        // The least recently used chunk's buffer is reused once the cache is full.
        let mut data = if self.chunks.len() == MAX_CHUNKS {
            self.chunks.remove(0).data
        } else {
            Vec::new()
        };
        data.resize(CHUNK_SIZE, 0);
        let mut filled = 0;
        while filled < CHUNK_SIZE {
            let n = self.inner.read_at(offset + filled as u64, &mut data[filled..])?;
            if n == 0 {
                break;
            }
            filled += n;
        }
        data.truncate(filled);
        Ok(Chunk { offset, data })
    }
}

impl<R: RandomReader> RandomReader for ChunkedReader<R> {
    fn size(&self) -> Option<u64> {
        self.inner.size()
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
        let chunk_offset = offset - offset % CHUNK_SIZE as u64;
        let chunk = match self.chunks.iter().position(|c| c.offset == chunk_offset) {
            Some(index) => self.chunks.remove(index),
            None => self.fetch(chunk_offset)?,
        };
        let start = ((offset - chunk_offset) as usize).min(chunk.data.len());
        let n = (chunk.data.len() - start).min(buf.len());
        buf[..n].copy_from_slice(&chunk.data[start..start + n]);
        self.chunks.push(chunk);
        Ok(n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Returns at most 1000 bytes per call, like a server with a small max read size.
    struct Patterned {
        len: u64,
        calls: usize,
    }

    impl RandomReader for Patterned {
        fn size(&self) -> Option<u64> {
            Some(self.len)
        }

        fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> CoreResult<usize> {
            self.calls += 1;
            let n = (self.len.saturating_sub(offset) as usize).min(buf.len()).min(1000);
            for (i, b) in buf[..n].iter_mut().enumerate() {
                *b = ((offset + i as u64) % 251) as u8;
            }
            Ok(n)
        }
    }

    #[test]
    fn reads_match_source_across_chunk_boundaries_and_seeks() {
        let len = (CHUNK_SIZE * 2 + 123) as u64;
        let mut reader = ChunkedReader::new(Patterned { len, calls: 0 });
        let mut buf = vec![0u8; 4096];
        for offset in [0u64, 4096, CHUNK_SIZE as u64 - 10, 7, len - 50] {
            let n = reader.read_at(offset, &mut buf).unwrap();
            assert!(n > 0);
            for (i, b) in buf[..n].iter().enumerate() {
                assert_eq!(*b, ((offset + i as u64) % 251) as u8, "offset {offset} index {i}");
            }
        }
        assert_eq!(reader.read_at(len, &mut buf).unwrap(), 0);
    }

    #[test]
    fn sequential_reads_reuse_the_chunk() {
        let mut reader = ChunkedReader::new(Patterned {
            len: CHUNK_SIZE as u64,
            calls: 0,
        });
        let mut buf = [0u8; 1024];
        let mut offset = 0;
        while offset < 100 * 1024 {
            offset += reader.read_at(offset, &mut buf).unwrap() as u64;
        }
        let calls_for_one_chunk = CHUNK_SIZE.div_ceil(1000);
        assert_eq!(reader.inner.calls, calls_for_one_chunk);
    }

    #[test]
    fn alternating_between_two_places_fetches_each_once() {
        // Audio and video stored far apart: the demuxer reads a little here, a little there.
        let far = (CHUNK_SIZE * 5) as u64;
        let mut reader = ChunkedReader::new(Patterned { len: far * 2, calls: 0 });
        let mut buf = [0u8; 4096];
        for step in 0..100u64 {
            for base in [0, far] {
                let offset = base + step * 4096;
                let n = reader.read_at(offset, &mut buf).unwrap();
                assert_eq!(buf[0], (offset % 251) as u8);
                assert_eq!(n, 4096);
            }
        }
        assert_eq!(reader.inner.calls, 2 * CHUNK_SIZE.div_ceil(1000));
    }

    #[test]
    fn keeps_at_most_max_chunks() {
        let len = (CHUNK_SIZE * (MAX_CHUNKS + 2)) as u64;
        let mut reader = ChunkedReader::new(Patterned { len, calls: 0 });
        let mut buf = [0u8; 16];
        for chunk in 0..MAX_CHUNKS + 2 {
            reader.read_at((chunk * CHUNK_SIZE) as u64, &mut buf).unwrap();
        }
        assert_eq!(reader.chunks.len(), MAX_CHUNKS);
        // The oldest went first: chunk 0 is fetched again, the newest is still there.
        let calls = reader.inner.calls;
        reader
            .read_at(((MAX_CHUNKS + 1) * CHUNK_SIZE) as u64, &mut buf)
            .unwrap();
        assert_eq!(reader.inner.calls, calls);
        reader.read_at(0, &mut buf).unwrap();
        assert!(reader.inner.calls > calls);
    }
}
