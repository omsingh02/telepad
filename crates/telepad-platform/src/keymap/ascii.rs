use super::usage;

/// Maps a character to the HID usage that types it on a US-QWERTY keyboard,
/// plus whether Shift must be held. Mirrors the Android client's `asciiToHid`.
///
/// Returns `None` for anything that has no key on that layout.
pub fn ascii_to_hid(c: char) -> Option<(u16, bool)> {
    let plain = |u: u16| Some((u, false));
    let shifted = |u: u16| Some((u, true));
    match c {
        'a'..='z' => plain(usage::A + (c as u16 - 'a' as u16)),
        'A'..='Z' => shifted(usage::A + (c as u16 - 'A' as u16)),
        '1'..='9' => plain(0x1E + (c as u16 - '1' as u16)),
        '0' => plain(0x27),
        ' ' => plain(usage::SPACE),
        '\n' | '\r' => plain(usage::ENTER),
        '\t' => plain(usage::TAB),
        '\u{8}' => plain(usage::BACKSPACE),
        '-' => plain(0x2D),
        '=' => plain(0x2E),
        '[' => plain(0x2F),
        ']' => plain(0x30),
        '\\' => plain(0x31),
        ';' => plain(0x33),
        '\'' => plain(0x34),
        '`' => plain(0x35),
        ',' => plain(0x36),
        '.' => plain(0x37),
        '/' => plain(0x38),
        '!' => shifted(0x1E),
        '@' => shifted(0x1F),
        '#' => shifted(0x20),
        '$' => shifted(0x21),
        '%' => shifted(0x22),
        '^' => shifted(0x23),
        '&' => shifted(0x24),
        '*' => shifted(0x25),
        '(' => shifted(0x26),
        ')' => shifted(0x27),
        '_' => shifted(0x2D),
        '+' => shifted(0x2E),
        '{' => shifted(0x2F),
        '}' => shifted(0x30),
        '|' => shifted(0x31),
        ':' => shifted(0x33),
        '"' => shifted(0x34),
        '~' => shifted(0x35),
        '<' => shifted(0x36),
        '>' => shifted(0x37),
        '?' => shifted(0x38),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_printable_ascii_char_is_typeable() {
        for c in ' '..='~' {
            assert!(ascii_to_hid(c).is_some(), "no mapping for {c:?}");
        }
    }

    #[test]
    fn shift_state_is_correct() {
        assert_eq!(ascii_to_hid('a'), Some((0x04, false)));
        assert_eq!(ascii_to_hid('A'), Some((0x04, true)));
        assert_eq!(ascii_to_hid('z'), Some((0x1D, false)));
        assert_eq!(ascii_to_hid('1'), Some((0x1E, false)));
        assert_eq!(ascii_to_hid('!'), Some((0x1E, true)));
        assert_eq!(ascii_to_hid('0'), Some((0x27, false)));
        assert_eq!(ascii_to_hid(')'), Some((0x27, true)));
        assert_eq!(ascii_to_hid('?'), Some((0x38, true)));
        assert_eq!(ascii_to_hid('/'), Some((0x38, false)));
    }

    #[test]
    fn whitespace_and_controls() {
        assert_eq!(ascii_to_hid(' '), Some((usage::SPACE, false)));
        assert_eq!(ascii_to_hid('\n'), Some((usage::ENTER, false)));
        assert_eq!(ascii_to_hid('\t'), Some((usage::TAB, false)));
        assert_eq!(ascii_to_hid('\u{8}'), Some((usage::BACKSPACE, false)));
    }

    #[test]
    fn non_ascii_has_no_key() {
        assert_eq!(ascii_to_hid('é'), None);
        assert_eq!(ascii_to_hid('한'), None);
        assert_eq!(ascii_to_hid('🚀'), None);
        assert_eq!(ascii_to_hid('\u{7f}'), None);
    }

    #[test]
    fn distinct_characters_never_collide_on_the_same_key_and_shift_state() {
        use std::collections::HashMap;
        let mut seen: HashMap<(u16, bool), char> = HashMap::new();
        for c in ' '..='~' {
            let key = ascii_to_hid(c).unwrap();
            if let Some(prev) = seen.insert(key, c) {
                panic!("{prev:?} and {c:?} both map to {key:?}");
            }
        }
    }
}
