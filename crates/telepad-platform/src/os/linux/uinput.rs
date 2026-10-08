//! Thin, dependency-light wrapper over the kernel's `/dev/uinput` virtual
//! input device interface (`linux/uinput.h`).

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

pub const EV_SYN: u16 = 0x00;
pub const EV_KEY: u16 = 0x01;
pub const EV_REL: u16 = 0x02;
pub const SYN_REPORT: u16 = 0;
pub const REL_X: u16 = 0x00;
pub const REL_Y: u16 = 0x01;
pub const REL_WHEEL: u16 = 0x08;
pub const REL_WHEEL_HI_RES: u16 = 0x0b;

const BUS_VIRTUAL: u16 = 0x06;
pub const UINPUT_MAX_NAME_SIZE: usize = 80;

/// `(type, code, value)` as in `struct input_event`, minus the timestamp (the
/// kernel stamps events itself).
pub type RawEvent = (u16, u16, i32);

pub const fn syn() -> RawEvent {
    (EV_SYN, SYN_REPORT, 0)
}

#[repr(C)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
struct UinputSetup {
    id: InputId,
    name: [u8; UINPUT_MAX_NAME_SIZE],
    ff_effects_max: u32,
}

mod ioctls {
    use super::UinputSetup;
    use nix::{ioctl_none, ioctl_read_buf, ioctl_write_int, ioctl_write_ptr};

    const UINPUT_IOCTL_BASE: u8 = b'U';
    ioctl_write_int!(ui_set_evbit, UINPUT_IOCTL_BASE, 100);
    ioctl_write_int!(ui_set_keybit, UINPUT_IOCTL_BASE, 101);
    ioctl_write_int!(ui_set_relbit, UINPUT_IOCTL_BASE, 102);
    ioctl_write_ptr!(ui_dev_setup, UINPUT_IOCTL_BASE, 3, UinputSetup);
    ioctl_none!(ui_dev_create, UINPUT_IOCTL_BASE, 1);
    ioctl_none!(ui_dev_destroy, UINPUT_IOCTL_BASE, 2);
    ioctl_read_buf!(ui_get_sysname, UINPUT_IOCTL_BASE, 44, u8);
}

/// A virtual input device. Destroyed (and any keys still held released by the
/// kernel) when dropped.
pub struct VirtualDevice {
    file: File,
}

impl VirtualDevice {
    /// Creates a device advertising exactly the given key/button codes and
    /// relative axes.
    pub fn create(name: &str, keys: &[u16], rel_axes: &[u16]) -> io::Result<Self> {
        let file = open_uinput()?;
        let fd = file.as_raw_fd();

        // SAFETY: `fd` is a valid, open uinput descriptor owned by `file` for
        // the duration of every call below.
        unsafe {
            if !keys.is_empty() {
                ioctls::ui_set_evbit(fd, EV_KEY.into())?;
                for &key in keys {
                    ioctls::ui_set_keybit(fd, key.into())?;
                }
            }
            if !rel_axes.is_empty() {
                ioctls::ui_set_evbit(fd, EV_REL.into())?;
                for &axis in rel_axes {
                    ioctls::ui_set_relbit(fd, axis.into())?;
                }
            }

            let mut setup = UinputSetup {
                id: InputId {
                    bustype: BUS_VIRTUAL,
                    vendor: 0,
                    product: 0,
                    version: 1,
                },
                name: [0; UINPUT_MAX_NAME_SIZE],
                ff_effects_max: 0,
            };
            let name = name.as_bytes();
            // Keep the final byte as the NUL terminator.
            let len = name.len().min(UINPUT_MAX_NAME_SIZE - 1);
            setup.name[..len].copy_from_slice(&name[..len]);

            ioctls::ui_dev_setup(fd, &setup)?;
            ioctls::ui_dev_create(fd)?;
        }
        Ok(Self { file })
    }

    /// Writes all `events` with a single `write(2)`, which the kernel applies
    /// atomically with respect to other writers.
    pub fn emit(&self, events: &[RawEvent]) -> io::Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        let buf = encode_events(events);
        let written = (&self.file).write(&buf)?;
        if written != buf.len() {
            return Err(io::Error::new(
                io::ErrorKind::WriteZero,
                format!("short uinput write: {written} of {} bytes", buf.len()),
            ));
        }
        Ok(())
    }

    /// The device's sysfs name (e.g. `input42`), used by tests to locate the
    /// device's `/sys` and `/dev/input` entries.
    #[cfg(test)]
    pub fn sysname(&self) -> io::Result<String> {
        let mut buf = [0u8; 64];
        // SAFETY: valid fd; the ioctl writes at most `buf.len()` bytes.
        unsafe { ioctls::ui_get_sysname(self.file.as_raw_fd(), &mut buf)? };
        let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
        Ok(String::from_utf8_lossy(&buf[..end]).into_owned())
    }
}

impl Drop for VirtualDevice {
    fn drop(&mut self) {
        // SAFETY: valid fd. Failure only means the kernel already tore it down.
        unsafe {
            let _ = ioctls::ui_dev_destroy(self.file.as_raw_fd());
        }
    }
}

/// Serialises events into the byte layout of `struct input_event`, which is
/// exactly what the kernel expects from `write(2)` on a uinput descriptor.
///
/// Fields are written individually at their real offsets into a zeroed buffer,
/// so the timestamp (which the kernel overwrites) and any padding are zero on
/// every architecture.
pub(crate) fn encode_events(events: &[RawEvent]) -> Vec<u8> {
    use libc::input_event;
    use std::mem::{offset_of, size_of};

    let record = size_of::<input_event>();
    let type_at = offset_of!(input_event, type_);
    let code_at = offset_of!(input_event, code);
    let value_at = offset_of!(input_event, value);

    let mut buf = vec![0u8; record * events.len()];
    for (chunk, &(type_, code, value)) in buf.chunks_exact_mut(record).zip(events) {
        chunk[type_at..type_at + 2].copy_from_slice(&type_.to_ne_bytes());
        chunk[code_at..code_at + 2].copy_from_slice(&code.to_ne_bytes());
        chunk[value_at..value_at + 4].copy_from_slice(&value.to_ne_bytes());
    }
    buf
}

/// Whether `/dev/uinput` can be opened for writing, without making a device: what Telepad has to be allowed to do
/// before it can type.
pub(crate) fn can_open_uinput() -> io::Result<()> {
    open_uinput().map(drop)
}

fn open_uinput() -> io::Result<File> {
    let mut last_err = None;
    for path in ["/dev/uinput", "/dev/input/uinput"] {
        match OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(Path::new(path))
        {
            Ok(file) => return Ok(file),
            // Prefer reporting a permission problem over a missing legacy path.
            Err(err) if err.kind() == io::ErrorKind::NotFound && last_err.is_none() => {
                last_err = Some(err);
            }
            Err(err) if err.kind() == io::ErrorKind::NotFound => {}
            Err(err) => return Err(err),
        }
    }
    Err(last_err.unwrap_or_else(|| io::Error::from(io::ErrorKind::NotFound)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn struct_layouts_match_the_kernel_abi() {
        // struct input_id = 4 x __u16
        assert_eq!(std::mem::size_of::<InputId>(), 8);
        // struct uinput_setup = input_id + char[80] + __u32
        assert_eq!(std::mem::size_of::<UinputSetup>(), 8 + 80 + 4);
        assert_eq!(std::mem::align_of::<UinputSetup>(), 4);
    }

    #[test]
    fn syn_is_a_report_marker() {
        assert_eq!(syn(), (EV_SYN, SYN_REPORT, 0));
    }

    #[test]
    fn events_serialise_to_consecutive_input_event_structs() {
        let events = [(EV_REL, REL_X, -5), (EV_KEY, 0x110, 1), syn()];
        let bytes = encode_events(&events);
        let size = std::mem::size_of::<libc::input_event>();
        assert_eq!(bytes.len(), size * events.len());

        for (i, expected) in events.iter().enumerate() {
            // SAFETY: the slice holds one complete input_event at this offset.
            let ev: libc::input_event =
                unsafe { std::ptr::read_unaligned(bytes[i * size..].as_ptr().cast()) };
            assert_eq!((ev.type_, ev.code, ev.value), *expected);
            // The kernel stamps events itself; we leave the time zeroed.
            assert_eq!((ev.time.tv_sec, ev.time.tv_usec), (0, 0));
        }
    }

    #[test]
    fn empty_batch_encodes_to_nothing() {
        assert!(encode_events(&[]).is_empty());
    }
}
