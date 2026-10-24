//! The page order comic readers use, matching the app's `NaturalOrderComparator`:
//! case-insensitive, with digit runs compared by numeric value ("2" < "10").

use std::cmp::Ordering;

pub(super) fn compare(a: &str, b: &str) -> Ordering {
    let (mut a, mut b) = (a, b);
    loop {
        let (Some(ca), Some(cb)) = (a.chars().next(), b.chars().next()) else {
            // A name that is a prefix of the other sorts first.
            return a.len().cmp(&b.len());
        };
        if ca.is_ascii_digit() && cb.is_ascii_digit() {
            let (digits_a, rest_a) = split_digits(a);
            let (digits_b, rest_b) = split_digits(b);
            let (value_a, value_b) = (digits_a.trim_start_matches('0'), digits_b.trim_start_matches('0'));
            let by_value = value_a.len().cmp(&value_b.len()).then_with(|| value_a.cmp(value_b));
            if by_value.is_ne() {
                return by_value;
            }
            (a, b) = (rest_a, rest_b);
        } else {
            let by_char = ca.to_lowercase().cmp(cb.to_lowercase());
            if by_char.is_ne() {
                return by_char;
            }
            (a, b) = (&a[ca.len_utf8()..], &b[cb.len_utf8()..]);
        }
    }
}

fn split_digits(s: &str) -> (&str, &str) {
    s.split_at(s.find(|c: char| !c.is_ascii_digit()).unwrap_or(s.len()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sorted(names: &[&'static str]) -> Vec<&'static str> {
        let mut names = names.to_vec();
        names.sort_by(|a, b| compare(a, b));
        names
    }

    #[test]
    fn numbers_sort_by_value_and_leading_zeros_tie() {
        assert_eq!(
            sorted(&["드라마 10화.mkv", "드라마 2화.mkv", "드라마 1화.mkv", "드라마 02화.mkv"]),
            ["드라마 1화.mkv", "드라마 2화.mkv", "드라마 02화.mkv", "드라마 10화.mkv"],
        );
    }

    #[test]
    fn case_is_ignored_and_a_shorter_prefix_sorts_first() {
        assert_eq!(sorted(&["b2", "B", "a"]), ["a", "B", "b2"]);
    }

    #[test]
    fn pages_sort_by_folder_then_page_number() {
        assert_eq!(
            sorted(&[
                "Vol2/p1.jpg",
                "vol1/p10.jpg",
                "Vol1/P9.jpg",
                "vol1/cover.jpg",
                "vol10/p1.jpg"
            ]),
            [
                "vol1/cover.jpg",
                "Vol1/P9.jpg",
                "vol1/p10.jpg",
                "Vol2/p1.jpg",
                "vol10/p1.jpg"
            ],
        );
    }

    #[test]
    fn numbers_longer_than_any_integer_still_compare() {
        assert_eq!(
            compare("scan 100000000000000000000001.png", "scan 99999999999999999999999.png"),
            Ordering::Greater,
        );
    }
}
