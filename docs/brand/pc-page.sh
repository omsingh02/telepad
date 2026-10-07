#!/usr/bin/env bash
# Takes the picture of the tray app's page (docs/brand/pc-page.png, which the website's "how it works" uses) from the
# real program, so that it can never drift from what the program shows.
#
# The program runs in its own network and host-name space (`unshare`), where the PC is called DESKTOP-PC and has
# the address 192.168.1.20, so the picture shows nothing of the machine it is made on.
#
# Needs: cargo, unshare and ip (iproute2) with unprivileged user namespaces, python3, chromium.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

inner() {
  out="$1"
  mkdir -p "$out/keys" "$out/config" "$out/bin"
  python3 -c "import socket; socket.sethostname('DESKTOP-PC')"
  ip link set lo up
  ip link add dummy0 type dummy
  ip addr add 192.168.1.20/24 dev dummy0
  ip link set dummy0 up
  # Nothing should open a browser of the person's, and no tray is needed.
  printf '#!/usr/bin/env bash\nexit 0\n' > "$out/bin/xdg-open"
  chmod +x "$out/bin/xdg-open"
  export PATH="$out/bin:$PATH" XDG_CONFIG_HOME="$out/config" DBUS_SESSION_BUS_ADDRESS=unix:path=/nonexistent
  "$repo/target/debug/telepad" --key-dir "$out/keys" --port 5000 --no-input --no-open --background > "$out/app.log" 2>&1 &
  app=$!
  for _ in $(seq 1 50); do [ -s "$out/keys/panel.url" ] && break; sleep 0.2; done
  chromium --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --force-dark-mode --window-size=560,980 --virtual-time-budget=4000 \
    --screenshot="$out/pc-page.png" "$(cat "$out/keys/panel.url")" > "$out/chromium.log" 2>&1
  kill -TERM "$app" 2> /dev/null || true
  wait "$app" 2> /dev/null || true
}

if [ "${1:-}" = "--inner" ]; then
  inner "$2"
else
  (cd "$repo" && cargo build -p telepad-tray)
  work="$(mktemp -d)"
  cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
  trap cleanup EXIT
  unshare --map-root-user --net --uts "$0" --inner "$work"
  optipng -quiet -o4 "$work/pc-page.png"
  cp "$work/pc-page.png" "$here/pc-page.png"
  echo "Wrote $here/pc-page.png"
fi
