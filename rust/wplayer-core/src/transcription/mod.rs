//! Speech to subtitles. A headless mpv decodes the audio track to 16 kHz mono
//! PCM in a file, faster than real time (`ao=pcm` is untimed); whisper.cpp
//! transcribes that file block by block while it grows, and each recognized
//! line goes to Kotlin as soon as its block is done. Going through mpv means
//! every source protocol and codec the player supports works here too.

mod audio;
mod recognizer;

use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::error::{CoreError, CoreResult};
use audio::{AudioDecoder, PcmReader, SAMPLE_RATE};
use recognizer::Recognizer;

/// Implemented in Kotlin; called on the transcribing thread.
#[uniffi::export(foreign)]
pub trait TranscriptionListener: Send + Sync {
    /// A recognized line; times are milliseconds from the start of the media.
    fn on_line(&self, start_ms: u64, end_ms: u64, text: String);
    /// Audio up to `position_seconds` of the media has been transcribed.
    fn on_progress(&self, position_seconds: f64);
    /// Polled while working; true stops the transcription with `Cancelled`.
    fn is_cancelled(&self) -> bool;
}

#[derive(uniffi::Record)]
pub struct TranscriptionRequest {
    /// What the player would open: `wplayer://…` or a local path.
    pub uri: String,
    /// A whisper.cpp (ggml) model file.
    pub model_path: String,
    /// whisper.cpp's Silero voice activity model; without it timestamps drift into silences.
    pub vad_model_path: Option<String>,
    /// The spoken language as an ISO 639-1 code; None detects it.
    pub language: Option<String>,
    /// mpv's audio track id; None picks the default track.
    pub audio_track: Option<i64>,
    /// Media time to start from, so the part being watched comes first.
    pub start_seconds: f64,
    /// Media time to stop at; None runs to the end.
    pub end_seconds: Option<f64>,
    pub threads: u32,
    /// An empty directory for the decoded audio; its files are removed at the end.
    pub work_dir: String,
}

/// The first block is short so the first lines show up quickly.
const FIRST_BLOCK_SECONDS: usize = 30;
/// Later blocks are longer: whisper keeps context within a block, and cuts between blocks may split a phrase.
const BLOCK_SECONDS: usize = 120;
/// How often `is_cancelled` is asked while whisper computes.
const CANCEL_POLL: Duration = Duration::from_millis(500);

/// Transcribes `request.uri` from `request.start_seconds` to the end, reporting
/// lines and progress to `listener`. Blocks for as long as that takes; call
/// from a background thread.
#[uniffi::export]
pub fn transcribe(request: TranscriptionRequest, listener: Arc<dyn TranscriptionListener>) -> CoreResult<()> {
    let mut recognizer = Recognizer::load(&request.model_path, request.vad_model_path.clone(), request.threads)?;
    let pcm_path = Path::new(&request.work_dir).join("audio.pcm");
    let result = run(&request, &pcm_path, &mut recognizer, &listener);
    let _ = std::fs::remove_file(&pcm_path);
    result
}

fn run(
    request: &TranscriptionRequest,
    pcm_path: &Path,
    recognizer: &mut Recognizer,
    listener: &Arc<dyn TranscriptionListener>,
) -> CoreResult<()> {
    let decoder = AudioDecoder::start(
        &request.uri,
        pcm_path,
        request.start_seconds,
        request.end_seconds,
        request.audio_track,
    )?;
    let mut reader = PcmReader::new(pcm_path);
    let cancelled = || listener.is_cancelled();
    let mut offset_seconds = request.start_seconds;
    let mut block_seconds = FIRST_BLOCK_SECONDS;
    loop {
        let block = reader.next_block(block_seconds * SAMPLE_RATE, &decoder, &cancelled)?;
        if block.is_empty() {
            break;
        }
        let listener_for_abort = Arc::clone(listener);
        let mut last_poll = Instant::now();
        let abort = move || {
            if last_poll.elapsed() < CANCEL_POLL {
                return false;
            }
            last_poll = Instant::now();
            listener_for_abort.is_cancelled()
        };
        let lines = recognizer.transcribe(&block, request.language.as_deref(), abort)?;
        if listener.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let offset_ms = (offset_seconds * 1000.0) as u64;
        for line in lines {
            listener.on_line(offset_ms + line.start_ms, offset_ms + line.end_ms, line.text);
        }
        offset_seconds += block.len() as f64 / SAMPLE_RATE as f64;
        listener.on_progress(offset_seconds);
        block_seconds = BLOCK_SECONDS;
    }
    decoder.finish()
}
