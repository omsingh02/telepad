import socket, struct, hashlib, base64, threading, sys, os, subprocess, json

PORT = 5000
SENS, SCROLL = 1.6, 2.2

class InputController:
    def mouse(self, flags, x=0, y=0, data=0): pass
    def key(self, vk): pass
    def keycombo(self, vk, ctrl=0, shift=0, alt=0, win=0): pass
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
            print("Warning: Windows Input requested but not on Windows. Mocking u32.")
            class MockU32:
                def __getattr__(self, name): return lambda *args, **kwargs: None
            self.u32 = MockU32()

    def mouse(self, flags, x=0, y=0, data=0):
        self.u32.mouse_event(flags, x, y, data, 0)

    def key(self, vk):
        self.u32.keybd_event(vk, 0, 1, 0)
        self.u32.keybd_event(vk, 0, 3, 0)

    def keycombo(self, vk, ctrl=0, shift=0, alt=0, win=0):
        mods = [(0x11, ctrl), (0x10, shift), (0x12, alt), (0x5B, win)]
        for k, p in mods:
            if p: self.u32.keybd_event(k, 0, 0, 0)
        self.u32.keybd_event(vk, 0, 0, 0)
        self.u32.keybd_event(vk, 0, 2, 0)
        for k, p in reversed(mods):
            if p: self.u32.keybd_event(k, 0, 2, 0)

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
            print("Error: 'evdev' module is required on Linux. Install via 'pip install evdev' or 'sudo pacman -S python-evdev'.")
            sys.exit(1)
            
        self.vk_map = {9: ecodes.KEY_TAB, 27: ecodes.KEY_ESC, 13: ecodes.KEY_ENTER, 8: ecodes.KEY_BACKSPACE, 32: ecodes.KEY_SPACE, 37: ecodes.KEY_LEFT, 38: ecodes.KEY_UP, 39: ecodes.KEY_RIGHT, 40: ecodes.KEY_DOWN, 91: ecodes.KEY_LEFTMETA}

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

    def keycombo(self, vk, ctrl=0, shift=0, alt=0, win=0):
        e = self.ecodes
        if ctrl: self.ui.write(e.EV_KEY, e.KEY_LEFTCTRL, 1)
        if shift: self.ui.write(e.EV_KEY, e.KEY_LEFTSHIFT, 1)
        if alt: self.ui.write(e.EV_KEY, e.KEY_LEFTALT, 1)
        if win: self.ui.write(e.EV_KEY, e.KEY_LEFTMETA, 1)
        self.ui.syn()
        
        if vk in self.vk_map: self._send_key(self.vk_map[vk])
            
        if win: self.ui.write(e.EV_KEY, e.KEY_LEFTMETA, 0)
        if alt: self.ui.write(e.EV_KEY, e.KEY_LEFTALT, 0)
        if shift: self.ui.write(e.EV_KEY, e.KEY_LEFTSHIFT, 0)
        if ctrl: self.ui.write(e.EV_KEY, e.KEY_LEFTCTRL, 0)
        self.ui.syn()

    def txt(self, s):
        try:
            # Safely delegate typing to Wayland typing tools for high complex unicode/emojis 
            # Note: Do not pass directly to shell to avoid injections
            subprocess.run(['wtype', s], timeout=2)
        except Exception as e:
            print(f"wtype error (is it installed?): {e}")
                
    def lock(self): os.system("loginctl lock-session")
    
    def volume(self, direction):
        if direction == 'mute': self._send_key(self.ecodes.KEY_MUTE)
        elif direction == 'dn': self._send_key(self.ecodes.KEY_VOLUMEDOWN)
        elif direction == 'up': self._send_key(self.ecodes.KEY_VOLUMEUP)
        
    def media(self, action):
        if action == 'play': self._send_key(self.ecodes.KEY_PLAYPAUSE)
        elif action == 'next': self._send_key(self.ecodes.KEY_NEXTSONG)
        elif action == 'prev': self._send_key(self.ecodes.KEY_PREVIOUSSONG)

# Init Controller based on OS
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

def get_media_status():
    if sys.platform == "win32": return b"[]"
    try:
        res = subprocess.check_output(['playerctl', '-a', 'metadata', '--format', '{{playerName}}|{{status}}|{{artist}}|{{title}}'], timeout=1).decode('utf-8')
        streams = []
        for line in res.strip().split('\n'):
            if not line: continue
            parts = line.split('|', 3)
            if len(parts) == 4:
                streams.append({
                    "id": parts[0], 
                    "status": parts[1], 
                    "artist": parts[2].strip() or "Unknown Artist", 
                    "title": parts[3].strip() or parts[0].capitalize()
                })
        return json.dumps(streams).encode('utf-8')
    except Exception:
        return b"[]"

def handle(conn, addr):
    conn.settimeout(60)
    try:
        req = conn.recv(4096).decode('utf-8', errors='ignore')
        if 'Upgrade: websocket' in req:
            for ln in req.split('\r\n'):
                if ln.startswith('Sec-WebSocket-Key:'):
                    key = ln.split(': ', 1)[1].strip()
                    break
            else:
                return
            conn.sendall(f"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {ws_accept(key)}\r\n\r\n".encode())
            conn.settimeout(300)
            while True:
                msg = ws_recv(conn)
                if not msg: break
                p = msg.split('|')
                t = p[0]
                if t == 'mv' and len(p) >= 3:
                    try: ctrl.mouse(1, int(float(p[1]) * SENS), int(float(p[2]) * SENS))
                    except: pass
                elif t == 'sc' and len(p) >= 2:
                    try: ctrl.mouse(0x800, 0, 0, int(float(p[1]) * SCROLL * 30))
                    except: pass
                elif t == 'md' and len(p) >= 2: ctrl.mouse(2 if p[1] == '1' else 8)
                elif t == 'mu' and len(p) >= 2: ctrl.mouse(4 if p[1] == '1' else 16)
                elif t == 'cl': ctrl.mouse(2); ctrl.mouse(4)
                elif t == 'rc': ctrl.mouse(8); ctrl.mouse(16)
                elif t == 'cmd' and len(p) >= 2:
                    if p[1] == 'lock': ctrl.lock()
                    elif p[1] in ('mute', 'voldn', 'volup'): ctrl.volume(p[1].replace('vol', ''))
                    elif p[1] in ('play', 'next', 'prev'):
                        if len(p) >= 3 and p[2]:  # Targeted playerctl action
                            action = 'play-pause' if p[1] == 'play' else p[1]
                            try: subprocess.run(['playerctl', '-p', p[2], action], timeout=1)
                            except: pass
                        else: # Global action
                            ctrl.media(p[1])
                elif t == 'key' and len(p) >= 2:
                    try:
                        vk = int(p[1])
                        # If a naked push of 91, don't demand combos
                        if vk == 91 and len(p) == 2:
                            ctrl.key(vk)
                            continue
                            
                        ctrl_mod = int(p[2]) if len(p) > 2 else 0
                        shift_mod = int(p[3]) if len(p) > 3 else 0
                        alt_mod = int(p[4]) if len(p) > 4 else 0
                        win_mod = int(p[5]) if len(p) > 5 else 0
                        ctrl.keycombo(vk, ctrl_mod, shift_mod, alt_mod, win_mod)
                    except: pass
                elif t == 'type' and len(p) >= 2: ctrl.txt(p[1])
        else:
            if req.startswith('GET /media HTTP'):
                payload = get_media_status()
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + payload)
            else:
                html = get_html()
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-cache\r\n\r\n" + html)
    except (socket.timeout, ConnectionResetError, BrokenPipeError, OSError):
        pass
    finally:
        try: conn.close()
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
    try:
        srv.bind(('0.0.0.0', PORT))
    except OSError:
        print(f"Error: Port {PORT} in use")
        sys.exit(1)
    srv.listen(8)
    ip = get_ip()
    print(f"\n  Telepad Server Running")
    print(f"  http://{ip}:{PORT}\n")
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\nShutdown")
    finally:
        srv.close()
