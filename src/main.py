import socket, struct, hashlib, base64, threading, sys, os, subprocess, json, psutil, qrcode

PORT = 5000
SENS, SCROLL = 1.6, 2.2

class InputController:
    def sync_mods(self, ctrl, shift, alt, win, force=False): pass
    def mouse(self, flags, x=0, y=0, data=0): pass
    def key(self, vk): pass
    def txt(self, s): pass
    def lock(self): pass
    def volume(self, direction): pass
    def media(self, action): pass

class WindowsInputController(InputController):
    def __init__(self):
        try:
            import ctypes
            self.u32 = ctypes.windll.user32
        except AttributeError:
            class MockU32:
                def __getattr__(self, name): return lambda *args, **kwargs: None
            self.u32 = MockU32()
        self._m = [0,0,0,0]

    def sync_mods(self, ctrl, shift, alt, win, force=False):
        if not force and [ctrl, shift, alt, win] == self._m: return
        pairs = [(0x11, ctrl), (0x10, shift), (0x12, alt), (0x5B, win)]
        for i, (k, s) in enumerate(pairs):
            if force or s != self._m[i]:
                self.u32.keybd_event(k, 0, 0 if s else 2, 0)
        self._m = [ctrl, shift, alt, win]

    def mouse(self, flags, x=0, y=0, data=0):
        self.u32.mouse_event(flags, x, y, data, 0)

    def key(self, vk):
        self.u32.keybd_event(vk, 0, 0, 0)
        self.u32.keybd_event(vk, 0, 2, 0)

    def txt(self, s):
        for c in s:
            v = self.u32.VkKeyScanW(ord(c))
            code, shift = v & 0xFF, v >> 8 & 1
            if shift: self.u32.keybd_event(0x10, 0, 0, 0)
            self.u32.keybd_event(code, 0, 0, 0)
            self.u32.keybd_event(code, 0, 2, 0)
            if shift: self.u32.keybd_event(0x10, 0, 2, 0)
            
    def lock(self): self.u32.LockWorkStation()
    def volume(self, direction):
        if direction == 'mute': self.key(0xAD)
        elif direction == 'dn': self.key(0xAE)
        elif direction == 'up': self.key(0xAF)
    def media(self, action):
        if action == 'play': self.key(0xB3)
        elif action == 'next': self.key(0xB0)
        elif action == 'prev': self.key(0xB1)


def run_as_user(cmd_lines, timeout=2, return_output=False):
    sudo_user = os.environ.get('SUDO_USER')
    env = os.environ.copy()
    if sudo_user:
        # Ensure DBUS session bus is discoverable — sudo -E may not carry it
        try:
            import pwd
            uid = pwd.getpwnam(sudo_user).pw_uid
            dbus_addr = f"unix:path=/run/user/{uid}/bus"
            env['DBUS_SESSION_BUS_ADDRESS'] = dbus_addr
            env['XDG_RUNTIME_DIR'] = f"/run/user/{uid}"
        except Exception: pass
        cmd = ['sudo', '-u', sudo_user, '-E'] + cmd_lines
    else:
        cmd = cmd_lines
    try:
        if return_output: return subprocess.check_output(cmd, timeout=timeout, stderr=subprocess.DEVNULL, env=env).decode('utf-8')
        else: subprocess.run(cmd, timeout=timeout, stderr=subprocess.DEVNULL, env=env)
    except Exception: return ""

class LinuxInputController(InputController):
    def __init__(self):
        try:
            import evdev
            self.evdev = evdev
            from evdev import ecodes
            self.ecodes = ecodes
            
            caps = {
                ecodes.EV_KEY: [
                    ecodes.KEY_A, ecodes.KEY_B, ecodes.KEY_C, ecodes.KEY_D, ecodes.KEY_E, ecodes.KEY_F, ecodes.KEY_G, ecodes.KEY_H, ecodes.KEY_I, ecodes.KEY_J, ecodes.KEY_K, ecodes.KEY_L, ecodes.KEY_M, ecodes.KEY_N, ecodes.KEY_O, ecodes.KEY_P, ecodes.KEY_Q, ecodes.KEY_R, ecodes.KEY_S, ecodes.KEY_T, ecodes.KEY_U, ecodes.KEY_V, ecodes.KEY_W, ecodes.KEY_X, ecodes.KEY_Y, ecodes.KEY_Z,
                    ecodes.KEY_0, ecodes.KEY_1, ecodes.KEY_2, ecodes.KEY_3, ecodes.KEY_4, ecodes.KEY_5, ecodes.KEY_6, ecodes.KEY_7, ecodes.KEY_8, ecodes.KEY_9,
                    ecodes.KEY_SPACE, ecodes.KEY_ENTER, ecodes.KEY_TAB, ecodes.KEY_ESC, ecodes.KEY_BACKSPACE,
                    ecodes.KEY_UP, ecodes.KEY_DOWN, ecodes.KEY_LEFT, ecodes.KEY_RIGHT,
                    ecodes.KEY_LEFTSHIFT, ecodes.KEY_RIGHTSHIFT, ecodes.KEY_LEFTCTRL, ecodes.KEY_RIGHTCTRL, ecodes.KEY_LEFTALT, ecodes.KEY_LEFTMETA,
                    ecodes.KEY_MUTE, ecodes.KEY_VOLUMEDOWN, ecodes.KEY_VOLUMEUP, ecodes.KEY_PLAYPAUSE, ecodes.KEY_NEXTSONG, ecodes.KEY_PREVIOUSSONG, ecodes.KEY_SLEEP,
                    ecodes.BTN_LEFT, ecodes.BTN_RIGHT, ecodes.BTN_MIDDLE,
                    ecodes.KEY_MINUS, ecodes.KEY_EQUAL, ecodes.KEY_LEFTBRACE, ecodes.KEY_RIGHTBRACE, ecodes.KEY_BACKSLASH, ecodes.KEY_SEMICOLON, ecodes.KEY_APOSTROPHE, ecodes.KEY_GRAVE, ecodes.KEY_COMMA, ecodes.KEY_DOT, ecodes.KEY_SLASH
                ],
                ecodes.EV_REL: [ecodes.REL_X, ecodes.REL_Y, ecodes.REL_WHEEL]
            }
            try:
                self.ui = evdev.UInput(caps, name='telepad-virtual-input')
            except Exception as e:
                print(f"Error accessing /dev/uinput: {e}\nEnsure you run as root or add user to 'input' group and specify udev rules.")
                sys.exit(1)
        except ImportError:
            print("Error: 'evdev' module is required on Linux.")
            sys.exit(1)
            
        self.vk_map = {9: ecodes.KEY_TAB, 27: ecodes.KEY_ESC, 13: ecodes.KEY_ENTER, 8: ecodes.KEY_BACKSPACE, 32: ecodes.KEY_SPACE, 37: ecodes.KEY_LEFT, 38: ecodes.KEY_UP, 39: ecodes.KEY_RIGHT, 40: ecodes.KEY_DOWN, 91: ecodes.KEY_LEFTMETA}
        for i in range(26): self.vk_map[65 + i] = getattr(ecodes, f"KEY_{chr(65 + i)}")
        for i in range(10): self.vk_map[48 + i] = getattr(ecodes, f"KEY_{i}")
        self._m = [0,0,0,0]
        self.sync_mods(0,0,0,0, force=True)

    def sync_mods(self, ctrl, shift, alt, win, force=False):
        if not force and [ctrl, shift, alt, win] == self._m: return
        e = self.ecodes
        pairs = [(e.KEY_LEFTCTRL, ctrl), (e.KEY_LEFTSHIFT, shift), (e.KEY_LEFTALT, alt), (e.KEY_LEFTMETA, win)]
        for i, (k, s) in enumerate(pairs):
            if force or s != self._m[i]:
                self.ui.write(e.EV_KEY, k, 1 if s else 0)
        self.ui.syn()
        self._m = [ctrl, shift, alt, win]

    def _send_key(self, key_ecode, hold=False):
        self.ui.write(self.ecodes.EV_KEY, key_ecode, 1) # Down
        if not hold:
            self.ui.write(self.ecodes.EV_KEY, key_ecode, 0) # Up
        self.ui.syn()

    def mouse(self, flags, x=0, y=0, data=0):
        e = self.ecodes
        if flags == 1: # Move
            self.ui.write(e.EV_REL, e.REL_X, int(x))
            self.ui.write(e.EV_REL, e.REL_Y, int(y))
        elif flags == 2: self.ui.write(e.EV_KEY, e.BTN_LEFT, 1)
        elif flags == 4: self.ui.write(e.EV_KEY, e.BTN_LEFT, 0)
        elif flags == 8: self.ui.write(e.EV_KEY, e.BTN_RIGHT, 1)
        elif flags == 16: self.ui.write(e.EV_KEY, e.BTN_RIGHT, 0)
        elif flags == 0x800: # Scroll
            ticks = -int(data / 120) 
            if ticks != 0: self.ui.write(e.EV_REL, e.REL_WHEEL, ticks)
        self.ui.syn()

    def key(self, vk):
        if vk in self.vk_map: self._send_key(self.vk_map[vk])

    def txt(self, s):
        run_as_user(['wtype', s])
                
    def lock(self): os.system("loginctl lock-session")
    
    def volume(self, direction):
        if direction == 'mute': self._send_key(self.ecodes.KEY_MUTE)
        elif direction == 'dn': self._send_key(self.ecodes.KEY_VOLUMEDOWN)
        elif direction == 'up': self._send_key(self.ecodes.KEY_VOLUMEUP)
        
    def media(self, action):
        if action == 'play': self._send_key(self.ecodes.KEY_PLAYPAUSE)
        elif action == 'next': self._send_key(self.ecodes.KEY_NEXTSONG)
        elif action == 'prev': self._send_key(self.ecodes.KEY_PREVIOUSSONG)

if sys.platform == "win32":
    ctrl = WindowsInputController()
else:
    ctrl = LinuxInputController()


def ws_accept(key):
    return base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()

def recv_exact(sock, n):
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk: return None
        buf += chunk
    return buf

def ws_recv(sock):
    h = recv_exact(sock, 2)
    if not h: return None
    if h[0] & 0x0F == 8: return None
    l = h[1] & 0x7F
    if l == 126:
        ext = recv_exact(sock, 2)
        if not ext: return None
        l = struct.unpack(">H", ext)[0]
    elif l == 127:
        ext = recv_exact(sock, 8)
        if not ext: return None
        l = struct.unpack(">Q", ext)[0]
    m = recv_exact(sock, 4)
    if not m: return None
    p = recv_exact(sock, l)
    if not p: return None
    return bytes([p[i] ^ m[i % 4] for i in range(l)]).decode('utf-8', errors='ignore')

def get_html():
    html_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'index.html')
    if os.path.exists(html_path):
        with open(html_path, 'rb') as f:
            return f.read()
    return b"<html><body>Error: index.html not found</body></html>"

def get_status():
    data = {"streams": [], "sys": {"bat": 0, "charging": False}}
    # Battery
    try:
        bat = psutil.sensors_battery()
        if bat:
            data["sys"]["bat"] = int(bat.percent)
            data["sys"]["charging"] = bat.power_plugged
    except: pass
    
    # Media
    if sys.platform != "win32":
        res = run_as_user(['playerctl', '-a', 'metadata', '--format', '{{playerName}}|{{status}}|{{artist}}|{{title}}'], return_output=True)
        if res:
            for line in res.strip().split('\n'):
                if not line: continue
                parts = line.split('|', 3)
                if len(parts) == 4:
                    data["streams"].append({
                        "id": parts[0], 
                        "status": parts[1], 
                        "artist": parts[2].strip() or "Unknown Artist", 
                        "title": parts[3].strip() or parts[0].capitalize()
                    })
    return json.dumps(data).encode('utf-8')

def handle(conn, addr):
    conn.settimeout(60)
    try:
        req = conn.recv(4096).decode('utf-8', errors='ignore')
        if 'Upgrade: websocket' in req:
            for ln in req.split('\r\n'):
                if ln.startswith('Sec-WebSocket-Key:'):
                    key = ln.split(': ', 1)[1].strip()
                    break
            else: return
            conn.sendall(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {ws_accept(key)}\r\n\r\n".encode())
            conn.settimeout(300)
            while True:
                msg = ws_recv(conn)
                if not msg: break
                p = msg.split('|')
                if len(p) < 5: continue
                try:
                    c_on, s_on, a_on, w_on = [int(x) for x in p[-4:]]
                    ctrl.sync_mods(c_on, s_on, a_on, w_on)
                    payload = p[:-4]
                except: continue

                t = payload[0]
                if t == 'mv' and len(payload) >= 3:
                    try: ctrl.mouse(1, int(float(payload[1]) * SENS), int(float(payload[2]) * SENS))
                    except: pass
                elif t == 'sc' and len(payload) >= 2:
                    try: ctrl.mouse(0x800, 0, 0, int(float(payload[1]) * SCROLL * 30))
                    except: pass
                elif t == 'md' and len(payload) >= 2: ctrl.mouse(2 if payload[1] == '1' else 8)
                elif t == 'mu' and len(payload) >= 2: ctrl.mouse(4 if payload[1] == '1' else 16)
                elif t == 'cl': ctrl.mouse(2); ctrl.mouse(4)
                elif t == 'rc': ctrl.mouse(8); ctrl.mouse(16)
                elif t == 'cmd' and len(payload) >= 2:
                    if payload[1] == 'lock': ctrl.lock()
                    elif payload[1] in ('mute', 'voldn', 'volup'): ctrl.volume(payload[1].replace('vol', ''))
                    elif payload[1] in ('play', 'next', 'prev'):
                        if len(payload) >= 3 and payload[2]:
                            action = 'play-pause' if payload[1] == 'play' else payload[1]
                            run_as_user(['playerctl', '-p', payload[2], action], timeout=1)
                        else: ctrl.media(payload[1])
                elif t == 'key' and len(payload) >= 2:
                    try: ctrl.key(int(payload[1]))
                    except: pass
                elif t == 'type' and len(payload) >= 2: ctrl.txt(payload[1])
        else:
            if 'GET /media' in req:
                payload = get_status()
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + payload)
            else:
                html = get_html()
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-cache\r\n\r\n" + html)
    except (socket.timeout, ConnectionResetError, BrokenPipeError, OSError): pass
    finally:
        try: 
            ctrl.sync_mods(0,0,0,0, force=True)
            conn.close()
        except: pass

def get_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        return s.getsockname()[0]
    except: return '127.0.0.1'
    finally: s.close()

if __name__ == '__main__':
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: srv.bind(('0.0.0.0', PORT))
    except OSError:
        print(f"Error: Port {PORT} in use")
        sys.exit(1)
    srv.listen(8)
    ip = get_ip()
    url = f"http://{ip}:{PORT}"
    print(f"\n  Telepad Server Running")
    print(f"  {url}\n")
    
    # QR Code
    qr = qrcode.QRCode()
    qr.add_data(url)
    qr.print_ascii(invert=True)
    
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt: print("\nShutdown")
    finally: srv.close()
