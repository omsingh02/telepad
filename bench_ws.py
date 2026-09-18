"""
Telepad WebSocket Throughput Benchmark
Connects to a running Telepad server and measures real message processing Hz.
"""
import socket, struct, hashlib, base64, time, sys, os, statistics

HOST = "127.0.0.1"
PORT = 5000

def ws_handshake(sock, token):
    """Perform WebSocket upgrade handshake."""
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET /?token={token} HTTP/1.1\r\n"
        f"Host: {HOST}:{PORT}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(req.encode())
    resp = sock.recv(4096).decode()
    if "101" not in resp:
        print(f"Handshake failed:\n{resp}")
        return False
    return True

def ws_send(sock, message):
    """Send a WebSocket text frame (client-masked)."""
    payload = message.encode("utf-8")
    length = len(payload)
    mask = os.urandom(4)
    
    # Build frame header
    header = bytearray([0x81])  # FIN + text opcode
    if length < 126:
        header.append(0x80 | length)  # Masked + length
    elif length < 65536:
        header.append(0x80 | 126)
        header.extend(struct.pack(">H", length))
    else:
        header.append(0x80 | 127)
        header.extend(struct.pack(">Q", length))
    
    header.extend(mask)
    masked = bytes([payload[i] ^ mask[i % 4] for i in range(length)])
    sock.sendall(bytes(header) + masked)

def run_benchmark(token, label, count, interval_ms=0):
    """
    Send `count` mouse-move messages and measure throughput.
    interval_ms=0 means burst (as fast as possible).
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.connect((HOST, PORT))
    
    if not ws_handshake(sock, token):
        sock.close()
        return
    
    # Warm up
    for _ in range(10):
        ws_send(sock, "mv|1.0|0.5|0|0|0|0")
    time.sleep(0.1)
    
    msg = "mv|2.3|1.1|0|0|0|0"  # Typical mouse-move message
    interval_s = interval_ms / 1000.0 if interval_ms > 0 else 0
    
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"  Sending {count} mouse-move messages", end="")
    if interval_ms > 0:
        print(f" @ {interval_ms}ms intervals ({1000/interval_ms:.1f} Hz target)")
    else:
        print(f" (burst / no delay)")
    print(f"{'='*60}")
    
    latencies = []
    t_start = time.perf_counter()
    
    for i in range(count):
        t0 = time.perf_counter()
        ws_send(sock, msg)
        t1 = time.perf_counter()
        latencies.append((t1 - t0) * 1000)  # ms
        
        if interval_s > 0:
            elapsed = t1 - t0
            sleep_time = interval_s - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)
    
    t_total = time.perf_counter() - t_start
    
    hz = count / t_total
    avg_lat = statistics.mean(latencies)
    p50 = statistics.median(latencies)
    p95 = sorted(latencies)[int(len(latencies) * 0.95)]
    p99 = sorted(latencies)[int(len(latencies) * 0.99)]
    max_lat = max(latencies)
    
    print(f"\n  Results:")
    print(f"  |-- Total time:     {t_total*1000:.1f} ms")
    print(f"  |-- Messages sent:  {count}")
    print(f"  |-- Throughput:     {hz:.1f} Hz ({hz:.0f} msgs/sec)")
    print(f"  |-- Avg send lat:   {avg_lat:.3f} ms")
    print(f"  |-- P50 latency:    {p50:.3f} ms")
    print(f"  |-- P95 latency:    {p95:.3f} ms")
    print(f"  |-- P99 latency:    {p99:.3f} ms")
    print(f"  +-- Max latency:    {max_lat:.3f} ms")
    
    sock.close()
    return hz

def run_mixed_benchmark(token, count=500):
    """Simulate realistic usage: mouse moves + clicks + key presses."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.connect((HOST, PORT))
    
    if not ws_handshake(sock, token):
        sock.close()
        return
    
    messages = []
    for i in range(count):
        r = i % 20
        if r < 15:
            messages.append("mv|1.5|0.8|0|0|0|0")
        elif r < 17:
            messages.append("cl|0|0|0|0")
        elif r < 19:
            messages.append("key|65|0|0|0|0")
        else:
            messages.append("cmd|play|0|0|0|0")
    
    print(f"\n{'='*60}")
    print(f"  Mixed Workload Benchmark")
    print(f"  {count} messages: 75% mouse, 10% click, 10% key, 5% media")
    print(f"  Sent at 16ms intervals (simulating real frontend)")
    print(f"{'='*60}")
    
    t_start = time.perf_counter()
    for msg in messages:
        ws_send(sock, msg)
        time.sleep(0.016)  # 16ms like the real frontend
    t_total = time.perf_counter() - t_start
    
    hz = count / t_total
    print(f"\n  Results:")
    print(f"  |-- Total time:     {t_total:.2f} s")
    print(f"  |-- Effective Hz:   {hz:.1f} Hz")
    print(f"  +-- Expected Hz:    62.5 Hz (1000/16ms)")
    
    sock.close()
    return hz

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python bench_ws.py <TOKEN>")
        print("  The token is printed when you start main.py")
        print("  Example: python bench_ws.py ABC123")
        sys.exit(1)
    
    token = sys.argv[1]
    
    print("\n" + "="*60)
    print("  TELEPAD WEBSOCKET THROUGHPUT BENCHMARK")
    print("="*60)
    print(f"  Server: {HOST}:{PORT}")
    print(f"  Token:  {token}")
    
    results = {}
    
    # Test 1: Burst (max throughput)
    results["burst"] = run_benchmark(token, "Test 1: Burst (Max Throughput)", count=2000, interval_ms=0)
    time.sleep(0.3)
    
    # Test 2: 16ms interval (matching frontend setInterval)
    results["16ms"] = run_benchmark(token, "Test 2: 16ms Interval (Frontend Match)", count=300, interval_ms=16)
    time.sleep(0.3)
    
    # Test 3: 8ms interval (2x frontend rate)
    results["8ms"] = run_benchmark(token, "Test 3: 8ms Interval (2x Frontend)", count=300, interval_ms=8)
    time.sleep(0.3)
    
    # Test 4: 4ms interval (4x frontend rate — stress test)
    results["4ms"] = run_benchmark(token, "Test 4: 4ms Interval (4x Frontend — Stress)", count=500, interval_ms=4)
    time.sleep(0.3)
    
    # Test 5: Mixed workload
    results["mixed"] = run_mixed_benchmark(token, count=300)
    
    # Summary
    print(f"\n{'='*60}")
    print(f"  SUMMARY")
    print(f"{'='*60}")
    print(f"  Max burst throughput:    {results['burst']:.0f} Hz")
    print(f"  @ 16ms (real frontend):  {results['16ms']:.1f} Hz")
    print(f"  @ 8ms  (2x speed):       {results['8ms']:.1f} Hz")
    print(f"  @ 4ms  (stress test):    {results['4ms']:.1f} Hz")
    print(f"  Mixed workload @ 16ms:   {results['mixed']:.1f} Hz")
    print(f"\n  Frontend setInterval = 16ms → theoretical cap = 62.5 Hz")
    print(f"  Your server can handle:  {results['burst']:.0f} Hz peak")
    print(f"{'='*60}\n")
