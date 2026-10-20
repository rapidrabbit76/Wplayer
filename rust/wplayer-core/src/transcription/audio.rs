//! Reads the PCM file the audio dump is still writing, in blocks for whisper.

use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::error::{CoreError, CoreResult};
pub(super) use crate::player::audio::{AudioDump as AudioDecoder, PCM_SAMPLE_RATE as SAMPLE_RATE};

/// How long to wait for more audio before looking again.
const POLL: Duration = Duration::from_millis(200);
/// A block ends at the quietest moment of its last seconds, not mid-word.
const CUT_SEARCH_SECONDS: usize = 3;
/// Loudness is compared over windows this long.
const CUT_WINDOW: usize = SAMPLE_RATE / 20;

pub(super) struct PcmReader {
    path: PathBuf,
    file: Option<File>,
    /// Samples read but not yet handed out: the tail after the last cut.
    pending: Vec<f32>,
    /// An odd byte left over from the last read.
    partial: Option<u8>,
}

impl PcmReader {
    pub(super) fn new(path: &Path) -> Self {
        Self {
            path: path.to_owned(),
            file: None,
            pending: Vec::new(),
            partial: None,
        }
    }

    /// Up to `len` samples, cut at a quiet moment unless the audio ended.
    /// Waits for the decoder to write them; empty once everything was read.
    pub(super) fn next_block(
        &mut self,
        len: usize,
        decoder: &AudioDecoder,
        cancelled: &dyn Fn() -> bool,
    ) -> CoreResult<Vec<f32>> {
        loop {
            // Read whatever is there before checking `is_done`, so nothing written before the end is missed.
            let done = decoder.is_done();
            self.read_available()?;
            if self.pending.len() >= len || done {
                break;
            }
            if cancelled() {
                return Err(CoreError::Cancelled);
            }
            std::thread::sleep(POLL);
        }
        let take = if self.pending.len() > len {
            quiet_cut(&self.pending[..len])
        } else {
            self.pending.len()
        };
        Ok(self.pending.drain(..take).collect())
    }

    fn read_available(&mut self) -> CoreResult<()> {
        if self.file.is_none() {
            match File::open(&self.path) {
                Ok(file) => self.file = Some(file),
                // mpv creates the file once the audio starts.
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
                Err(e) => return Err(e.into()),
            }
        }
        let mut bytes = Vec::new();
        if let Some(byte) = self.partial.take() {
            bytes.push(byte);
        }
        self.file.as_mut().expect("opened above").read_to_end(&mut bytes)?;
        if bytes.len() % 2 == 1 {
            self.partial = bytes.pop();
        }
        self.pending.extend(
            bytes
                .as_chunks::<2>()
                .0
                .iter()
                .map(|&s| f32::from(i16::from_le_bytes(s)) / 32768.0),
        );
        Ok(())
    }
}

/// Where to end a block of `samples`: the middle of the quietest window in its
/// last seconds, so a cut rarely splits a word.
pub(super) fn quiet_cut(samples: &[f32]) -> usize {
    let search = (CUT_SEARCH_SECONDS * SAMPLE_RATE).min(samples.len());
    let from = samples.len() - search;
    (from..samples.len().saturating_sub(CUT_WINDOW))
        .step_by(CUT_WINDOW / 2)
        .map(|start| (start, energy(&samples[start..start + CUT_WINDOW])))
        .min_by(|a, b| a.1.total_cmp(&b.1))
        .map_or(samples.len(), |(start, _)| start + CUT_WINDOW / 2)
}

fn energy(samples: &[f32]) -> f32 {
    samples.iter().map(|s| s * s).sum::<f32>() / samples.len().max(1) as f32
}

/// Root-mean-square loudness, to skip silent blocks.
pub(super) fn rms(samples: &[f32]) -> f32 {
    energy(samples).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cuts_in_the_quiet_part_near_the_end() {
        // Loud, then a short silence 1 s before the end, then loud again.
        let mut samples = vec![0.5f32; SAMPLE_RATE * 10];
        let silence = SAMPLE_RATE * 9..SAMPLE_RATE * 9 + SAMPLE_RATE / 5;
        samples[silence.clone()].fill(0.0);
        let cut = quiet_cut(&samples);
        assert!(silence.contains(&cut), "cut at {cut}, silence {silence:?}");
    }

    #[test]
    fn converts_little_endian_samples_across_reads() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("a.pcm");
        let mut reader = PcmReader::new(&path);
        reader.read_available().unwrap();
        assert!(reader.pending.is_empty());

        // 0x4000 = 0.5, split so the second sample straddles two reads.
        std::fs::write(&path, [0x00, 0x40, 0x00]).unwrap();
        reader.read_available().unwrap();
        assert_eq!(reader.pending, vec![0.5]);
        std::fs::write(&path, [0x00, 0x40, 0x00, 0xC0]).unwrap();
        reader.read_available().unwrap();
        assert_eq!(reader.pending, vec![0.5, -0.5]);
    }
}
