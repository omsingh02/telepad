use bytes::{Buf, BufMut, BytesMut};
use thiserror::Error;

pub const WIRE_HANDSHAKE_INIT: u8 = 0xC0;
pub const WIRE_HANDSHAKE_RESP: u8 = 0xC1;
pub const WIRE_TRANSPORT: u8 = 0xC2;
pub const WIRE_DISCOVERY_PROBE: u8 = 0xC3;
pub const WIRE_DISCOVERY_REPLY: u8 = 0xC4;
pub const WIRE_PAIRING_INTRO_REQ: u8 = 0xC5;
pub const WIRE_PAIRING_INTRO_RESP: u8 = 0xC6;
/// Sent instead of a handshake response when the server will not accept this
/// device (it has not been paired and pairing is closed). Clients that predate
/// it see an unexpected reply and fail the handshake at once instead of timing out.
pub const WIRE_PAIRING_REJECTED: u8 = 0xC7;

/// The release this build belongs to, such as `2.0.0-alpha.4`. The release workflow passes the whole tag as
/// `TELEPAD_RELEASE_VERSION`; a build made any other way is the version in `Cargo.toml`, which has no pre-release
/// part. It is what the program says it is, and what update checks compare with.
pub const RELEASE_VERSION: &str = match option_env!("TELEPAD_RELEASE_VERSION") {
    Some(version) if !version.is_empty() => version,
    _ => env!("CARGO_PKG_VERSION"),
};

pub const TELEPAD_PORT: u16 = 5000;
pub const TELEPAD_MULTICAST_GROUP: &str = "239.255.42.67";
pub const TELEPAD_DISCOVERY_MAGIC: &[u8; 8] = &[0x54, 0xE7, 0x9A, 0x03, 0x21, 0xC8, 0xBE, 0xFE];
pub const TELEPAD_DISCOVERY_MAGIC_ALT: &[u8; 8] = b"TELEPAD!";

pub const MSG_TYPE_MOUSE_MOVE: u8 = 0x01;
pub const MSG_TYPE_MOUSE_BUTTON: u8 = 0x02;
pub const MSG_TYPE_SCROLL: u8 = 0x03;
pub const MSG_TYPE_KEY_PRESS: u8 = 0x04;
pub const MSG_TYPE_KEY_RELEASE: u8 = 0x05;
pub const MSG_TYPE_TEXT_INPUT: u8 = 0x06;
pub const MSG_TYPE_MEDIA_CMD: u8 = 0x07;
pub const MSG_TYPE_VOLUME_CMD: u8 = 0x08;
pub const MSG_TYPE_LOCK_SCREEN: u8 = 0x09;
pub const MSG_TYPE_CLIPBOARD_GET: u8 = 0x0E;
pub const MSG_TYPE_CLIPBOARD_SET: u8 = 0x0F;
pub const MSG_TYPE_LAUNCH_ACTION: u8 = 0x10;
pub const MSG_TYPE_NOW_PLAYING_Q: u8 = 0x11;
pub const MSG_TYPE_HOST_INFO_Q: u8 = 0x12;

pub const MSG_TYPE_CLIPBOARD_DATA: u8 = 0x80;
pub const MSG_TYPE_NOW_PLAYING: u8 = 0x81;
pub const MSG_TYPE_HOST_INFO: u8 = 0x82;

#[derive(Error, Debug, PartialEq, Eq)]
pub enum ProtocolError {
    #[error("Datagram is empty")]
    EmptyPacket,
    #[error("Buffer too short: expected at least {expected}, got {actual}")]
    BufferTooShort { expected: usize, actual: usize },
    #[error("Unknown envelope tag: 0x{0:02X}")]
    UnknownEnvelopeTag(u8),
    #[error("Unknown message type: 0x{0:02X}")]
    UnknownMessageType(u8),
    #[error("Invalid UTF-8 payload: {0}")]
    InvalidUtf8(#[from] std::string::FromUtf8Error),
    #[error("Unknown enum value {0}")]
    InvalidEnumValue(u8),
    #[error("Payload exceeds maximum size: {size} > {max}")]
    PayloadTooLarge { size: usize, max: usize },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MouseButtonKind {
    Left = 0x00,
    Right = 0x01,
    Middle = 0x02,
}

impl TryFrom<u8> for MouseButtonKind {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::Left),
            0x01 => Ok(Self::Right),
            0x02 => Ok(Self::Middle),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MediaAction {
    PlayPause = 0x00,
    Next = 0x01,
    Prev = 0x02,
    Stop = 0x03,
}

impl TryFrom<u8> for MediaAction {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::PlayPause),
            0x01 => Ok(Self::Next),
            0x02 => Ok(Self::Prev),
            0x03 => Ok(Self::Stop),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum VolumeDirection {
    Up = 0x00,
    Down = 0x01,
    Mute = 0x02,
}

impl TryFrom<u8> for VolumeDirection {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::Up),
            0x01 => Ok(Self::Down),
            0x02 => Ok(Self::Mute),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SystemAction {
    ShowDesktop = 0x00,
    TaskView = 0x01,
    Browser = 0x02,
    FileManager = 0x03,
    TaskManager = 0x04,
    Screenshot = 0x05,
}

impl TryFrom<u8> for SystemAction {
    type Error = ProtocolError;
    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x00 => Ok(Self::ShowDesktop),
            0x01 => Ok(Self::TaskView),
            0x02 => Ok(Self::Browser),
            0x03 => Ok(Self::FileManager),
            0x04 => Ok(Self::TaskManager),
            0x05 => Ok(Self::Screenshot),
            other => Err(ProtocolError::InvalidEnumValue(other)),
        }
    }
}

/// Operating system of the machine running the server. Lets the phone show the
/// right key names (Win / Command / Super) and send the right shortcuts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[repr(u8)]
pub enum HostOs {
    /// Not reported, or an operating system this version does not know. Clients
    /// should behave as they did before hosts reported anything (Windows).
    #[default]
    Unknown = 0,
    Windows = 1,
    MacOs = 2,
    Linux = 3,
}

impl HostOs {
    /// The OS this binary was built for.
    pub fn current() -> Self {
        if cfg!(windows) {
            Self::Windows
        } else if cfg!(target_os = "macos") {
            Self::MacOs
        } else if cfg!(target_os = "linux") {
            Self::Linux
        } else {
            Self::Unknown
        }
    }
}

impl From<u8> for HostOs {
    /// Never fails: a newer server may report an OS this version has not heard of.
    fn from(val: u8) -> Self {
        match val {
            1 => Self::Windows,
            2 => Self::MacOs,
            3 => Self::Linux,
            _ => Self::Unknown,
        }
    }
}

/// Feature bits in [`HostInfo::capabilities`].
pub mod capabilities {
    /// The server can report what is playing (`NowPlaying` replies carry real data).
    pub const NOW_PLAYING: u8 = 0x01;
    /// The server can read and write the clipboard.
    pub const CLIPBOARD: u8 = 0x02;
}

/// What a server tells a phone about itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct HostInfo {
    pub os: HostOs,
    /// Bitmask of [`capabilities`].
    pub capabilities: u8,
    /// Server version as `(major, minor, patch)`.
    pub version: (u8, u8, u8),
}

impl HostInfo {
    pub fn supports(&self, capability: u8) -> bool {
        self.capabilities & capability != 0
    }
}

pub mod modifiers {
    pub const LCTRL: u8 = 0x01;
    pub const LSHIFT: u8 = 0x02;
    pub const LALT: u8 = 0x04;
    pub const LMETA: u8 = 0x08;
    pub const RCTRL: u8 = 0x10;
    pub const RSHIFT: u8 = 0x20;
    pub const RALT: u8 = 0x40;
    pub const RMETA: u8 = 0x80;
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct NowPlayingState {
    pub is_playing: bool,
    pub position_ms: Option<i64>,
    pub duration_ms: Option<i64>,
    pub title: Option<String>,
    pub artist: Option<String>,
    pub album: Option<String>,
    pub source_app: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ClientMessage {
    MouseMove {
        dx: i16,
        dy: i16,
    },
    MouseButton {
        button: MouseButtonKind,
        pressed: bool,
    },
    Scroll {
        delta: i16,
    },
    KeyPress {
        keycode: u16,
        mods: u8,
    },
    KeyRelease {
        keycode: u16,
        mods: u8,
    },
    TextInput(String),
    MediaCmd(MediaAction),
    VolumeCmd(VolumeDirection),
    LockScreen,
    ClipboardGet,
    ClipboardSet(String),
    LaunchAction(SystemAction),
    NowPlayingQuery,
    HostInfoQuery,
}

impl ClientMessage {
    pub fn encode(&self, buf: &mut BytesMut) -> Result<(), ProtocolError> {
        match self {
            Self::MouseMove { dx, dy } => {
                buf.put_u8(MSG_TYPE_MOUSE_MOVE);
                buf.put_i16_le(*dx);
                buf.put_i16_le(*dy);
            }
            Self::MouseButton { button, pressed } => {
                buf.put_u8(MSG_TYPE_MOUSE_BUTTON);
                buf.put_u8(*button as u8);
                buf.put_u8(if *pressed { 1 } else { 0 });
            }
            Self::Scroll { delta } => {
                buf.put_u8(MSG_TYPE_SCROLL);
                buf.put_i16_le(*delta);
            }
            Self::KeyPress { keycode, mods } => {
                buf.put_u8(MSG_TYPE_KEY_PRESS);
                buf.put_u16_le(*keycode);
                buf.put_u8(*mods);
            }
            Self::KeyRelease { keycode, mods } => {
                buf.put_u8(MSG_TYPE_KEY_RELEASE);
                buf.put_u16_le(*keycode);
                buf.put_u8(*mods);
            }
            Self::TextInput(text) => {
                let bytes = text.as_bytes();
                if bytes.len() > u16::MAX as usize {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: u16::MAX as usize,
                    });
                }
                buf.put_u8(MSG_TYPE_TEXT_INPUT);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
            }
            Self::MediaCmd(action) => {
                buf.put_u8(MSG_TYPE_MEDIA_CMD);
                buf.put_u8(*action as u8);
            }
            Self::VolumeCmd(direction) => {
                buf.put_u8(MSG_TYPE_VOLUME_CMD);
                buf.put_u8(*direction as u8);
            }
            Self::LockScreen => {
                buf.put_u8(MSG_TYPE_LOCK_SCREEN);
            }
            Self::ClipboardGet => {
                buf.put_u8(MSG_TYPE_CLIPBOARD_GET);
            }
            Self::ClipboardSet(text) => {
                let bytes = text.as_bytes();
                if bytes.len() > u16::MAX as usize {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: u16::MAX as usize,
                    });
                }
                buf.put_u8(MSG_TYPE_CLIPBOARD_SET);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
            }
            Self::LaunchAction(action) => {
                buf.put_u8(MSG_TYPE_LAUNCH_ACTION);
                buf.put_u8(*action as u8);
            }
            Self::NowPlayingQuery => {
                buf.put_u8(MSG_TYPE_NOW_PLAYING_Q);
            }
            Self::HostInfoQuery => {
                buf.put_u8(MSG_TYPE_HOST_INFO_Q);
            }
        }
        Ok(())
    }

    pub fn decode(mut buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.is_empty() {
            return Err(ProtocolError::EmptyPacket);
        }
        let msg_type = buf.get_u8();
        match msg_type {
            MSG_TYPE_MOUSE_MOVE => {
                if buf.len() < 4 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 4,
                        actual: buf.len(),
                    });
                }
                let dx = buf.get_i16_le();
                let dy = buf.get_i16_le();
                Ok(Self::MouseMove { dx, dy })
            }
            MSG_TYPE_MOUSE_BUTTON => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let button = MouseButtonKind::try_from(buf.get_u8())?;
                let pressed = buf.get_u8() != 0;
                Ok(Self::MouseButton { button, pressed })
            }
            MSG_TYPE_SCROLL => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let delta = buf.get_i16_le();
                Ok(Self::Scroll { delta })
            }
            MSG_TYPE_KEY_PRESS => {
                if buf.len() < 3 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 3,
                        actual: buf.len(),
                    });
                }
                let keycode = buf.get_u16_le();
                let mods = buf.get_u8();
                Ok(Self::KeyPress { keycode, mods })
            }
            MSG_TYPE_KEY_RELEASE => {
                if buf.len() < 3 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 3,
                        actual: buf.len(),
                    });
                }
                let keycode = buf.get_u16_le();
                let mods = buf.get_u8();
                Ok(Self::KeyRelease { keycode, mods })
            }
            MSG_TYPE_TEXT_INPUT => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let len = buf.get_u16_le() as usize;
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::TextInput(text))
            }
            MSG_TYPE_MEDIA_CMD => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                let action = MediaAction::try_from(buf.get_u8())?;
                Ok(Self::MediaCmd(action))
            }
            MSG_TYPE_VOLUME_CMD => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                let direction = VolumeDirection::try_from(buf.get_u8())?;
                Ok(Self::VolumeCmd(direction))
            }
            MSG_TYPE_LOCK_SCREEN => Ok(Self::LockScreen),
            MSG_TYPE_CLIPBOARD_GET => Ok(Self::ClipboardGet),
            MSG_TYPE_CLIPBOARD_SET => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let len = buf.get_u16_le() as usize;
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::ClipboardSet(text))
            }
            MSG_TYPE_LAUNCH_ACTION => {
                if buf.is_empty() {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 1,
                        actual: 0,
                    });
                }
                let action = SystemAction::try_from(buf.get_u8())?;
                Ok(Self::LaunchAction(action))
            }
            MSG_TYPE_NOW_PLAYING_Q => Ok(Self::NowPlayingQuery),
            MSG_TYPE_HOST_INFO_Q => Ok(Self::HostInfoQuery),
            other => Err(ProtocolError::UnknownMessageType(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum ServerMessage {
    ClipboardData(String),
    NowPlaying(NowPlayingState),
    HostInfo(HostInfo),
}

impl ServerMessage {
    pub fn encode(&self, buf: &mut BytesMut) -> Result<(), ProtocolError> {
        match self {
            Self::ClipboardData(text) => {
                let bytes = text.as_bytes();
                if bytes.len() > u16::MAX as usize {
                    return Err(ProtocolError::PayloadTooLarge {
                        size: bytes.len(),
                        max: u16::MAX as usize,
                    });
                }
                buf.put_u8(MSG_TYPE_CLIPBOARD_DATA);
                buf.put_u16_le(bytes.len() as u16);
                buf.put_slice(bytes);
                Ok(())
            }
            Self::NowPlaying(state) => {
                buf.put_u8(MSG_TYPE_NOW_PLAYING);
                let mut flags: u8 = 0;
                if state.is_playing {
                    flags |= 0x01;
                }
                if state.title.is_some() {
                    flags |= 0x02;
                }
                if state.artist.is_some() {
                    flags |= 0x04;
                }
                if state.album.is_some() {
                    flags |= 0x08;
                }
                if state.source_app.is_some() {
                    flags |= 0x10;
                }
                buf.put_u8(flags);
                buf.put_i64_le(state.position_ms.unwrap_or(-1));
                buf.put_i64_le(state.duration_ms.unwrap_or(-1));

                let encode_str = |b: &mut BytesMut, s: &Option<String>| {
                    if let Some(str_val) = s {
                        if str_val.is_empty() {
                            b.put_u8(0);
                        } else {
                            let mut end = str_val.len().min(255);
                            while !str_val.is_char_boundary(end) {
                                end -= 1;
                            }
                            b.put_u8(end as u8);
                            b.put_slice(&str_val.as_bytes()[..end]);
                        }
                    } else {
                        b.put_u8(0);
                    }
                };

                encode_str(buf, &state.title);
                encode_str(buf, &state.artist);
                encode_str(buf, &state.album);
                encode_str(buf, &state.source_app);
                Ok(())
            }
            Self::HostInfo(info) => {
                buf.put_u8(MSG_TYPE_HOST_INFO);
                buf.put_u8(info.os as u8);
                buf.put_u8(info.capabilities);
                buf.put_u8(info.version.0);
                buf.put_u8(info.version.1);
                buf.put_u8(info.version.2);
                Ok(())
            }
        }
    }

    pub fn decode(mut buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.is_empty() {
            return Err(ProtocolError::EmptyPacket);
        }
        let msg_type = buf.get_u8();
        match msg_type {
            MSG_TYPE_CLIPBOARD_DATA => {
                if buf.len() < 2 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 2,
                        actual: buf.len(),
                    });
                }
                let len = buf.get_u16_le() as usize;
                if buf.len() < len {
                    return Err(ProtocolError::BufferTooShort {
                        expected: len,
                        actual: buf.len(),
                    });
                }
                let text = String::from_utf8(buf[..len].to_vec())?;
                Ok(Self::ClipboardData(text))
            }
            MSG_TYPE_NOW_PLAYING => {
                if buf.len() < 17 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 17,
                        actual: buf.len(),
                    });
                }
                let flags = buf.get_u8();
                let is_playing = (flags & 0x01) != 0;
                let title_present = (flags & 0x02) != 0;
                let artist_present = (flags & 0x04) != 0;
                let album_present = (flags & 0x08) != 0;
                let source_app_present = (flags & 0x10) != 0;
                let pos = buf.get_i64_le();
                let dur = buf.get_i64_le();

                let read_str =
                    |b: &mut &[u8], present: bool| -> Result<Option<String>, ProtocolError> {
                        if b.is_empty() {
                            return Ok(if present { Some(String::new()) } else { None });
                        }
                        let l = b.get_u8() as usize;
                        if l == 0 {
                            return Ok(if present { Some(String::new()) } else { None });
                        }
                        if b.len() < l {
                            return Err(ProtocolError::BufferTooShort {
                                expected: l,
                                actual: b.len(),
                            });
                        }
                        let s = String::from_utf8(b[..l].to_vec())?;
                        b.advance(l);
                        Ok(Some(s))
                    };

                let title = read_str(&mut buf, title_present)?;
                let artist = read_str(&mut buf, artist_present)?;
                let album = read_str(&mut buf, album_present)?;
                let source_app = read_str(&mut buf, source_app_present)?;

                Ok(Self::NowPlaying(NowPlayingState {
                    is_playing,
                    position_ms: if pos >= 0 { Some(pos) } else { None },
                    duration_ms: if dur >= 0 { Some(dur) } else { None },
                    title,
                    artist,
                    album,
                    source_app,
                }))
            }
            MSG_TYPE_HOST_INFO => {
                // Later versions may append fields; read what we know and ignore the rest.
                if buf.len() < 5 {
                    return Err(ProtocolError::BufferTooShort {
                        expected: 5,
                        actual: buf.len(),
                    });
                }
                let os = HostOs::from(buf.get_u8());
                let capabilities = buf.get_u8();
                let version = (buf.get_u8(), buf.get_u8(), buf.get_u8());
                Ok(Self::HostInfo(HostInfo {
                    os,
                    capabilities,
                    version,
                }))
            }
            other => Err(ProtocolError::UnknownMessageType(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mouse_move_roundtrip() {
        let original = ClientMessage::MouseMove { dx: -125, dy: 450 };
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_mouse_button_roundtrip() {
        let original = ClientMessage::MouseButton {
            button: MouseButtonKind::Right,
            pressed: true,
        };
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_scroll_roundtrip() {
        let original = ClientMessage::Scroll { delta: -120 };
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_key_press_and_release_roundtrip() {
        let press = ClientMessage::KeyPress {
            keycode: 0x41,
            mods: modifiers::LCTRL | modifiers::LSHIFT,
        };
        let mut buf = BytesMut::new();
        press.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(press, decoded);

        let release = ClientMessage::KeyRelease {
            keycode: 0x41,
            mods: 0,
        };
        buf.clear();
        release.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(release, decoded);
    }

    #[test]
    fn test_text_input_roundtrip() {
        let original = ClientMessage::TextInput("Hello Telepad v2! \u{1F680}".into());
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ClientMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_payload_too_large_rejected() {
        let huge_text = "a".repeat(70000);
        let msg = ClientMessage::TextInput(huge_text);
        let mut buf = BytesMut::new();
        assert!(matches!(
            msg.encode(&mut buf),
            Err(ProtocolError::PayloadTooLarge { .. })
        ));
    }

    #[test]
    fn test_all_control_messages_roundtrip() {
        let msgs = [
            ClientMessage::MediaCmd(MediaAction::PlayPause),
            ClientMessage::VolumeCmd(VolumeDirection::Up),
            ClientMessage::LockScreen,
            ClientMessage::ClipboardGet,
            ClientMessage::ClipboardSet("Copy text".into()),
            ClientMessage::LaunchAction(SystemAction::TaskView),
            ClientMessage::NowPlayingQuery,
        ];
        for msg in msgs {
            let mut buf = BytesMut::new();
            msg.encode(&mut buf).unwrap();
            let decoded = ClientMessage::decode(&buf).unwrap();
            assert_eq!(msg, decoded);
        }
    }

    #[test]
    fn host_info_query_roundtrip() {
        let mut buf = BytesMut::new();
        ClientMessage::HostInfoQuery.encode(&mut buf).unwrap();
        assert_eq!(&buf[..], &[MSG_TYPE_HOST_INFO_Q]);
        assert_eq!(
            ClientMessage::decode(&buf).unwrap(),
            ClientMessage::HostInfoQuery
        );
    }

    #[test]
    fn host_info_roundtrip_and_wire_layout() {
        let info = HostInfo {
            os: HostOs::Linux,
            capabilities: capabilities::CLIPBOARD,
            version: (2, 1, 7),
        };
        let mut buf = BytesMut::new();
        ServerMessage::HostInfo(info).encode(&mut buf).unwrap();
        // [type][os][caps][major][minor][patch]
        assert_eq!(&buf[..], &[0x82, 3, 0x02, 2, 1, 7]);
        assert_eq!(
            ServerMessage::decode(&buf).unwrap(),
            ServerMessage::HostInfo(info)
        );
        assert!(info.supports(capabilities::CLIPBOARD));
        assert!(!info.supports(capabilities::NOW_PLAYING));
    }

    #[test]
    fn host_info_tolerates_extra_trailing_fields_from_newer_servers() {
        let bytes = [MSG_TYPE_HOST_INFO, 2, 0x03, 3, 0, 0, 0xAA, 0xBB, 0xCC];
        let ServerMessage::HostInfo(info) = ServerMessage::decode(&bytes).unwrap() else {
            panic!("expected host info");
        };
        assert_eq!(info.os, HostOs::MacOs);
        assert_eq!(info.version, (3, 0, 0));
        assert!(info.supports(capabilities::NOW_PLAYING) && info.supports(capabilities::CLIPBOARD));
    }

    #[test]
    fn host_info_with_an_unknown_os_decodes_as_unknown_instead_of_failing() {
        let ServerMessage::HostInfo(info) =
            ServerMessage::decode(&[MSG_TYPE_HOST_INFO, 200, 0, 1, 2, 3]).unwrap()
        else {
            panic!("expected host info");
        };
        assert_eq!(info.os, HostOs::Unknown);
        assert_eq!(HostOs::from(0), HostOs::Unknown);
        assert_eq!(HostOs::from(1), HostOs::Windows);
        assert_eq!(HostOs::from(2), HostOs::MacOs);
        assert_eq!(HostOs::from(3), HostOs::Linux);
    }

    #[test]
    fn truncated_host_info_is_rejected() {
        for len in 1..6 {
            let bytes = &[MSG_TYPE_HOST_INFO, 1, 0, 1, 2, 3][..len];
            assert!(
                matches!(
                    ServerMessage::decode(bytes),
                    Err(ProtocolError::BufferTooShort { .. })
                ),
                "len {len}"
            );
        }
    }

    #[test]
    fn current_host_os_matches_the_build_target() {
        let os = HostOs::current();
        if cfg!(windows) {
            assert_eq!(os, HostOs::Windows);
        } else if cfg!(target_os = "macos") {
            assert_eq!(os, HostOs::MacOs);
        } else if cfg!(target_os = "linux") {
            assert_eq!(os, HostOs::Linux);
        }
    }

    #[test]
    fn test_clipboard_data_roundtrip() {
        let original = ServerMessage::ClipboardData("Sync clipboard data \u{1F4CB}".into());
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ServerMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_now_playing_roundtrip() {
        let original = ServerMessage::NowPlaying(NowPlayingState {
            is_playing: true,
            position_ms: Some(42000),
            duration_ms: Some(180000),
            title: Some("Song Title".into()),
            artist: Some("Artist Name".into()),
            album: Some("Album Name".into()),
            source_app: Some("Spotify".into()),
        });
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let decoded = ServerMessage::decode(&buf).unwrap();
        assert_eq!(original, decoded);
    }

    #[test]
    fn test_now_playing_some_empty_string_roundtrip() {
        let original = ServerMessage::NowPlaying(NowPlayingState {
            is_playing: false,
            position_ms: None,
            duration_ms: None,
            title: Some("".into()),
            artist: None,
            album: Some("".into()),
            source_app: None,
        });
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let ServerMessage::NowPlaying(decoded) = ServerMessage::decode(&buf).unwrap() else {
            panic!("expected NowPlaying");
        };
        assert_eq!(decoded.title, Some("".to_string()));
        assert_eq!(decoded.artist, None);
        assert_eq!(decoded.album, Some("".to_string()));
        assert_eq!(decoded.source_app, None);
    }

    #[test]
    fn test_korean_now_playing_truncation_preserves_utf8() {
        // "a" followed by 100 Korean characters (3 bytes each = 301 bytes)
        let long_korean = format!("a{}", "\u{AC00}".repeat(100));
        let original = ServerMessage::NowPlaying(NowPlayingState {
            is_playing: true,
            position_ms: Some(1000),
            duration_ms: Some(2000),
            title: Some(long_korean),
            artist: Some("Artist".into()),
            album: None,
            source_app: None,
        });
        let mut buf = BytesMut::new();
        original.encode(&mut buf).unwrap();
        let ServerMessage::NowPlaying(decoded) = ServerMessage::decode(&buf).unwrap() else {
            panic!("expected NowPlaying");
        };
        let decoded_title = decoded.title.unwrap();
        assert!(decoded_title.len() <= 255);
        assert!(decoded_title.starts_with('a'));
        // Verify valid UTF-8 character boundary: string does not contain broken sequences
        assert!(decoded_title.chars().all(|c| c == 'a' || c == '\u{AC00}'));
    }
}
