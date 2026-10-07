//! The tray icons, as raw RGBA bytes drawn from the brand icon (`assets/make-icons.sh` makes them).

/// A square icon.
#[derive(Clone, Copy)]
pub struct Icon {
    pub rgba: &'static [u8],
    pub size: u32,
}

/// The app icon, for Windows and Linux.
pub const APP: Icon = Icon {
    rgba: include_bytes!("../assets/icon-64.rgba"),
    size: 64,
};

/// A black shape on a transparent ground, which macOS draws in the colour that suits the menu bar.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub const TEMPLATE: Icon = Icon {
    rgba: include_bytes!("../assets/template-44.rgba"),
    size: 44,
};

#[cfg(test)]
mod tests {
    use super::*;

    fn visible(icon: Icon) -> usize {
        icon.rgba
            .as_chunks::<4>()
            .0
            .iter()
            .filter(|pixel| pixel[3] > 0)
            .count()
    }

    #[test]
    fn each_icon_is_exactly_the_size_it_says() {
        for icon in [APP, TEMPLATE] {
            assert_eq!(icon.rgba.len(), (icon.size * icon.size * 4) as usize);
        }
    }

    #[test]
    fn the_icons_draw_something_and_leave_room_around_it() {
        for icon in [APP, TEMPLATE] {
            let pixels = (icon.size * icon.size) as usize;
            assert!(visible(icon) > pixels / 10, "an icon that is nearly empty");
        }
        // The template is a shape, not a square: most of it is transparent.
        assert!(visible(TEMPLATE) < (TEMPLATE.size * TEMPLATE.size) as usize / 2);
    }

    #[test]
    fn the_template_is_black_wherever_it_is_drawn() {
        // macOS recolours a template, but only if what it is given is black with transparency.
        for pixel in TEMPLATE
            .rgba
            .as_chunks::<4>()
            .0
            .iter()
            .filter(|pixel| pixel[3] > 200)
        {
            assert!(pixel[0] < 30 && pixel[1] < 30 && pixel[2] < 30, "{pixel:?}");
        }
    }
}
