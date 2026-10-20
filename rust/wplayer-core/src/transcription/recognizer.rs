//! whisper.cpp: turns a block of 16 kHz mono samples into timed lines of text.

use whisper_rs::{
    FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters, WhisperState, WhisperVadContext,
    WhisperVadContextParams, WhisperVadParams,
};

use super::audio::{SAMPLE_RATE, rms};
use crate::error::{CoreError, CoreResult};

pub(super) struct Line {
    /// Milliseconds from the start of the block.
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
}

/// Blocks quieter than this are not worth running whisper on (and it tends to invent text for silence).
const SILENCE_RMS: f32 = 0.002;

const SPEECH_PAD_MS: i32 = 200;

/// Lines whisper produces from silence or music, learned from video captions
/// ("thanks for watching"); dropped when a line is nothing but one of these.
const HALLUCINATIONS: &[&str] = &[
    "시청해주셔서 감사합니다",
    "시청해 주셔서 감사합니다",
    "구독과 좋아요 부탁드립니다",
    "구독과 좋아요",
    "MBC 뉴스",
    "ご視聴ありがとうございました",
    "ご視聴ありがとうございます",
    "チャンネル登録よろしくお願いします",
    "Thank you for watching",
    "Thanks for watching",
    "請不吝點贊 訂閱 轉發 打賞支持明鏡與點點欄目",
    "字幕由Amara.org社群提供",
];

pub(super) struct Recognizer {
    // Declared before the context it was created from, so it is dropped first.
    state: WhisperState,
    _context: WhisperContext,
    threads: i32,
    /// Silero voice activity detection: whisper then only hears speech, which
    /// keeps timestamps on the words instead of the silence before them.
    /// (whisper.cpp applies its own VAD only in `whisper_full`, not with a state.)
    vad: Option<WhisperVadContext>,
}

impl Recognizer {
    pub(super) fn load(model_path: &str, vad_model_path: Option<String>, threads: u32) -> CoreResult<Self> {
        let context =
            WhisperContext::new_with_params(model_path, WhisperContextParameters::default()).map_err(|e| {
                CoreError::InvalidArgument {
                    detail: format!("whisper model {model_path}: {e}"),
                }
            })?;
        let state = context.create_state().map_err(whisper_error)?;
        let threads = threads.clamp(1, 16) as i32;
        let vad = vad_model_path
            .map(|path| {
                let mut params = WhisperVadContextParams::new();
                params.set_n_threads(threads);
                WhisperVadContext::new(&path, params).map_err(|e| CoreError::InvalidArgument {
                    detail: format!("VAD model {path}: {e}"),
                })
            })
            .transpose()?;
        Ok(Self {
            state,
            _context: context,
            threads,
            vad,
        })
    }

    /// Recognizes speech in `samples`; `abort` is polled during the work and true stops it.
    pub(super) fn transcribe(
        &mut self,
        samples: &[f32],
        language: Option<&str>,
        abort: impl FnMut() -> bool + 'static,
    ) -> CoreResult<Vec<Line>> {
        if rms(samples) < SILENCE_RMS {
            return Ok(Vec::new());
        }
        let (speech, timeline) = match &mut self.vad {
            Some(vad) => {
                let mut params = WhisperVadParams::new();
                // Keep a little audio around each speech part so first and last syllables are not clipped.
                params.set_speech_pad(SPEECH_PAD_MS);
                let parts = vad.segments_from_samples(params, samples).map_err(whisper_error)?;
                let ranges = parts.map(|part| centiseconds_to_sample(part.start)..centiseconds_to_sample(part.end));
                SpeechTimeline::build(samples, ranges)
            }
            None => (samples.to_vec(), SpeechTimeline::identity()),
        };
        if speech.is_empty() {
            return Ok(Vec::new());
        }
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_n_threads(self.threads);
        params.set_language(Some(language.unwrap_or("auto")));
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_suppress_blank(true);
        params.set_abort_callback_safe(abort);
        self.state.full(params, &speech).map_err(whisper_error)?;

        let mut lines: Vec<Line> = Vec::new();
        for segment in self.state.as_iter() {
            let text = segment.to_str_lossy().map_err(whisper_error)?.trim().to_owned();
            if text.is_empty() || is_hallucination(&text) || lines.last().is_some_and(|last| last.text == text) {
                continue;
            }
            // whisper reports centiseconds, in the speech-only audio.
            let start_ms = timeline.original_ms((segment.start_timestamp().max(0) * 10) as u64);
            let end_ms = timeline.original_ms((segment.end_timestamp().max(0) * 10) as u64);
            lines.push(Line {
                start_ms,
                end_ms: end_ms.max(start_ms + 500),
                text,
            });
        }
        Ok(lines)
    }
}

fn centiseconds_to_sample(cs: f32) -> usize {
    (cs.max(0.0) as f64 * SAMPLE_RATE as f64 / 100.0) as usize
}

/// Where each piece of the speech-only audio came from in the block, to map
/// whisper's times back.
pub(super) struct SpeechTimeline {
    /// (start in the speech audio, start in the block, length), in samples, in order.
    pieces: Vec<(usize, usize, usize)>,
}

impl SpeechTimeline {
    fn identity() -> Self {
        Self {
            pieces: vec![(0, 0, usize::MAX / 2)],
        }
    }

    /// The samples of `ranges` joined, and how to map times in them back to `samples`.
    pub(super) fn build(samples: &[f32], ranges: impl Iterator<Item = std::ops::Range<usize>>) -> (Vec<f32>, Self) {
        let mut speech = Vec::new();
        let mut pieces = Vec::new();
        for range in ranges {
            let range = range.start.min(samples.len())..range.end.min(samples.len());
            if range.is_empty() {
                continue;
            }
            pieces.push((speech.len(), range.start, range.len()));
            speech.extend_from_slice(&samples[range]);
        }
        (speech, Self { pieces })
    }

    /// Milliseconds in the block for `ms` in the speech-only audio.
    pub(super) fn original_ms(&self, ms: u64) -> u64 {
        let sample = (ms as usize).saturating_mul(SAMPLE_RATE) / 1000;
        let piece = self
            .pieces
            .iter()
            .rev()
            .find(|(start, _, _)| *start <= sample)
            .or(self.pieces.first());
        let Some(&(start, original, len)) = piece else {
            return ms;
        };
        let within = (sample - start.min(sample)).min(len);
        ((original + within) * 1000 / SAMPLE_RATE) as u64
    }
}

fn is_hallucination(text: &str) -> bool {
    let core = text.trim_matches(|c: char| c.is_whitespace() || c.is_ascii_punctuation() || "。、！？…".contains(c));
    HALLUCINATIONS.iter().any(|phrase| core.eq_ignore_ascii_case(phrase))
}

fn whisper_error(e: whisper_rs::WhisperError) -> CoreError {
    CoreError::Player {
        detail: format!("whisper: {e}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_speech_times_back_to_the_block() {
        let samples = vec![0.0f32; SAMPLE_RATE * 20];
        // Speech at 3-5 s and 10-12 s of the block.
        let ranges = [SAMPLE_RATE * 3..SAMPLE_RATE * 5, SAMPLE_RATE * 10..SAMPLE_RATE * 12];
        let (speech, timeline) = SpeechTimeline::build(&samples, ranges.into_iter());
        assert_eq!(speech.len(), SAMPLE_RATE * 4);
        assert_eq!(timeline.original_ms(0), 3_000);
        assert_eq!(timeline.original_ms(1_500), 4_500);
        assert_eq!(timeline.original_ms(2_000), 10_000);
        assert_eq!(timeline.original_ms(3_000), 11_000);
        // Past the end stays at the end of the last piece.
        assert_eq!(timeline.original_ms(9_000), 12_000);
        assert_eq!(SpeechTimeline::identity().original_ms(1_234), 1_234);
    }

    #[test]
    fn drops_caption_boilerplate_but_keeps_real_lines() {
        assert!(is_hallucination("시청해주셔서 감사합니다."));
        assert!(is_hallucination(" Thank you for watching! "));
        assert!(is_hallucination("ご視聴ありがとうございました。"));
        assert!(!is_hallucination("시청해주셔서 감사합니다. 다음 주에 또 만나요"));
        assert!(!is_hallucination("감사합니다"));
    }

    /// Needs a ggml model, the Silero VAD model and 16 kHz mono s16le speech that starts after 3 s of
    /// silence and mentions 날씨 (made with `say -v Yuna` and ffmpeg).
    #[test]
    #[ignore = "needs WHISPER_TEST_MODEL, WHISPER_TEST_VAD and WHISPER_TEST_PCM"]
    fn transcribes_korean_speech_with_timestamps() {
        let model = std::env::var("WHISPER_TEST_MODEL").expect("WHISPER_TEST_MODEL");
        let bytes = std::fs::read(std::env::var("WHISPER_TEST_PCM").expect("WHISPER_TEST_PCM")).unwrap();
        let samples: Vec<f32> = bytes
            .as_chunks::<2>()
            .0
            .iter()
            .map(|&s| f32::from(i16::from_le_bytes(s)) / 32768.0)
            .collect();
        let vad = std::env::var("WHISPER_TEST_VAD").ok();
        let mut recognizer = Recognizer::load(&model, vad, 4).unwrap();
        let lines = recognizer.transcribe(&samples, Some("ko"), || false).unwrap();
        let text: String = lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join(" ");
        println!("{} ms: {text}", lines[0].start_ms);
        assert!(text.contains("날씨"), "{text}");
        assert!(lines[0].start_ms >= 2_500, "starts at {} ms", lines[0].start_ms);
    }
}
