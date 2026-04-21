import socket, struct, hashlib, base64, ctypes, threading, sys, os

PORT = 5000
SENS, SCROLL = 1.6, 2.2
try:
    u32 = ctypes.windll.user32
except AttributeError:
    print("Error: WinRemotePad requires a Windows environment to run (ctypes.windll.user32 is missing).")
    # For dev purposes on non-Windows testing, mock it
    class MockU32:
        def __getattr__(self, name):
            return lambda *args, **kwargs: None
    u32 = MockU32()

def mouse(flags, x=0, y=0, data=0):
    u32.mouse_event(flags, x, y, data, 0)

def key(vk):
    u32.keybd_event(vk, 0, 1, 0)
    u32.keybd_event(vk, 0, 3, 0)

def keycombo(vk, ctrl=0, shift=0, alt=0, win=0):
    mods = [(0x11, ctrl), (0x10, shift), (0x12, alt), (0x5B, win)]
    for k, p in mods:
        if p: u32.keybd_event(k, 0, 0, 0)
    u32.keybd_event(vk, 0, 0, 0)
    u32.keybd_event(vk, 0, 2, 0)
    for k, p in reversed(mods):
        if p: u32.keybd_event(k, 0, 2, 0)

def txt(s):
    for c in s:
        v = u32.VkKeyScanW(ord(c))
        code, shift = v & 0xFF, v >> 8 & 1
        if shift: u32.keybd_event(0x10, 0, 0, 0)
        u32.keybd_event(code, 0, 0, 0)
        u32.keybd_event(code, 0, 2, 0)
        if shift: u32.keybd_event(0x10, 0, 2, 0)

CMD = {
    'lock': lambda: u32.LockWorkStation(),
    'mute': lambda: key(0xAD), 'voldn': lambda: key(0xAE), 'volup': lambda: key(0xAF),
    'play': lambda: key(0xB3), 'next': lambda: key(0xB0), 'prev': lambda: key(0xB1),
}

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
    # Construct absolute path to index.html assuming it is in the same directory as this script.
    html_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'index.html')
    if os.path.exists(html_path):
        with open(html_path, 'rb') as f:
            return f.read()
    return b"<html><body>Error: index.html not found</body></html>"

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
                    try: mouse(1, int(float(p[1]) * SENS), int(float(p[2]) * SENS))
                    except: pass
                elif t == 'sc' and len(p) >= 2:
                    try: mouse(0x800, 0, 0, int(float(p[1]) * SCROLL * 30))
                    except: pass
                elif t == 'md' and len(p) >= 2: mouse(2 if p[1] == '1' else 8)
                elif t == 'mu' and len(p) >= 2: mouse(4 if p[1] == '1' else 16)
                elif t == 'cl': mouse(2); mouse(4)
                elif t == 'rc': mouse(8); mouse(16)
                elif t == 'cmd' and len(p) >= 2 and p[1] in CMD: CMD[p[1]]()
                elif t == 'key' and len(p) >= 2:
                    try:
                        vk = int(p[1])
                        ctrl = int(p[2]) if len(p) > 2 else 0
                        shift = int(p[3]) if len(p) > 3 else 0
                        alt = int(p[4]) if len(p) > 4 else 0
                        win = int(p[5]) if len(p) > 5 else 0
                        keycombo(vk, ctrl, shift, alt, win)
                    except: pass
                elif t == 'type' and len(p) >= 2: txt(p[1])
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
    print(f"\n  WinRemotePad Server")
    print(f"  http://{ip}:{PORT}\n")
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\nShutdown")
    finally:
        srv.close()
