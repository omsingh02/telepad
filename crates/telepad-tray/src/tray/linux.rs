//! The Linux tray: a StatusNotifierItem, which KDE, Xfce, waybar, GNOME with its AppIndicator extension and
//! most other desktops show. It speaks D-Bus directly, so there is no GTK and nothing to install.

use super::{Command, Commands, Handle, Menu};
use crate::icon;
use ksni::menu::{CheckmarkItem, StandardItem};
use ksni::{MenuItem, TrayMethods};
use std::sync::Arc;
use tokio::sync::Notify;

struct Tray {
    menu: Menu,
    commands: Commands,
}

impl Tray {
    fn choose(&self, command: Command) {
        (self.commands)(command);
    }
}

impl ksni::Tray for Tray {
    fn id(&self) -> String {
        "io.github.omsingh02.telepad".into()
    }

    fn title(&self) -> String {
        "Telepad".into()
    }

    fn category(&self) -> ksni::Category {
        ksni::Category::ApplicationStatus
    }

    fn icon_pixmap(&self) -> Vec<ksni::Icon> {
        vec![argb(icon::APP)]
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            icon_name: String::new(),
            icon_pixmap: Vec::new(),
            title: "Telepad".into(),
            description: self.menu.status.clone(),
        }
    }

    /// A click on the icon itself is what most people try first: show the code.
    fn activate(&mut self, _x: i32, _y: i32) {
        self.choose(Command::Pair);
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        vec![
            StandardItem {
                label: self.menu.status.clone(),
                enabled: false,
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Pair a phone…".into(),
                icon_name: "phone".into(),
                activate: Box::new(|tray: &mut Self| tray.choose(Command::Pair)),
                ..Default::default()
            }
            .into(),
            CheckmarkItem {
                label: "Start at login".into(),
                checked: self.menu.autostart,
                activate: Box::new(|tray: &mut Self| tray.choose(Command::ToggleAutostart)),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Quit Telepad".into(),
                icon_name: "application-exit".into(),
                activate: Box::new(|tray: &mut Self| tray.choose(Command::Quit)),
                ..Default::default()
            }
            .into(),
        ]
    }
}

/// The icon in the format the tray wants: ARGB, most significant byte first.
fn argb(icon: icon::Icon) -> ksni::Icon {
    let mut data = icon.rgba.to_vec();
    for pixel in data.as_chunks_mut::<4>().0 {
        pixel.rotate_right(1);
    }
    ksni::Icon {
        width: icon.size as i32,
        height: icon.size as i32,
        data,
    }
}

pub fn run(
    runtime: &tokio::runtime::Handle,
    menu: Menu,
    commands: Commands,
    ready: impl FnOnce(Handle) + Send + 'static,
    unavailable: impl FnOnce(String) + Send + 'static,
) {
    let stop = Arc::new(Notify::new());
    let inside = runtime.clone();
    let waiting = Arc::clone(&stop);

    runtime.block_on(async move {
        let make = || Tray {
            menu: menu.clone(),
            commands: Arc::clone(&commands),
        };

        let running = match make().spawn().await {
            Ok(running) => Some(running),
            Err(error) => {
                // No tray on this desktop, or not yet: GNOME without its extension has none, and at login
                // Telepad can start before the bar that shows one. The program is told, so that it can
                // offer another way in, and asks to be shown if a tray appears later.
                unavailable(error.to_string());
                make().assume_sni_available(true).spawn().await.ok()
            }
        };

        match running {
            Some(running) => {
                let running = Arc::new(running);
                let for_update = Arc::clone(&running);
                let runtime_for_update = inside.clone();
                let notify = Arc::clone(&waiting);
                ready(Handle::new(
                    move |menu| {
                        let running = Arc::clone(&for_update);
                        runtime_for_update.spawn(async move {
                            running.update(|tray: &mut Tray| tray.menu = menu).await;
                        });
                    },
                    move || {
                        let running = Arc::clone(&running);
                        let notify = Arc::clone(&notify);
                        // The icon goes first, and then the wait below ends.
                        inside.spawn(async move {
                            running.shutdown().await;
                            notify.notify_one();
                        });
                    },
                ));
            }
            None => {
                let notify = Arc::clone(&waiting);
                ready(Handle::new(|_| {}, move || notify.notify_one()));
            }
        }
        stop.notified().await;
    });
}
