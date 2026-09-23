#!/usr/bin/env python3
"""
android_agent.py — a single, dependency-free CLI for driving and inspecting an
Android device or emulator over adb/uiautomator.

Built to replace screenshot -> guess coordinates -> tap -> screenshot loops with
deterministic, structural calls. See ../SKILL.md for the intended workflow.

Requires: Python 3.8+, `adb` reachable (on PATH, or in the default Android SDK
platform-tools location). No third-party packages.
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET

# State file lives next to this script, not in whatever CWD the caller uses.
STATE_FILE = Path(__file__).resolve().parent / ".android_agent_state.json"
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")

_ADB = None  # resolved lazily, optionally overridden by --adb-path


# --------------------------------------------------------------------------
# adb plumbing
# --------------------------------------------------------------------------

def run(cmd, **kw):
    """Run a command, returning (returncode, stdout_bytes, stderr_bytes)."""
    timeout = kw.pop("timeout", 15)
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL, timeout=timeout, **kw)
        return proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired:
        return 1, b"", b"command timed out"


def find_adb():
    candidates = []
    if os.name == "nt":
        candidates.append(os.path.expandvars(
            r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"))
    else:
        candidates.append(os.path.expanduser(
            "~/Library/Android/sdk/platform-tools/adb"))
        candidates.append(os.path.expanduser(
            "~/Android/Sdk/platform-tools/adb"))
    for c in candidates:
        if os.path.isfile(c):
            return c
    p = shutil.which("adb")
    if p:
        return p
    print("ERROR: adb not found on PATH or in the default SDK location. "
          "Pass --adb-path <path> or add platform-tools to PATH.", file=sys.stderr)
    sys.exit(3)


def adb_base(serial=None):
    global _ADB
    if _ADB is None:
        _ADB = find_adb()
    base = [_ADB]
    if serial:
        base += ["-s", serial]
    return base


def list_devices():
    rc, out, err = run([_ADB or find_adb(), "devices"])
    lines = out.decode(errors="replace").splitlines()[1:]
    devices = []
    for line in lines:
        line = line.strip()
        if not line or "\t" not in line:
            continue
        serial, state = line.split("\t", 1)
        devices.append((serial, state))
    return devices


def resolve_serial(args):
    if getattr(args, "serial", None):
        return args.serial
    devices = [d for d, s in list_devices() if s == "device"]
    if len(devices) == 1:
        return devices[0]
    if not devices:
        print("ERROR: no adb devices in 'device' state. Run the 'devices' "
              "command to see current state (unauthorized/offline, etc).",
              file=sys.stderr)
        sys.exit(2)
    print(f"ERROR: {len(devices)} devices connected, pass --serial. "
          f"Options: {devices}", file=sys.stderr)
    sys.exit(2)


def get_screen_size(serial):
    rc, out, err = run(adb_base(serial) + ["shell", "wm", "size"])
    m = re.search(r"(\d+)x(\d+)", out.decode(errors="replace"))
    if m:
        return int(m.group(1)), int(m.group(2))
    return 1080, 2400


# --------------------------------------------------------------------------
# devices / info / screenshot
# --------------------------------------------------------------------------

def cmd_devices(args):
    devices = list_devices()
    if not devices:
        print("No devices/emulators detected.")
        return
    for serial, state in devices:
        print(f"{serial}\t{state}")


def cmd_info(args):
    serial = resolve_serial(args)
    base = adb_base(serial)

    # Device properties
    def prop(name):
        _, out, _ = run(base + ["shell", "getprop", name])
        return out.decode(errors="replace").strip()

    brand = prop("ro.product.brand")
    model = prop("ro.product.model")
    device = prop("ro.product.device")
    os_rel = prop("ro.build.version.release")
    sdk_ver = prop("ro.build.version.sdk")

    # Display
    w, h = get_screen_size(serial)
    _, density_out, _ = run(base + ["shell", "wm", "density"])
    density_match = re.search(r"\d+", density_out.decode(errors="replace"))
    density = density_match.group(0) if density_match else "unknown"

    # Battery
    _, batt_out, _ = run(base + ["shell", "dumpsys", "battery"])
    batt_text = batt_out.decode(errors="replace")
    level_m = re.search(r"level:\s*(\d+)", batt_text)
    temp_m = re.search(r"temperature:\s*(\d+)", batt_text)
    usb_m = re.search(r"USB powered:\s*(\w+)", batt_text)
    batt_level = f"{level_m.group(1)}%" if level_m else "unknown"
    batt_temp = f"{float(temp_m.group(1)) / 10:.1f}°C" if temp_m else "unknown"
    charging = "Yes (USB)" if usb_m and usb_m.group(1).lower() == "true" else "No"

    # Network / WiFi IP
    _, ip_out, _ = run(base + ["shell", "ip", "-f", "inet", "addr", "show", "wlan0"])
    ip_text = ip_out.decode(errors="replace")
    ip_match = re.search(r"inet\s+([\d\.]+/\d+)", ip_text)
    wifi_ip = ip_match.group(1) if ip_match else "No WiFi IPv4"

    # Software keyboard
    _, ime_out, _ = run(base + ["shell", "dumpsys", "input_method"])
    ime_shown = "Visible" if "mInputShown=true" in ime_out.decode(errors="replace") else "Hidden"

    # Focus
    _, win_out, _ = run(base + ["shell", "dumpsys", "window"])
    focus = "Unknown"
    for line in win_out.decode(errors="replace").splitlines():
        if "mCurrentFocus" in line:
            focus = line.strip().replace("mCurrentFocus=", "")
            break

    print(f"Device:       {brand} {model} ({device})")
    print(f"Android:      v{os_rel} (API {sdk_ver})")
    print(f"Screen:       {w}x{h} ({density} dpi)")
    print(f"Battery:      {batt_level} (Temp: {batt_temp}, Charging: {charging})")
    print(f"WiFi IP:      {wifi_ip}")
    print(f"Keyboard:     {ime_shown}")
    print(f"Focus:        {focus}")


def cmd_screenshot(args):
    serial = resolve_serial(args)
    # Single adb call, no temp files — exec-out streams raw PNG bytes to stdout.
    # This avoids the 3-call round-trip (shell screencap, pull, rm) that the
    # old approach used, and sidesteps Windows newline-translation corruption.
    cmd = adb_base(serial) + ["exec-out", "screencap", "-p"]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=10)
        if proc.returncode != 0 or len(proc.stdout) < 100:
            # Fallback for older devices / vendors that don't support exec-out
            device_tmp = "/sdcard/_screencap.png"
            run(adb_base(serial) + ["shell", "screencap", "-p", device_tmp])
            rc, out, err = run(adb_base(serial) + ["pull", device_tmp, args.out])
            run(adb_base(serial) + ["shell", "rm", "-f", device_tmp])
            if rc != 0 or not Path(args.out).exists():
                print(f"ERROR: screenshot failed. stderr: {err.decode(errors='replace')}",
                      file=sys.stderr)
                sys.exit(1)
        else:
            Path(args.out).write_bytes(proc.stdout)
    except subprocess.TimeoutExpired:
        print("ERROR: screenshot timed out.", file=sys.stderr)
        sys.exit(1)
    abs_path = str(Path(args.out).resolve())
    print(f"Wrote {args.out} ({Path(args.out).stat().st_size} bytes) -> {abs_path}")


# --------------------------------------------------------------------------
# UI hierarchy: dump / find / wait-element / tap / scroll
# --------------------------------------------------------------------------

def get_ui_tree_xml(serial):
    device_path = "/sdcard/_android_agent_dump.xml"
    rc, out, err = run(adb_base(serial) + ["shell", "uiautomator", "dump", device_path])
    if rc != 0:
        raise RuntimeError(f"uiautomator dump failed: {err.decode(errors='replace')}")
    import tempfile
    with tempfile.NamedTemporaryFile(delete=False, suffix=".xml") as tmp:
        tmp_path = tmp.name
    try:
        rc, out, err = run(adb_base(serial) + ["pull", device_path, tmp_path])
        if rc != 0 or not Path(tmp_path).exists():
            raise RuntimeError(f"could not pull UI dump: {err.decode(errors='replace')}")
        content = Path(tmp_path).read_text(encoding="utf-8", errors="replace")
    finally:
        run(adb_base(serial) + ["shell", "rm", "-f", device_path])
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
    return content


def parse_elements(xml_text):
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        raise RuntimeError(f"could not parse UI dump XML: {e}")
    elements = []
    for node in root.iter("node"):
        bounds = node.get("bounds", "")
        m = BOUNDS_RE.match(bounds)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        elements.append({
            "text": node.get("text", ""),
            "resource_id": node.get("resource-id", ""),
            "content_desc": node.get("content-desc", ""),
            "class": node.get("class", ""),
            "clickable": node.get("clickable") == "true",
            "enabled": node.get("enabled") == "true",
            "bounds": [x1, y1, x2, y2],
            "center": [(x1 + x2) // 2, (y1 + y2) // 2],
        })
    return elements


def _norm(s):
    return (s or "").strip().lower()


def match_elements(elements, text=None, resource_id=None, desc=None, clickable_only=True):
    exact = []
    partial = []
    for el in elements:
        if clickable_only and not el["clickable"]:
            continue
        if text is not None:
            q = _norm(text)
            t = _norm(el["text"])
            d = _norm(el["content_desc"])
            if q not in t and q not in d:
                continue
            if q == t or q == d:
                exact.append(el)
            else:
                partial.append(el)
            continue
        if resource_id is not None and resource_id not in el["resource_id"]:
            continue
        if desc is not None and _norm(desc) not in _norm(el["content_desc"]):
            continue
        exact.append(el)
    return exact + partial


def cmd_dump(args):
    serial = resolve_serial(args)
    elements = parse_elements(get_ui_tree_xml(serial))
    print(json.dumps(elements, indent=2, ensure_ascii=False))
    if not elements:
        print("(empty hierarchy — likely a custom-drawn surface; "
              "use `screenshot` instead)", file=sys.stderr)


def cmd_find(args):
    serial = resolve_serial(args)
    elements = parse_elements(get_ui_tree_xml(serial))
    matches = match_elements(elements, args.text, args.id, args.desc,
                              clickable_only=not args.any)
    if not matches and not args.any:
        matches = match_elements(elements, args.text, args.id, args.desc,
                                 clickable_only=False)
    print(json.dumps(matches, indent=2, ensure_ascii=False))
    if not matches:
        sys.exit(1)


def cmd_wait_element(args):
    serial = resolve_serial(args)
    deadline = time.time() + args.timeout
    last_matches = []

    while time.time() < deadline:
        try:
            elements = parse_elements(get_ui_tree_xml(serial))
            matches = match_elements(elements, args.text, args.id, args.desc,
                                     clickable_only=not args.any)
            if not matches and not args.any:
                # Fallback to non-clickable in Compose if no clickable match
                matches = match_elements(elements, args.text, args.id, args.desc,
                                         clickable_only=False)
            last_matches = matches

            if args.gone:
                if not matches:
                    print("Element is gone.")
                    return
            else:
                if matches:
                    matched = matches[0]
                    if args.tap:
                        x, y = matched["center"]
                        run(adb_base(serial) + ["shell", "input", "tap", str(x), str(y)])
                        print(f"Matched and tapped ({x}, {y}): {matched.get('text') or matched.get('content_desc')}")
                    else:
                        print(json.dumps(matched, indent=2, ensure_ascii=False))
                    return
        except Exception:
            pass
        time.sleep(args.poll)

    target_desc = args.text or args.id or args.desc or "element"
    if args.gone:
        print(f"ERROR: Element matching '{target_desc}' did not disappear within {args.timeout}s.", file=sys.stderr)
    else:
        print(f"ERROR: Element matching '{target_desc}' not found within {args.timeout}s.", file=sys.stderr)
        try:
            elements = parse_elements(get_ui_tree_xml(serial))
            labels = sorted({
                el["text"] or el["content_desc"] or el["resource_id"]
                for el in elements if (el["text"] or el["content_desc"])
            })
            print("Visible on screen:", file=sys.stderr)
            for lbl in labels[:20]:
                print(f"  - {lbl}", file=sys.stderr)
        except Exception:
            pass
    sys.exit(1)


def cmd_assert_text(args):
    serial = resolve_serial(args)
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        try:
            elements = parse_elements(get_ui_tree_xml(serial))
            matches = match_elements(elements, text=args.text, clickable_only=False)
            if matches:
                print(f"Assert OK: '{args.text}' found on screen")
                return
        except Exception:
            pass
        time.sleep(0.5)

    print(f"Assert FAILED: '{args.text}' not found on screen within {args.timeout}s.", file=sys.stderr)
    try:
        elements = parse_elements(get_ui_tree_xml(serial))
        labels = sorted({
            el["text"] or el["content_desc"] or el["resource_id"]
            for el in elements if (el["text"] or el["content_desc"])
        })
        print("Visible on screen:", file=sys.stderr)
        for lbl in labels[:25]:
            print(f"  - {lbl}", file=sys.stderr)
    except Exception:
        pass
    sys.exit(1)


def cmd_tap(args):
    serial = resolve_serial(args)
    target_x = args.x
    target_y = args.y
    text_query = args.text

    # Support positional coords: e.g. 'tap 540 664' or 'tap "Enter IP"'
    if getattr(args, "coords", None):
        if len(args.coords) == 2 and all(p.lstrip("-").isdigit() for p in args.coords):
            target_x = int(args.coords[0])
            target_y = int(args.coords[1])
        elif len(args.coords) >= 1 and text_query is None:
            text_query = " ".join(args.coords)

    if target_x is not None and target_y is not None:
        x, y = target_x, target_y
    else:
        elements = parse_elements(get_ui_tree_xml(serial))
        # First try clickable elements
        matches = match_elements(elements, text_query, args.id, args.desc, clickable_only=True)
        # Fall back to any matching element if none marked clickable (standard in Compose)
        if not matches:
            matches = match_elements(elements, text_query, args.id, args.desc, clickable_only=False)

        if not matches:
            available = sorted({
                el["text"] or el["content_desc"] or el["resource_id"] or el["class"]
                for el in elements if (el["text"] or el["content_desc"])
            })
            print("ERROR: no element matched. Visible labels currently on screen:", file=sys.stderr)
            for label in available[:20]:
                print(f"  - {label}", file=sys.stderr)
            sys.exit(1)
        if len(matches) > 1 and args.index >= len(matches):
            print(f"ERROR: --index {args.index} out of range, {len(matches)} matches found.", file=sys.stderr)
            sys.exit(1)
        if len(matches) > 1:
            print(f"NOTE: {len(matches)} elements matched, using index {args.index}.", file=sys.stderr)
        x, y = matches[args.index]["center"]
    run(adb_base(serial) + ["shell", "input", "tap", str(x), str(y)])
    print(f"Tapped ({x}, {y})")


def cmd_long_press(args):
    serial = resolve_serial(args)
    target_x = args.x
    target_y = args.y
    text_query = args.text

    if getattr(args, "coords", None):
        if len(args.coords) == 2 and all(p.lstrip("-").isdigit() for p in args.coords):
            target_x = int(args.coords[0])
            target_y = int(args.coords[1])
        elif len(args.coords) >= 1 and text_query is None:
            text_query = " ".join(args.coords)

    if target_x is not None and target_y is not None:
        x, y = target_x, target_y
    else:
        elements = parse_elements(get_ui_tree_xml(serial))
        matches = match_elements(elements, text_query, args.id, args.desc, clickable_only=False)
        if not matches:
            available = sorted({
                el["text"] or el["content_desc"] or el["resource_id"] or el["class"]
                for el in elements if (el["text"] or el["content_desc"])
            })
            print("ERROR: no element matched. Visible labels currently on screen:", file=sys.stderr)
            for label in available[:20]:
                print(f"  - {label}", file=sys.stderr)
            sys.exit(1)
        if len(matches) > 1 and args.index >= len(matches):
            print(f"ERROR: --index {args.index} out of range, {len(matches)} matches found.", file=sys.stderr)
            sys.exit(1)
        if len(matches) > 1:
            print(f"NOTE: {len(matches)} elements matched, using index {args.index}.", file=sys.stderr)
        x, y = matches[args.index]["center"]

    duration = getattr(args, "duration", 1000)
    run(adb_base(serial) + ["shell", "input", "swipe", str(x), str(y), str(x), str(y), str(duration)])
    print(f"Long-pressed ({x}, {y}) for {duration}ms")


def cmd_type(args):
    serial = resolve_serial(args)
    if getattr(args, "tap_text", None) or getattr(args, "tap_id", None):
        elements = parse_elements(get_ui_tree_xml(serial))
        matches = match_elements(elements, text=args.tap_text, resource_id=args.tap_id, clickable_only=False)
        if matches:
            x, y = matches[0]["center"]
            run(adb_base(serial) + ["shell", "input", "tap", str(x), str(y)])
            time.sleep(0.3)
    if args.clear:
        # Select all (Ctrl+A) then Delete — one key combination instead of individual backspaces.
        # KEYCODE_CTRL_LEFT=113, KEYCODE_A=29, KEYCODE_DEL=67.
        rc, _, _ = run(adb_base(serial) + ["shell", "input", "keycombination", "113", "29"], timeout=3)
        if rc == 0:
            time.sleep(0.1)
            run(adb_base(serial) + ["shell", "input", "keyevent", "67"], timeout=3)
        else:
            # Fallback: move to end (123) and send repeated DEL (67) in single adb command
            run(adb_base(serial) + ["shell", "input", "keyevent", "123"], timeout=3)
            del_keys = ["67"] * getattr(args, "clear_count", 35)
            run(adb_base(serial) + ["shell", "input", "keyevent"] + del_keys, timeout=5)
        time.sleep(0.2)
    if args.text:
        # Escape spaces and shell-sensitive characters for `adb shell input text`
        escaped = args.text.replace(" ", "%s")
        for ch in ["\\", '"', "'", "&", ";", "(", ")", "<", ">", "|", "*", "?", "$", "`",
                   "#", "!", "{", "}", "~", "^"]:
            escaped = escaped.replace(ch, f"\\{ch}")
        run(adb_base(serial) + ["shell", "input", "text", escaped])
        print(f"Typed '{args.text}'")
    if args.enter:
        time.sleep(0.2)
        run(adb_base(serial) + ["shell", "input", "keyevent", "66"])  # KEYCODE_ENTER
    if getattr(args, "hide_keyboard", False):
        time.sleep(0.2)
        _, ime_out, _ = run(adb_base(serial) + ["shell", "dumpsys", "input_method"])
        if "mInputShown=true" in ime_out.decode(errors="replace"):
            run(adb_base(serial) + ["shell", "input", "keyevent", "111"])  # KEYCODE_ESCAPE


def cmd_swipe(args):
    serial = resolve_serial(args)
    run(adb_base(serial) + ["shell", "input", "swipe",
                             str(args.x1), str(args.y1),
                             str(args.x2), str(args.y2), str(args.duration)])
    print(f"Swiped ({args.x1},{args.y1}) -> ({args.x2},{args.y2})")


def cmd_scroll(args):
    serial = resolve_serial(args)
    w, h = get_screen_size(serial)
    dist = getattr(args, "distance", 0.45)
    dur = getattr(args, "duration", 300)
    direction = args.direction.lower()

    if direction == "down":
        # Swipe from lower middle towards top middle (scrolls down)
        x1, y1 = w // 2, int(h * 0.75)
        x2, y2 = w // 2, int(h * (0.75 - dist))
    elif direction == "up":
        # Swipe from upper middle towards bottom middle (scrolls up)
        x1, y1 = w // 2, int(h * 0.25)
        x2, y2 = w // 2, int(h * (0.25 + dist))
    elif direction == "right":
        # Swipe right to left (scrolls right)
        x1, y1 = int(w * 0.8), h // 2
        x2, y2 = int(w * (0.8 - dist)), h // 2
    elif direction == "left":
        # Swipe left to right (scrolls left)
        x1, y1 = int(w * 0.2), h // 2
        x2, y2 = int(w * (0.2 + dist)), h // 2
    else:
        print(f"ERROR: unknown direction '{direction}'. Choose down, up, left, or right.", file=sys.stderr)
        sys.exit(1)

    run(adb_base(serial) + ["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(dur)])
    print(f"Scrolled {direction}")


def cmd_scroll_into_view(args):
    serial = resolve_serial(args)
    w, h = get_screen_size(serial)
    max_swipes = args.max_swipes
    direction = args.direction.lower()

    for i in range(max_swipes + 1):
        try:
            elements = parse_elements(get_ui_tree_xml(serial))
            matches = match_elements(elements, args.text, args.id, args.desc, clickable_only=False)
            if matches:
                matched = matches[0]
                if args.tap:
                    x, y = matched["center"]
                    run(adb_base(serial) + ["shell", "input", "tap", str(x), str(y)])
                    print(f"Found and tapped ({x}, {y}): {matched.get('text') or matched.get('content_desc')}")
                else:
                    print(json.dumps(matched, indent=2, ensure_ascii=False))
                return
        except Exception:
            pass

        if i < max_swipes:
            # Scroll one step
            if direction == "down":
                x1, y1 = w // 2, int(h * 0.75)
                x2, y2 = w // 2, int(h * 0.35)
            else:
                x1, y1 = w // 2, int(h * 0.35)
                x2, y2 = w // 2, int(h * 0.75)
            run(adb_base(serial) + ["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "350"])
            time.sleep(0.4)

    target_desc = args.text or args.id or args.desc or "element"
    print(f"ERROR: Could not scroll '{target_desc}' into view after {max_swipes} swipes.", file=sys.stderr)
    sys.exit(1)


def cmd_key(args):
    serial = resolve_serial(args)
    run(adb_base(serial) + ["shell", "input", "keyevent", args.keycode])
    print(f"Sent keyevent {args.keycode}")


def cmd_back(args):
    serial = resolve_serial(args)
    run(adb_base(serial) + ["shell", "input", "keyevent", "4"])
    print("Pressed Back")


def cmd_home(args):
    serial = resolve_serial(args)
    run(adb_base(serial) + ["shell", "input", "keyevent", "3"])
    print("Pressed Home")


def cmd_current(args):
    serial = resolve_serial(args)
    rc, out, err = run(adb_base(serial) + ["shell", "dumpsys", "window"])
    text = out.decode(errors="replace")
    for line in text.splitlines():
        if "mCurrentFocus" in line or "mFocusedApp" in line:
            print(line.strip())


# --------------------------------------------------------------------------
# wake/unlock, launch, install
# --------------------------------------------------------------------------

def cmd_wait_awake(args):
    serial = resolve_serial(args)
    if getattr(args, "keep_awake", False) or getattr(args, "timeout_mins", None):
        mins = args.timeout_mins or 30
        ms = int(mins * 60 * 1000)
        run(adb_base(serial) + ["shell", "settings", "put", "system", "screen_off_timeout", str(ms)])
        run(adb_base(serial) + ["shell", "settings", "put", "global", "stay_on_while_plugged_in", "7"])
        print(f"Configured keep-awake ({mins}m screen timeout, stay on when plugged in)")

    deadline = time.time() + args.timeout
    while time.time() < deadline:
        rc, out, err = run(adb_base(serial) + ["shell", "dumpsys", "power"])
        m = re.search(r"mWakefulness=(\w+)", out.decode(errors="replace"))
        state = m.group(1) if m else "Unknown"
        if state == "Awake":
            if args.unlock:
                run(adb_base(serial) + ["shell", "wm", "dismiss-keyguard"])
                run(adb_base(serial) + ["shell", "input", "keyevent", "82"])  # KEYCODE_MENU
                w, h = get_screen_size(serial)
                run(adb_base(serial) + ["shell", "input", "swipe",
                                        str(w // 2), str(int(h * 0.8)),
                                        str(w // 2), str(int(h * 0.2)), "300"])
            print("Awake")
            return
        run(adb_base(serial) + ["shell", "input", "keyevent", "224"])  # KEYCODE_WAKEUP
        time.sleep(0.5)
    print(f"ERROR: device did not reach Awake state within {args.timeout}s "
          f"(last state: {state})", file=sys.stderr)
    sys.exit(1)


def build_component(package, activity):
    if "/" in activity:
        return activity  # already fully qualified, e.g. pkg/pkg.Activity
    if "." not in activity:
        activity = "." + activity
    return f"{package}/{activity}"


def cmd_launch(args):
    serial = resolve_serial(args)
    if args.clear:
        run(adb_base(serial) + ["shell", "pm", "clear", args.package])
    run(adb_base(serial) + ["shell", "am", "force-stop", args.package])
    component = build_component(args.package, args.activity)
    rc, out, err = run(adb_base(serial) + ["shell", "am", "start", "-n", component])
    if rc != 0:
        print(f"ERROR launching {component}: {err.decode(errors='replace')}",
              file=sys.stderr)
        sys.exit(1)
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        rc, out, err = run(adb_base(serial) + ["shell", "dumpsys", "window"])
        text = out.decode(errors="replace")
        for line in text.splitlines():
            if ("mCurrentFocus" in line or "mFocusedApp" in line) and args.package in line:
                print(f"Launched and focused: {component}")
                return
        time.sleep(0.3)
    print(f"WARNING: launched {component} but could not confirm focus within "
          f"{args.timeout}s (app may still be starting)", file=sys.stderr)


def cmd_install(args):
    serial = resolve_serial(args)
    apk_path = Path(args.apk)
    if not apk_path.is_file():
        print(f"ERROR: APK file not found at '{apk_path}'.", file=sys.stderr)
        sys.exit(1)

    cmd = adb_base(serial) + ["install", "-r", "-d", "-g", str(apk_path)]
    print(f"Installing {apk_path.name}...")
    rc, out, err = run(cmd, timeout=args.timeout)
    out_str = (out + err).decode(errors="replace")
    if "Success" in out_str:
        print(f"Successfully installed {apk_path.name}")
    else:
        print(f"ERROR installing APK: {out_str.strip()}", file=sys.stderr)
        sys.exit(1)

    if getattr(args, "launch", None):
        pkg = getattr(args, "package", None)
        if not pkg:
            # Try parsing package from aapt or manifest if available, or require --package
            print("NOTE: pass --package when using --launch to specify package name.", file=sys.stderr)
        else:
            args.activity = args.launch
            args.clear = False
            args.timeout = 8.0
            cmd_launch(args)


# --------------------------------------------------------------------------
# logcat capture & dump
# --------------------------------------------------------------------------

def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text())
        except json.JSONDecodeError:
            return {}
    return {}


def save_state(state):
    STATE_FILE.write_text(json.dumps(state))


def cmd_logcat_start(args):
    serial = resolve_serial(args)
    run(adb_base(serial) + ["logcat", "-c"])  # clear buffer
    cmd = adb_base(serial) + ["logcat", "-v", "time"]
    if args.tags:
        for t in args.tags:
            cmd.append(f"{t}:V")
        cmd.append("*:S")
    out_f = open(args.out, "wb")
    proc = subprocess.Popen(cmd, stdout=out_f, stderr=subprocess.STDOUT)
    state = load_state()
    state["logcat_pid"] = proc.pid
    state["logcat_out"] = str(Path(args.out).resolve())
    save_state(state)
    print(f"logcat capturing to {args.out} (pid {proc.pid})")


def cmd_logcat_stop(args):
    state = load_state()
    pid = state.get("logcat_pid")
    if not pid:
        print("No tracked logcat capture (nothing started with logcat-start, "
              "or it was already stopped).", file=sys.stderr)
        sys.exit(1)
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            os.kill(pid, 15)
    except (ProcessLookupError, OSError) as e:
        print(f"WARNING: could not kill pid {pid}: {e}", file=sys.stderr)
    out_path = state.get("logcat_out")
    print(f"Stopped. Log at {out_path}")
    state.pop("logcat_pid", None)
    save_state(state)


def cmd_logcat(args):
    serial = resolve_serial(args)
    if args.clear:
        run(adb_base(serial) + ["logcat", "-c"])
        print("Logcat buffer cleared.")
        return

    cmd = adb_base(serial) + ["logcat", "-d", "-v", "time"]
    if args.tag:
        cmd.extend([f"{args.tag}:V", "*:S"])
    rc, out, err = run(cmd, timeout=10)
    lines = out.decode(errors="replace").splitlines()
    if args.grep:
        pat = re.compile(args.grep, re.IGNORECASE)
        lines = [l for l in lines if pat.search(l)]
    for line in lines[-args.lines:]:
        print(line)


# --------------------------------------------------------------------------
# JAVA_HOME resolution + one-time gradle.properties pin
# --------------------------------------------------------------------------

def probe_java_homes():
    candidates = []
    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        candidates.append(env_home)
    if os.name == "nt":
        patterns = [
            r"C:\Program Files\Android\Android Studio\jbr",
            r"C:\Program Files\Android\Android Studio\jre",
            r"C:\Program Files\JetBrains\*\jbr",
            r"C:\Program Files\Java\*",
            r"C:\Program Files\Eclipse Adoptium\*",
        ]
    elif sys.platform == "darwin":
        patterns = [
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
            os.path.expanduser("~/Library/Java/JavaVirtualMachines/*/Contents/Home"),
        ]
    else:
        patterns = [
            os.path.expanduser("~/android-studio/jbr"),
            "/usr/lib/jvm/*",
        ]
    for pattern in patterns:
        candidates.extend(glob.glob(pattern))
    seen, result = set(), []
    for c in candidates:
        c = os.path.normpath(c)
        if c not in seen and os.path.isdir(c):
            seen.add(c)
            result.append(c)
    return result


def validate_java_home(path):
    exe = "java.exe" if os.name == "nt" else "java"
    java_bin = os.path.join(path, "bin", exe)
    if not os.path.isfile(java_bin):
        return None
    rc, out, err = run([java_bin, "-version"])
    text = (err or out).decode(errors="replace").strip()
    return text.splitlines()[0] if text else "unknown version"


def cmd_java_home(args):
    valid = []
    for c in probe_java_homes():
        v = validate_java_home(c)
        if v:
            valid.append((c, v))
    if not valid:
        print("ERROR: no valid JDK found in known locations "
              "(JAVA_HOME, Android Studio/JetBrains JBRs, Adoptium).", file=sys.stderr)
        sys.exit(1)
    chosen_path, chosen_ver = valid[0]
    print(f"Resolved JAVA_HOME: {chosen_path}  ({chosen_ver})")
    for c, v in valid[1:]:
        print(f"  (also found: {c} — {v})")
    if args.fix_gradle:
        gradle_props = Path(args.fix_gradle) / "gradle.properties"
        line = f"org.gradle.java.home={chosen_path.replace(os.sep, '/')}"
        if gradle_props.exists():
            lines = [l for l in gradle_props.read_text().splitlines()
                     if not l.strip().startswith("org.gradle.java.home")]
            lines.append(line)
            gradle_props.write_text("\n".join(lines) + "\n")
        else:
            gradle_props.parent.mkdir(parents=True, exist_ok=True)
            gradle_props.write_text(line + "\n")
        print(f"Wrote '{line}' to {gradle_props}")


# --------------------------------------------------------------------------
# CLI wiring
# --------------------------------------------------------------------------

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    device_parent = argparse.ArgumentParser(add_help=False)
    device_parent.add_argument("--serial", help="Target device serial "
                                "(auto-selected if exactly one device is connected).")
    device_parent.add_argument("--adb-path", help="Explicit path to adb, "
                                "overriding PATH / default SDK location.")

    parser = argparse.ArgumentParser(
        description="Deterministic adb/uiautomator driver for Android device testing.")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("devices", parents=[device_parent],
                    help="List connected adb devices/emulators."
                    ).set_defaults(func=cmd_devices)

    sub.add_parser("info", parents=[device_parent],
                    help="Show comprehensive device, battery, display, and network status."
                    ).set_defaults(func=cmd_info)

    p = sub.add_parser("screenshot", parents=[device_parent],
                        help="Capture a byte-exact screenshot.")
    p.add_argument("out", help="Output PNG path.")
    p.set_defaults(func=cmd_screenshot)

    p = sub.add_parser("dump", parents=[device_parent],
                        help="Dump the current UI hierarchy as JSON.")
    p.set_defaults(func=cmd_dump)

    p = sub.add_parser("find", parents=[device_parent],
                        help="Find UI elements by text/resource-id/content-desc.")
    p.add_argument("--text")
    p.add_argument("--id", dest="id")
    p.add_argument("--desc")
    p.add_argument("--any", action="store_true",
                    help="Include non-clickable elements too.")
    p.set_defaults(func=cmd_find)

    p = sub.add_parser("wait-element", parents=[device_parent],
                        help="Wait until an element appears or disappears, with optional auto-tap.")
    p.add_argument("--text")
    p.add_argument("--id", dest="id")
    p.add_argument("--desc")
    p.add_argument("--any", action="store_true", help="Include non-clickable elements.")
    p.add_argument("--gone", action="store_true", help="Wait until the element disappears.")
    p.add_argument("--tap", action="store_true", help="Automatically tap the element when it appears.")
    p.add_argument("--timeout", type=float, default=10.0, help="Timeout in seconds (default 10.0).")
    p.add_argument("--poll", type=float, default=0.5, help="Poll interval in seconds (default 0.5).")
    p.set_defaults(func=cmd_wait_element)

    p = sub.add_parser("assert-text", parents=[device_parent],
                        help="Assert that text is visible on screen.")
    p.add_argument("text", help="Text expected to be visible.")
    p.add_argument("--timeout", type=float, default=5.0, help="Timeout in seconds.")
    p.set_defaults(func=cmd_assert_text)

    p = sub.add_parser("tap", parents=[device_parent],
                        help="Tap an element by text/id/content-desc, or literal x,y.")
    p.add_argument("coords", nargs="*", help="Optional positional 'x y' or target text.")
    p.add_argument("--text")
    p.add_argument("--id", dest="id")
    p.add_argument("--desc")
    p.add_argument("--x", type=int)
    p.add_argument("--y", type=int)
    p.add_argument("--index", type=int, default=0,
                    help="Which match to use when several elements match (default 0).")
    p.set_defaults(func=cmd_tap)

    p = sub.add_parser("long-press", parents=[device_parent],
                        help="Long-press an element by text/id/content-desc, or literal x,y.")
    p.add_argument("coords", nargs="*", help="Optional positional 'x y' or target text.")
    p.add_argument("--text")
    p.add_argument("--id", dest="id")
    p.add_argument("--desc")
    p.add_argument("--x", type=int)
    p.add_argument("--y", type=int)
    p.add_argument("--index", type=int, default=0,
                    help="Which match to use when several elements match (default 0).")
    p.add_argument("--duration", type=int, default=1000, help="Press duration in ms (default 1000).")
    p.set_defaults(func=cmd_long_press)

    p = sub.add_parser("type", parents=[device_parent],
                        help="Type text via adb, optionally clearing existing text first.")
    p.add_argument("text", nargs="?", default="", help="Text to type.")
    p.add_argument("--clear", action="store_true", help="Clear field before typing (backspaces).")
    p.add_argument("--clear-count", type=int, default=35, help="Number of backspaces to send.")
    p.add_argument("--tap-text", help="Optionally tap an element matching this text before typing.")
    p.add_argument("--tap-id", help="Optionally tap an element matching this resource-id before typing.")
    p.add_argument("--enter", action="store_true", help="Press Enter after typing.")
    p.add_argument("--hide-keyboard", action="store_true", help="Dismiss soft keyboard after typing.")
    p.set_defaults(func=cmd_type)

    p = sub.add_parser("scroll", parents=[device_parent],
                        help="Scroll down, up, left, or right smoothly.")
    p.add_argument("--direction", default="down", choices=["down", "up", "left", "right"],
                    help="Scroll direction (default: down).")
    p.add_argument("--distance", type=float, default=0.45,
                    help="Scroll distance as fraction of screen dimension (default 0.45).")
    p.add_argument("--duration", type=int, default=300, help="Swipe duration in ms.")
    p.set_defaults(func=cmd_scroll)

    p = sub.add_parser("scroll-into-view", parents=[device_parent],
                        help="Scroll repeatedly until an element appears, with optional auto-tap.")
    p.add_argument("--text")
    p.add_argument("--id", dest="id")
    p.add_argument("--desc")
    p.add_argument("--direction", default="down", choices=["down", "up"], help="Scroll direction (default: down).")
    p.add_argument("--max-swipes", type=int, default=5, help="Maximum number of swipe gestures (default: 5).")
    p.add_argument("--tap", action="store_true", help="Automatically tap the element once visible.")
    p.set_defaults(func=cmd_scroll_into_view)

    p = sub.add_parser("swipe", parents=[device_parent], help="Swipe between two points.")
    p.add_argument("x1", type=int)
    p.add_argument("y1", type=int)
    p.add_argument("x2", type=int)
    p.add_argument("y2", type=int)
    p.add_argument("--duration", type=int, default=200, help="Milliseconds.")
    p.set_defaults(func=cmd_swipe)

    p = sub.add_parser("key", parents=[device_parent], help="Send a keyevent (name or number).")
    p.add_argument("keycode")
    p.set_defaults(func=cmd_key)

    sub.add_parser("back", parents=[device_parent],
                    help="Press hardware/system Back button."
                    ).set_defaults(func=cmd_back)

    sub.add_parser("home", parents=[device_parent],
                    help="Press hardware/system Home button."
                    ).set_defaults(func=cmd_home)

    p = sub.add_parser("current", parents=[device_parent],
                        help="Print currently focused window and package.")
    p.set_defaults(func=cmd_current)

    p = sub.add_parser("wait-awake", parents=[device_parent],
                        help="Wait for the device to be awake (and optionally unlock it).")
    p.add_argument("--unlock", action="store_true")
    p.add_argument("--keep-awake", action="store_true", help="Set long screen timeout and stay on plugged in.")
    p.add_argument("--timeout-mins", type=int, help="Screen timeout in minutes.")
    p.add_argument("--timeout", type=float, default=10.0)
    p.set_defaults(func=cmd_wait_awake)

    p = sub.add_parser("launch", parents=[device_parent],
                        help="Force-stop, optionally clear, start, and confirm focus.")
    p.add_argument("--package", required=True)
    p.add_argument("--activity", required=True,
                    help=".MainActivity, a full class name, or pkg/.Activity")
    p.add_argument("--clear", action="store_true", help="pm clear before launching.")
    p.add_argument("--timeout", type=float, default=8.0)
    p.set_defaults(func=cmd_launch)

    p = sub.add_parser("install", parents=[device_parent],
                        help="Install an APK safely using the resolved adb path.")
    p.add_argument("apk", help="Path to APK file.")
    p.add_argument("--timeout", type=float, default=60.0, help="Timeout in seconds.")
    p.add_argument("--launch", help="Activity to launch immediately after install.")
    p.add_argument("--package", help="Package name for --launch.")
    p.set_defaults(func=cmd_install)

    p = sub.add_parser("logcat", parents=[device_parent],
                        help="Inspect recent logcat lines directly.")
    p.add_argument("--tag", help="Filter by tag.")
    p.add_argument("--lines", type=int, default=50, help="Number of lines to show (default 50).")
    p.add_argument("--grep", help="Regex pattern to match.")
    p.add_argument("--clear", action="store_true", help="Clear logcat buffer.")
    p.set_defaults(func=cmd_logcat)

    p = sub.add_parser("logcat-start", parents=[device_parent],
                        help="Clear the buffer and start a background filtered capture.")
    p.add_argument("--tags", nargs="*", default=[], help="Tags to include (others silenced).")
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_logcat_start)

    p = sub.add_parser("logcat-stop", help="Stop the tracked logcat-start capture.")
    p.set_defaults(func=cmd_logcat_stop)

    p = sub.add_parser("java-home",
                        help="Resolve a valid JDK and optionally pin it into gradle.properties.")
    p.add_argument("--fix-gradle", metavar="ANDROID_DIR",
                    help="Write org.gradle.java.home into <ANDROID_DIR>/gradle.properties.")
    p.set_defaults(func=cmd_java_home)

    args = parser.parse_args()
    global _ADB
    if getattr(args, "adb_path", None):
        _ADB = args.adb_path
    args.func(args)


if __name__ == "__main__":
    main()
