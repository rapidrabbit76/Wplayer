use serde::Deserialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TrackKind {
    Video,
    Audio,
    Subtitle,
}

impl TrackKind {
    /// The mpv property that selects a track of this kind.
    pub(super) fn property(self) -> &'static str {
        match self {
            Self::Video => "vid",
            Self::Audio => "aid",
            Self::Subtitle => "sid",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Track {
    pub id: i64,
    pub kind: TrackKind,
    pub title: Option<String>,
    pub language: Option<String>,
    pub codec: Option<String>,
    pub selected: bool,
    pub external: bool,
    pub is_default: bool,
}

/// One entry of mpv's `track-list` property (JSON when read as a string).
#[derive(Deserialize)]
struct RawTrack {
    id: i64,
    #[serde(rename = "type")]
    kind: String,
    title: Option<String>,
    lang: Option<String>,
    codec: Option<String>,
    #[serde(default)]
    selected: bool,
    #[serde(default)]
    external: bool,
    #[serde(default)]
    default: bool,
    #[serde(rename = "external-filename")]
    external_filename: Option<String>,
}

/// The id of the external track loaded from `filename`, if there is one.
pub(super) fn external_track_id(json: &str, filename: &str) -> Option<i64> {
    let raw: Vec<RawTrack> = serde_json::from_str(json).ok()?;
    raw.into_iter()
        .find(|t| t.external_filename.as_deref() == Some(filename))
        .map(|t| t.id)
}

pub(super) fn parse(json: &str) -> Vec<Track> {
    let raw: Vec<RawTrack> = match serde_json::from_str(json) {
        Ok(raw) => raw,
        Err(e) => {
            log::warn!("cannot parse track-list: {e}");
            return Vec::new();
        }
    };
    raw.into_iter()
        .filter_map(|t| {
            let kind = match t.kind.as_str() {
                "video" => TrackKind::Video,
                "audio" => TrackKind::Audio,
                "sub" => TrackKind::Subtitle,
                _ => return None,
            };
            Some(Track {
                id: t.id,
                kind,
                title: t.title,
                language: t.lang,
                codec: t.codec,
                selected: t.selected,
                external: t.external,
                is_default: t.default,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn finds_an_external_track_by_its_file() {
        let json = r#"[{"id":1,"type":"sub","external":true,"external-filename":"/a/ai.srt"},{"id":2,"type":"sub"}]"#;
        assert_eq!(external_track_id(json, "/a/ai.srt"), Some(1));
        assert_eq!(external_track_id(json, "/b.srt"), None);
    }

    use super::*;

    #[test]
    fn parses_mpv_track_list() {
        let json = r#"[
            {"id":1,"type":"video","codec":"av1","selected":true,"default":true,"external":false},
            {"id":1,"type":"audio","lang":"kor","codec":"opus","selected":true},
            {"id":2,"type":"sub","title":"한국어","codec":"subrip","external":true}
        ]"#;
        let tracks = parse(json);
        assert_eq!(tracks.len(), 3);
        assert_eq!(tracks[0].codec.as_deref(), Some("av1"));
        assert_eq!(tracks[1].language.as_deref(), Some("kor"));
        assert_eq!(tracks[2].kind, TrackKind::Subtitle);
        assert!(tracks[2].external && !tracks[2].selected);
    }

    #[test]
    fn invalid_json_yields_no_tracks() {
        assert!(parse("not json").is_empty());
    }
}
