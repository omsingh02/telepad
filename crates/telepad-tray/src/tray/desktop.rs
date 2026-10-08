//! The Windows and macOS tray: an icon with a menu, served by an event loop on the main thread.
//!
//! Both systems insist on the loop (Windows delivers the icon's clicks as window messages, macOS draws it from
//! the application's run loop), so this takes over the calling thread and ends the program when stopped.

use super::{Command, Commands, Handle, Menu};
use crate::icon;
use tao::event::{Event, StartCause};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tray_icon::menu::{CheckMenuItem, Menu as NativeMenu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent};

/// What reaches the loop from outside it.
enum Wake {
    /// A choice in the menu.
    Menu(MenuEvent),
    /// A click on the icon itself.
    Click(TrayIconEvent),
    /// New words for the menu.
    Update(Menu),
    /// Time to end.
    Stop,
}

pub fn run(
    _runtime: &tokio::runtime::Handle,
    menu: Menu,
    commands: Commands,
    ready: impl FnOnce(Handle) + Send + 'static,
    unavailable: impl FnOnce(String) + Send + 'static,
) {
    let mut event_loop = EventLoopBuilder::<Wake>::with_user_event().build();
    // A tray program has no Dock icon and no window of its own.
    #[cfg(target_os = "macos")]
    {
        use tao::platform::macos::{ActivationPolicy, EventLoopExtMacOS};
        event_loop.set_activation_policy(ActivationPolicy::Accessory);
    }
    #[cfg(not(target_os = "macos"))]
    let _ = &mut event_loop;

    // The tray's own events arrive on other threads; each wakes the loop.
    let proxy = event_loop.create_proxy();
    MenuEvent::set_event_handler(Some(move |event| {
        let _ = proxy.send_event(Wake::Menu(event));
    }));
    let proxy = event_loop.create_proxy();
    TrayIconEvent::set_event_handler(Some(move |event| {
        let _ = proxy.send_event(Wake::Click(event));
    }));

    let for_update = event_loop.create_proxy();
    let for_stop = event_loop.create_proxy();
    let handle = Handle::new(
        move |menu| {
            let _ = for_update.send_event(Wake::Update(menu));
        },
        move || {
            let _ = for_stop.send_event(Wake::Stop);
        },
    );

    let pair = MenuItem::new("Pair a phone…", true, None);
    let update = MenuItem::new(&menu.update, true, None);
    let allow = MenuItem::new(menu.allow.unwrap_or("Allow Telepad…"), true, None);
    let native = NativeMenu::new();
    let autostart = CheckMenuItem::new("Start at login", true, menu.autostart, None);
    let quit = MenuItem::new("Quit Telepad", true, None);
    let status = MenuItem::new(&menu.status, false, None);

    let mut tray: Option<TrayIcon> = None;
    let mut ready = Some(ready);
    let mut unavailable = Some(unavailable);
    let mut handle = Some(handle);
    let mut menu = menu;
    // Where the item that asks for permission goes: below the status line and its separator.
    let allow_at = 2;
    let mut allow_shown = false;

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            // The icon is made once the loop is running: made earlier, it may never show (macOS).
            Event::NewEvents(StartCause::Init) => {
                match build(&menu, &native, &status, &pair, &update, &autostart, &quit) {
                    Ok(built) => {
                        tray = Some(built);
                        if menu.allow.is_some() && native.insert(&allow, allow_at).is_ok() {
                            allow_shown = true;
                        }
                    }
                    Err(reason) => {
                        if let Some(unavailable) = unavailable.take() {
                            unavailable(reason);
                        }
                    }
                }
                // macOS draws the icon on the next turn of its run loop, which nothing has asked for yet.
                #[cfg(target_os = "macos")]
                wake_run_loop();
                if let (Some(ready), Some(handle)) = (ready.take(), handle.take()) {
                    ready(handle);
                }
            }
            Event::UserEvent(Wake::Menu(event)) => {
                if event.id == *pair.id() {
                    commands(Command::Pair);
                } else if event.id == *allow.id() {
                    commands(Command::Allow);
                } else if event.id == *update.id() {
                    commands(Command::Update);
                } else if event.id == *autostart.id() {
                    commands(Command::ToggleAutostart);
                } else if event.id == *quit.id() {
                    commands(Command::Quit);
                }
            }
            Event::UserEvent(Wake::Click(TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            })) => commands(Command::Pair),
            // Opening the app again from Finder or Spotlight while it runs: show the code.
            #[cfg(target_os = "macos")]
            Event::Reopen { .. } => commands(Command::Pair),
            Event::UserEvent(Wake::Update(new)) => {
                if new != menu {
                    status.set_text(&new.status);
                    autostart.set_checked(new.autostart);
                    update.set_text(&new.update);
                    match new.allow {
                        Some(label) => {
                            allow.set_text(label);
                            if !allow_shown && native.insert(&allow, allow_at).is_ok() {
                                allow_shown = true;
                            }
                        }
                        None => {
                            if allow_shown && native.remove(&allow).is_ok() {
                                allow_shown = false;
                            }
                        }
                    }
                    if let Some(tray) = &tray {
                        let _ = tray.set_tooltip(Some(format!("Telepad: {}", new.status)));
                    }
                    menu = new;
                }
            }
            Event::UserEvent(Wake::Stop) => {
                // The icon must be taken away, or Windows leaves a dead one in the tray until it is hovered.
                tray.take();
                *control_flow = ControlFlow::Exit;
            }
            _ => {}
        }
    });
}

/// Makes the icon with its menu.
fn build(
    menu: &Menu,
    native: &NativeMenu,
    status: &MenuItem,
    pair: &MenuItem,
    update: &MenuItem,
    autostart: &CheckMenuItem,
    quit: &MenuItem,
) -> Result<TrayIcon, String> {
    native
        .append_items(&[
            status,
            &PredefinedMenuItem::separator(),
            pair,
            update,
            autostart,
            &PredefinedMenuItem::separator(),
            quit,
        ])
        .map_err(|error| error.to_string())?;

    // On a Mac the icon is a black shape that the system recolours for the bar; elsewhere it is the app's icon.
    let (source, template) = if cfg!(target_os = "macos") {
        (icon::TEMPLATE, true)
    } else {
        (icon::APP, false)
    };
    let image = Icon::from_rgba(source.rgba.to_vec(), source.size, source.size)
        .map_err(|error| error.to_string())?;

    let builder = TrayIconBuilder::new()
        .with_menu(Box::new(native.clone()))
        // A click on the icon shows the code; the menu is for the right button.
        .with_menu_on_left_click(false)
        .with_tooltip(format!("Telepad: {}", menu.status));
    // Only macOS has "template" icons, which it recolours for the bar.
    #[cfg(target_os = "macos")]
    let builder = if template {
        builder.with_icon_templated(image)
    } else {
        builder.with_icon(image)
    };
    #[cfg(not(target_os = "macos"))]
    let builder = {
        let _ = template;
        builder.with_icon(image)
    };
    builder.build().map_err(|error| error.to_string())
}

#[cfg(target_os = "macos")]
fn wake_run_loop() {
    use objc2_core_foundation::CFRunLoop;
    if let Some(run_loop) = CFRunLoop::main() {
        run_loop.wake_up();
    }
}
