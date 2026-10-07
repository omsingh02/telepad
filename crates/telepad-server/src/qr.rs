//! Drawing a QR code in the terminal.

use qrcode::render::unicode::Dense1x2;
use qrcode::{EcLevel, QrCode};

/// Which kind of terminal the code is drawn for. A QR code is dark squares on a light ground, and
/// a terminal draws a block in its text colour, so the two kinds need opposite drawings.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Style {
    /// Light text on a dark background, which is what most terminals are.
    #[default]
    DarkTerminal,
    /// Dark text on a light background.
    LightTerminal,
}

/// The QR code for `text`, drawn with block characters (two rows of the code to a line of text), or
/// `None` if the text is too long for one.
///
/// A drawing for the wrong kind of terminal is inverted. The Telepad app's scanner reads that too,
/// but other scanners may not, which is why the console can draw it either way.
pub fn terminal(text: &str, style: Style) -> Option<String> {
    // The lowest error correction: the code is read off a clean screen, and a smaller code fits more
    // terminals (a taller one is cut off in a window of 24 lines).
    let code = QrCode::with_error_correction_level(text, EcLevel::L).ok()?;
    let (dark, light) = match style {
        Style::DarkTerminal => (Dense1x2::Light, Dense1x2::Dark),
        Style::LightTerminal => (Dense1x2::Dark, Dense1x2::Light),
    };
    Some(
        code.render::<Dense1x2>()
            .dark_color(dark)
            .light_color(light)
            .quiet_zone(true)
            .build(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const LINK: &str = "telepad://pair?v=1&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&t=AAAAAAAAAAAAAAAAAAAAAA&p=5000&h=192.168.1.20&n=DESKTOP-PC";

    #[test]
    fn a_link_becomes_a_square_of_block_characters() {
        let drawing = terminal(LINK, Style::DarkTerminal).expect("fits in a code");
        let lines: Vec<&str> = drawing.lines().collect();
        assert!(lines.len() >= 15, "{} lines", lines.len());
        assert!(
            lines.len() <= 27,
            "{} lines is too tall for a small terminal window",
            lines.len()
        );

        // Two rows of modules to a line, and as wide as the code is tall.
        let width = lines[0].chars().count();
        assert!(
            lines.iter().all(|line| line.chars().count() == width),
            "every line is as wide as the first"
        );
        assert!(
            width.abs_diff(lines.len() * 2) <= 2,
            "{width} wide, {} lines",
            lines.len()
        );

        assert!(
            drawing
                .chars()
                .all(|c| matches!(c, ' ' | '▀' | '▄' | '█' | '\n')),
            "only block characters"
        );
    }

    #[test]
    fn a_dark_terminal_gets_a_lit_border() {
        let drawing = terminal(LINK, Style::DarkTerminal).unwrap();
        // The quiet zone around the code is part of it, and is the code's light colour.
        assert!(
            drawing.lines().next().unwrap().chars().all(|c| c == '█'),
            "the top margin is lit"
        );
    }

    #[test]
    fn a_light_terminal_gets_the_opposite_drawing_of_the_same_code() {
        let dark = terminal(LINK, Style::DarkTerminal).unwrap();
        let light = terminal(LINK, Style::LightTerminal).unwrap();
        assert!(
            light.lines().next().unwrap().chars().all(|c| c == ' '),
            "the margin is the terminal's own background"
        );

        // Each block is the other's opposite.
        let invert = |c: char| match c {
            '█' => ' ',
            ' ' => '█',
            '▀' => '▄',
            '▄' => '▀',
            other => other,
        };
        // Every line but the last, which is half a row of padding (drawn as plain background).
        let body = |drawing: &str| drawing.lines().take(24).collect::<Vec<_>>().join("\n");
        assert_eq!(
            body(&dark).chars().map(invert).collect::<String>(),
            body(&light)
        );
        assert_eq!(dark.lines().count(), light.lines().count());
    }

    #[test]
    fn text_too_long_for_a_code_gives_nothing_instead_of_panicking() {
        assert!(terminal(&"x".repeat(5000), Style::DarkTerminal).is_none());
    }
}
