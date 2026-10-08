//! macOS input injection through Quartz Event Services (CoreGraphics).
//!
//! Posting events requires the user to grant the program (or the terminal it
//! runs in) Accessibility access in System Settings. All behavioural decisions
//! live in [`super::macos_plan`], where they are unit-tested; this file only
//! turns plans into CoreGraphics calls.

use super::macos_plan::{self as plan, ClickTracker, DisplayRect, KeyStep, LaunchPlan, TextAction};
use crate::access::{AllowError, Block};
use crate::backend::{InputBackend, PlatformError, Result};
use crate::keymap::macos::ModifierPolicy;
use crate::os::process::{self, Completion};
use objc2_app_kit::{NSEvent, NSEventModifierFlags, NSEventType};
use objc2_core_foundation::{CFRetained, CGPoint};
use objc2_core_graphics::{
    CGDisplayBounds, CGError, CGEvent, CGEventField, CGEventFlags, CGEventSource,
    CGEventSourceStateID, CGEventTapLocation, CGEventType, CGGetActiveDisplayList, CGMouseButton,
    CGPreflightPostEventAccess, CGRequestPostEventAccess, CGScrollEventUnit,
};
use std::time::{Duration, Instant};
use telepad_protocol::{MediaAction, MouseButtonKind, SystemAction, VolumeDirection};

/// How long a snapshot of the display layout stays valid. The layout almost
/// never changes, and refreshing it per pointer event would be wasteful.
const DISPLAY_CACHE_TTL: Duration = Duration::from_secs(2);
const MAX_DISPLAYS: usize = 16;

/// The list in System Settings where a program is allowed to control the computer.
const ACCESSIBILITY_PANE: &str =
    "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";

/// Whether macOS has not yet let this program post events (type and click).
pub(crate) fn input_blocked() -> Option<Block> {
    (!CGPreflightPostEventAccess()).then_some(Block::MacAccessibility)
}

/// Has macOS ask (it only asks the first time), then opens the list where Telepad is switched on. The person
/// does that; [`input_blocked`] says when they have.
pub(crate) fn allow_input() -> std::result::Result<(), AllowError> {
    CGRequestPostEventAccess();
    std::process::Command::new("/usr/bin/open")
        .arg(ACCESSIBILITY_PANE)
        .status()
        .map_err(|error| AllowError::Failed(format!("could not open System Settings: {error}")))
        .and_then(|status| {
            status
                .success()
                .then_some(())
                .ok_or_else(|| AllowError::Failed("System Settings did not open".to_owned()))
        })
}

pub struct MacInput {
    policy: ModifierPolicy,
    /// Modifier flags held by standalone modifier key presses.
    held_flags: u64,
    /// Held state of left, right and middle buttons (selects drag events).
    buttons_down: [bool; 3],
    clicks: ClickTracker,
    displays: Option<(Instant, Vec<DisplayRect>)>,
    home: String,
}

impl MacInput {
    pub fn new(policy: ModifierPolicy) -> Result<Self> {
        if !CGPreflightPostEventAccess() {
            // Shows the system permission prompt the first time; a no-op after.
            CGRequestPostEventAccess();
            tracing::warn!(
                "macOS has not allowed this program to control the computer, so input is \
                 being ignored. Open System Settings > Privacy & Security > Accessibility and \
                 enable it (or the terminal app you started it from), then try again."
            );
        }
        Ok(Self {
            policy,
            held_flags: 0,
            buttons_down: [false; 3],
            clicks: ClickTracker::default(),
            displays: None,
            home: dirs::home_dir().map_or_else(|| "/".into(), |h| h.to_string_lossy().into_owned()),
        })
    }

    /// The pointer's current position in global display coordinates.
    fn cursor_location() -> CGPoint {
        // An event created without a source is stamped with the current pointer location.
        CGEvent::new(None).map_or(CGPoint { x: 0.0, y: 0.0 }, |e| CGEvent::location(Some(&e)))
    }

    fn display_rects(&mut self) -> &[DisplayRect] {
        let stale = self
            .displays
            .as_ref()
            .is_none_or(|(at, _)| at.elapsed() > DISPLAY_CACHE_TTL);
        if stale {
            let mut ids = [0u32; MAX_DISPLAYS];
            let mut count = 0u32;
            // SAFETY: `ids` has room for MAX_DISPLAYS entries and `count` is a valid out-pointer.
            let status = unsafe {
                CGGetActiveDisplayList(MAX_DISPLAYS as u32, ids.as_mut_ptr(), &mut count)
            };
            let rects = if status == CGError::Success {
                ids[..(count as usize).min(MAX_DISPLAYS)]
                    .iter()
                    .map(|&id| {
                        let r = CGDisplayBounds(id);
                        (r.origin.x, r.origin.y, r.size.width, r.size.height)
                    })
                    .collect()
            } else {
                Vec::new()
            };
            self.displays = Some((Instant::now(), rects));
        }
        &self.displays.as_ref().expect("just populated").1
    }

    fn post(event: Option<CFRetained<CGEvent>>) -> Result<CFRetained<CGEvent>> {
        event
            .ok_or_else(|| PlatformError::Os("CoreGraphics could not create an input event".into()))
    }

    fn source() -> Option<CFRetained<CGEventSource>> {
        CGEventSource::new(CGEventSourceStateID::HIDSystemState)
    }

    fn post_keys(steps: &[KeyStep]) -> Result<()> {
        let source = Self::source();
        for step in steps {
            let event = Self::post(CGEvent::new_keyboard_event(
                source.as_deref(),
                step.vk,
                step.down,
            ))?;
            CGEvent::set_flags(Some(&event), CGEventFlags(step.flags));
            CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&event));
        }
        Ok(())
    }

    /// Posts a system-defined media key (volume, play/pause, ...) down then up.
    fn post_aux_key(code: i64) -> Result<()> {
        for down in [true, false] {
            let state: i64 = if down { 0xA } else { 0xB };
            let flags = NSEventModifierFlags((state as usize) << 8);
            let event = NSEvent::otherEventWithType_location_modifierFlags_timestamp_windowNumber_context_subtype_data1_data2(
                NSEventType::SystemDefined,
                CGPoint { x: 0.0, y: 0.0 },
                flags,
                0.0,
                0,
                None,
                8, // NX_SUBTYPE_AUX_CONTROL_BUTTONS
                ((code << 16) | (state << 8)) as isize,
                -1,
            )
            .ok_or_else(|| PlatformError::Os("could not create a media key event".into()))?;
            let cg_event = event
                .CGEvent()
                .ok_or_else(|| PlatformError::Os("media key event has no CGEvent".into()))?;
            CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&cg_event));
        }
        Ok(())
    }
}

fn slot(button: MouseButtonKind) -> usize {
    match button {
        MouseButtonKind::Left => 0,
        MouseButtonKind::Right => 1,
        MouseButtonKind::Middle => 2,
    }
}

impl InputBackend for MacInput {
    fn name(&self) -> &'static str {
        "macOS CoreGraphics"
    }

    fn mouse_move(&mut self, dx: i16, dy: i16) -> Result<()> {
        let current = Self::cursor_location();
        let (x, y) = {
            let rects = self.display_rects().to_vec();
            plan::clamp_to_displays(current.x + f64::from(dx), current.y + f64::from(dy), &rects)
        };
        // While a button is held the pointer must report drag events, or
        // applications will not extend selections or move dragged items.
        let (kind, button) = if self.buttons_down[0] {
            (CGEventType::LeftMouseDragged, CGMouseButton::Left)
        } else if self.buttons_down[1] {
            (CGEventType::RightMouseDragged, CGMouseButton::Right)
        } else if self.buttons_down[2] {
            (CGEventType::OtherMouseDragged, CGMouseButton::Center)
        } else {
            (CGEventType::MouseMoved, CGMouseButton::Left)
        };
        let source = Self::source();
        let event = Self::post(CGEvent::new_mouse_event(
            source.as_deref(),
            kind,
            CGPoint { x, y },
            button,
        ))?;
        // Relative deltas, for applications (games, drag handlers) that read them.
        CGEvent::set_integer_value_field(
            Some(&event),
            CGEventField::MouseEventDeltaX,
            i64::from(dx),
        );
        CGEvent::set_integer_value_field(
            Some(&event),
            CGEventField::MouseEventDeltaY,
            i64::from(dy),
        );
        CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&event));
        Ok(())
    }

    fn mouse_button(&mut self, button: MouseButtonKind, pressed: bool) -> Result<()> {
        let position = Self::cursor_location();
        let (down, up, cg_button) = match button {
            MouseButtonKind::Left => (
                CGEventType::LeftMouseDown,
                CGEventType::LeftMouseUp,
                CGMouseButton::Left,
            ),
            MouseButtonKind::Right => (
                CGEventType::RightMouseDown,
                CGEventType::RightMouseUp,
                CGMouseButton::Right,
            ),
            MouseButtonKind::Middle => (
                CGEventType::OtherMouseDown,
                CGEventType::OtherMouseUp,
                CGMouseButton::Center,
            ),
        };
        let clicks = if pressed {
            self.clicks
                .press(button, Instant::now(), position.x, position.y)
        } else {
            self.clicks.release(button)
        };
        let source = Self::source();
        let kind = if pressed { down } else { up };
        let event = Self::post(CGEvent::new_mouse_event(
            source.as_deref(),
            kind,
            position,
            cg_button,
        ))?;
        CGEvent::set_integer_value_field(Some(&event), CGEventField::MouseEventClickState, clicks);
        CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&event));
        self.buttons_down[slot(button)] = pressed;
        Ok(())
    }

    fn scroll(&mut self, delta: i16) -> Result<()> {
        let source = Self::source();
        let event = Self::post(CGEvent::new_scroll_wheel_event2(
            source.as_deref(),
            CGScrollEventUnit::Line,
            1,
            plan::scroll_lines(delta),
            0,
            0,
        ))?;
        CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&event));
        Ok(())
    }

    fn key(&mut self, usage: u16, mods: u8, pressed: bool) -> Result<()> {
        let (steps, held) = plan::plan_key(usage, mods, pressed, self.policy, self.held_flags);
        Self::post_keys(&steps)?;
        self.held_flags = held;
        Ok(())
    }

    fn text(&mut self, text: &str) -> Result<()> {
        let source = Self::source();
        for action in plan::plan_text(text) {
            match action {
                TextAction::Key(vk) => {
                    Self::post_keys(&[
                        KeyStep {
                            vk,
                            down: true,
                            flags: 0,
                        },
                        KeyStep {
                            vk,
                            down: false,
                            flags: 0,
                        },
                    ])?;
                }
                TextAction::Chars(units) => {
                    for down in [true, false] {
                        // Virtual key 0 is only a carrier; the Unicode payload is what gets typed.
                        let event =
                            Self::post(CGEvent::new_keyboard_event(source.as_deref(), 0, down))?;
                        // SAFETY: `units` outlives the call and its length is passed alongside it.
                        unsafe {
                            CGEvent::keyboard_set_unicode_string(
                                Some(&event),
                                units.len() as std::ffi::c_ulong,
                                units.as_ptr(),
                            );
                        }
                        // Typed text must not turn into shortcuts if a modifier is physically held.
                        CGEvent::set_flags(Some(&event), CGEventFlags(0));
                        CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&event));
                    }
                }
            }
        }
        Ok(())
    }

    fn media(&mut self, action: MediaAction) -> Result<()> {
        match plan::media_key(action) {
            Some(code) => Self::post_aux_key(code),
            None => Err(PlatformError::Os("macOS has no Stop media key".into())),
        }
    }

    fn volume(&mut self, direction: VolumeDirection) -> Result<()> {
        Self::post_aux_key(plan::volume_key(direction))
    }

    fn launch(&mut self, action: SystemAction) -> Result<()> {
        match plan::plan_launch(action, &self.home) {
            LaunchPlan::Chord(vks) => Self::post_keys(&plan::plan_chord(&vks)),
            LaunchPlan::Open(argv) => process::run_first_available(&[argv], Completion::Spawned)
                .map(|_| ())
                .ok_or_else(|| PlatformError::Os(format!("could not run `open` for {action:?}"))),
        }
    }

    fn lock_screen(&mut self) -> Result<()> {
        Self::post_keys(&plan::plan_chord(&plan::lock_chord()))
    }
}
