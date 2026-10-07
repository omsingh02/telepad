#!/usr/bin/env bash
# Installs Telepad for the current user (no root needed), or removes it again.
#
#   ./install.sh               install into ~/.local (the program, its icon and its entry in the applications menu)
#   ./install.sh --uninstall   remove all of that, and the entry that starts Telepad at login
#
# Set PREFIX to install somewhere else, for example PREFIX=/usr/local with sudo. Your paired phones and the PC's
# identity (in ~/.config/telepad) are never touched.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
prefix="${PREFIX:-$HOME/.local}"
bin_dir="$prefix/bin"
apps_dir="$prefix/share/applications"
icons_dir="$prefix/share/icons/hicolor"
doc_dir="$prefix/share/doc/telepad"
sizes="16 24 32 48 64 128 256 512"
autostart_entry="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/telepad.desktop"

refresh_caches() {
  # Best effort: a menu that notices the new entry at once. Neither tool is needed for it to work.
  if command -v update-desktop-database > /dev/null 2>&1; then update-desktop-database "$apps_dir" 2> /dev/null || true; fi
  if command -v gtk-update-icon-cache > /dev/null 2>&1; then gtk-update-icon-cache -q -t "$icons_dir" 2> /dev/null || true; fi
}

# A running Telepad holds the program file and would be replaced under its own feet.
stop_running() {
  if [ -x "$bin_dir/telepad" ]; then "$bin_dir/telepad" --quit 2> /dev/null || true; fi
}

if [ "${1:-}" = "--uninstall" ]; then
  stop_running
  rm -f -- "$bin_dir/telepad" "$apps_dir/telepad.desktop" "$autostart_entry" "$icons_dir/scalable/apps/telepad.svg" \
    "$doc_dir/LICENSE" "$doc_dir/THIRD_PARTY_LICENSES.md"
  rmdir "$doc_dir" 2> /dev/null || true
  for size in $sizes; do rm -f -- "$icons_dir/${size}x${size}/apps/telepad.png"; done
  refresh_caches
  echo "Telepad is removed. Your paired phones are kept in ~/.config/telepad; delete that folder to forget them."
  exit 0
fi

if [ ! -x "$here/telepad" ]; then
  echo "install.sh: the program 'telepad' is not next to this script. Run it from the unpacked folder." >&2
  exit 1
fi

stop_running
install -Dm755 "$here/telepad" "$bin_dir/telepad"
install -Dm644 "$here/telepad.svg" "$icons_dir/scalable/apps/telepad.svg"
install -Dm644 "$here/LICENSE" "$doc_dir/LICENSE"
install -Dm644 "$here/THIRD_PARTY_LICENSES.md" "$doc_dir/THIRD_PARTY_LICENSES.md"
for size in $sizes; do
  install -Dm644 "$here/icons/telepad-$size.png" "$icons_dir/${size}x${size}/apps/telepad.png"
done

# The entry runs the program by its full path, so that it works whether or not ~/.local/bin is on the PATH.
mkdir -p "$apps_dir"
sed "s|^Exec=telepad\$|Exec=$bin_dir/telepad|" "$here/telepad.desktop" > "$apps_dir/telepad.desktop"
chmod 644 "$apps_dir/telepad.desktop"
refresh_caches

echo "Telepad is installed. Open it from your applications menu, or run: $bin_dir/telepad"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "(To run it by typing 'telepad', add $bin_dir to your PATH.)" ;;
esac
echo "Its first start shows a QR code: scan it with the Telepad app on your phone."
