//! Converts text subtitles to UTF-8 before libmpv sees them.
//!
//! Korean SMI/SRT files are frequently CP949 (EUC-KR); libmpv is built
//! without uchardet, so detection happens here with `chardetng`.

pub(crate) mod sami;

use chardetng::{EncodingDetector, Iso2022JpDetection, Utf8Detection};
use encoding_rs::Encoding;

/// Returns `bytes` as UTF-8 without a BOM, decoding legacy encodings as needed.
pub(crate) fn to_utf8(bytes: &[u8]) -> Vec<u8> {
    if let Some((encoding, bom_len)) = Encoding::for_bom(bytes) {
        let (text, _) = encoding.decode_without_bom_handling(&bytes[bom_len..]);
        return text.into_owned().into_bytes();
    }
    if std::str::from_utf8(bytes).is_ok() {
        return bytes.to_vec();
    }
    let mut detector = EncodingDetector::new(Iso2022JpDetection::Deny);
    detector.feed(bytes, true);
    // A `.kr` hint biases ties between CJK legacy encodings toward EUC-KR.
    let encoding = detector.guess(Some(b"kr"), Utf8Detection::Deny);
    let (text, _) = encoding.decode_without_bom_handling(bytes);
    text.into_owned().into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMI: &str =
        "<SAMI><BODY><SYNC Start=1000><P Class=KRCC>안녕하세요, 자막 테스트입니다.</P></SYNC></BODY></SAMI>";

    #[test]
    fn decodes_euc_kr() {
        let (encoded, _, _) = encoding_rs::EUC_KR.encode(SAMI);
        assert_eq!(String::from_utf8(to_utf8(&encoded)).unwrap(), SAMI);
    }

    #[test]
    fn strips_utf8_bom_and_decodes_utf16() {
        let mut with_bom = vec![0xEF, 0xBB, 0xBF];
        with_bom.extend_from_slice(SAMI.as_bytes());
        assert_eq!(to_utf8(&with_bom), SAMI.as_bytes());

        let mut utf16 = vec![0xFF, 0xFE];
        utf16.extend(SAMI.encode_utf16().flat_map(u16::to_le_bytes));
        assert_eq!(to_utf8(&utf16), SAMI.as_bytes());
    }

    #[test]
    fn keeps_valid_utf8_untouched() {
        assert_eq!(to_utf8(SAMI.as_bytes()), SAMI.as_bytes());
    }
}
