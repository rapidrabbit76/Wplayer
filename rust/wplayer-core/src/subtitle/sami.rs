//! SAMI (`.smi`) to SubRip, before libmpv sees the file. FFmpeg's SAMI
//! demuxer shows every language class of a file at once (Korean and English
//! lines together), and MPVKit's FFmpeg on iOS has no SAMI demuxer at all.
//!
//! SAMI is loose HTML: `<SYNC Start=ms>` blocks hold `<P Class=..>`
//! paragraphs, one class per language. A block without text ends the line
//! shown before it.

/// How long the last line stays when nothing ends it.
const LAST_LINE_MS: u64 = 4000;

/// The SubRip text for a SAMI file, with Korean lines when the file has
/// several languages; `None` when `sami` has no `<SYNC>` blocks.
pub(crate) fn to_srt(sami: &str) -> Option<String> {
    // ASCII lowercasing keeps byte offsets, so positions found in `lower` index `sami`.
    let lower = sami.to_ascii_lowercase();
    let class = preferred_class(&lower);
    let mut syncs: Vec<usize> = lower.match_indices("<sync").map(|(i, _)| i).collect();
    if syncs.is_empty() {
        return None;
    }
    let body_end = lower.rfind("</body").unwrap_or(lower.len());
    syncs.push(body_end.max(*syncs.last().unwrap()));

    let mut lines: Vec<(u64, String)> = Vec::new();
    for pair in syncs.windows(2) {
        let (tag_start, block_end) = (pair[0], pair[1]);
        let Some(tag_len) = lower[tag_start..block_end].find('>') else {
            continue;
        };
        let Some(start) =
            attribute(&lower[tag_start..tag_start + tag_len], "start").and_then(|v| v.parse::<u64>().ok())
        else {
            continue;
        };
        let content = tag_start + tag_len + 1;
        if let Some(text) = paragraph(sami, &lower, content, block_end, class.as_deref()) {
            lines.push((start, text));
        }
    }
    lines.sort_by_key(|(start, _)| *start);

    let mut srt = String::new();
    let mut index = 0;
    for (i, (start, text)) in lines.iter().enumerate() {
        if text.is_empty() {
            continue;
        }
        let end = lines.get(i + 1).map_or(start + LAST_LINE_MS, |(next, _)| *next);
        if end <= *start {
            continue;
        }
        index += 1;
        srt.push_str(&format!(
            "{index}\n{} --> {}\n{text}\n\n",
            timestamp(*start),
            timestamp(end)
        ));
    }
    Some(srt)
}

/// The class of the Korean paragraphs, from the `<STYLE>` block's `lang:` entries.
fn preferred_class(lower: &str) -> Option<String> {
    let style_start = lower.find("<style")?;
    let style_end = lower[style_start..]
        .find("</style")
        .map_or(lower.len(), |i| style_start + i);
    lower[style_start..style_end].split('}').find_map(|rule| {
        let (selector, body) = rule.split_once('{')?;
        let name = selector.trim().rsplit('.').next()?.trim();
        let lang = body
            .split(';')
            .find_map(|decl| decl.split_once(':').filter(|(k, _)| k.trim() == "lang"))?
            .1;
        (lang.trim().starts_with("ko") && !name.is_empty()).then(|| name.to_owned())
    })
}

/// The text of the block's paragraph in `class` (or its first paragraph), as SubRip.
/// `None` when the block has paragraphs, but none in `class`.
fn paragraph(sami: &str, lower: &str, start: usize, end: usize, class: Option<&str>) -> Option<String> {
    let paragraphs: Vec<usize> = lower[start..end].match_indices("<p").map(|(i, _)| start + i).collect();
    if paragraphs.is_empty() {
        return Some(clean(&sami[start..end]));
    }
    for (n, &p) in paragraphs.iter().enumerate() {
        let tag_end = lower[p..end].find('>').map_or(end, |i| p + i);
        let p_class = attribute(&lower[p..tag_end], "class");
        if class.is_none() || p_class.as_deref() == class {
            let text_start = (tag_end + 1).min(end);
            let text_end = paragraphs.get(n + 1).copied().unwrap_or(end);
            return Some(clean(&sami[text_start..text_end]));
        }
    }
    None
}

/// An attribute's value in a lowercased tag, without quotes: `start=1000`, `class="krcc"`.
fn attribute(tag: &str, name: &str) -> Option<String> {
    let mut rest = tag;
    while let Some(i) = rest.find(name) {
        let after = rest[i + name.len()..].trim_start();
        let preceded_by_space = rest[..i].ends_with(|c: char| c.is_whitespace());
        if preceded_by_space && let Some(value) = after.strip_prefix('=') {
            let value = value.trim_start().trim_start_matches(['"', '\'']);
            let value: String = value
                .chars()
                .take_while(|c| !c.is_whitespace() && !matches!(c, '"' | '\'' | '>'))
                .collect();
            return Some(value);
        }
        rest = &rest[i + name.len()..];
    }
    None
}

/// SAMI markup to SubRip text: `<br>` breaks lines, font colors and b/i/u stay,
/// other tags and source line breaks go, entities are decoded. Empty when nothing shows.
fn clean(html: &str) -> String {
    let mut out = String::new();
    let mut rest = html;
    while let Some(open) = rest.find('<') {
        out.push_str(&decode_entities(&rest[..open]));
        let Some(close) = rest[open..].find('>') else {
            rest = &rest[open..];
            break;
        };
        let tag = &rest[open + 1..open + close];
        out.push_str(&convert_tag(tag));
        rest = &rest[open + close + 1..];
    }
    out.push_str(&decode_entities(rest));

    let lines: Vec<String> = out
        .split('\n')
        .map(|line| line.split_whitespace().collect::<Vec<_>>().join(" "))
        .filter(|line| !strip_tags(line).trim().is_empty())
        .collect();
    lines.join("\n")
}

fn convert_tag(tag: &str) -> String {
    let lower = tag.trim().to_ascii_lowercase();
    let name = lower
        .trim_start_matches('/')
        .split(|c: char| c.is_whitespace() || c == '/')
        .next()
        .unwrap_or("");
    let closing = lower.starts_with('/');
    match name {
        "br" => "\n".to_owned(),
        "b" | "i" | "u" => format!("<{}{name}>", if closing { "/" } else { "" }),
        "font" if closing => "</font>".to_owned(),
        "font" => {
            attribute(&format!(" {lower}"), "color").map_or(String::new(), |color| format!("<font color=\"{color}\">"))
        }
        _ => String::new(),
    }
}

fn strip_tags(text: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for c in text.chars() {
        match c {
            '<' => in_tag = true,
            '>' => in_tag = false,
            c if !in_tag => out.push(c),
            _ => {}
        }
    }
    out
}

/// Source line breaks are spaces in HTML; entities become characters.
fn decode_entities(text: &str) -> String {
    let text = text.replace(['\r', '\n', '\t'], " ");
    let mut out = String::new();
    let mut rest = text.as_str();
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        let entity_end = rest[amp..].find(';').filter(|&i| i <= 10);
        let decoded = entity_end.and_then(|end| {
            let entity = &rest[amp + 1..amp + end];
            let c = match entity.to_ascii_lowercase().as_str() {
                "nbsp" => ' ',
                "lt" => '<',
                "gt" => '>',
                "amp" => '&',
                "quot" => '"',
                "apos" => '\'',
                e => {
                    let code = e.strip_prefix("#x").map_or_else(
                        || e.strip_prefix('#')?.parse().ok(),
                        |h| u32::from_str_radix(h, 16).ok(),
                    )?;
                    char::from_u32(code)?
                }
            };
            Some((c, end))
        });
        match decoded {
            Some((c, end)) => {
                out.push(c);
                rest = &rest[amp + end + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[amp + 1..];
            }
        }
    }
    out.push_str(rest);
    out
}

fn timestamp(ms: u64) -> String {
    format!(
        "{:02}:{:02}:{:02},{:03}",
        ms / 3_600_000,
        ms / 60_000 % 60,
        ms / 1000 % 60,
        ms % 1000
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_lines_and_ends_them_at_blank_syncs() {
        let sami = "<SAMI><BODY>\n<SYNC Start=1000><P Class=KRCC>첫 줄<br>둘째 줄\n</P>\n<SYNC Start=2500><P Class=KRCC>&nbsp;\n<SYNC Start=3000><P Class=KRCC>다음</P></BODY></SAMI>";
        assert_eq!(
            to_srt(sami).unwrap(),
            "1\n00:00:01,000 --> 00:00:02,500\n첫 줄\n둘째 줄\n\n2\n00:00:03,000 --> 00:00:07,000\n다음\n\n"
        );
    }

    #[test]
    fn prefers_the_korean_class() {
        let sami = "<SAMI><HEAD><STYLE><!--\n.ENCC { Name: English; lang: en-US; }\n.KRCC { Name: Korean; lang: ko-KR; }\n--></STYLE></HEAD><BODY>\
            <SYNC Start=0><P Class=ENCC>Hello<P Class=KRCC>안녕\
            <SYNC Start=1000><P Class=ENCC>&nbsp;<P Class=KRCC>&nbsp;</BODY></SAMI>";
        assert_eq!(to_srt(sami).unwrap(), "1\n00:00:00,000 --> 00:00:01,000\n안녕\n\n");
    }

    #[test]
    fn keeps_font_colors_and_decodes_entities() {
        let sami = "<SYNC Start=\"61000\"><P><font color=#FFFF00>A &amp; B</font> &lt;3 &#54620;</P>";
        assert_eq!(
            to_srt(sami).unwrap(),
            "1\n00:01:01,000 --> 00:01:05,000\n<font color=\"#ffff00\">A & B</font> <3 한\n\n"
        );
    }

    #[test]
    fn rejects_files_without_syncs() {
        assert!(to_srt("1\n00:00:01,000 --> 00:00:02,000\nhi\n").is_none());
    }
}
