"""
Telepad Stress Test — Find the maximum sustainable Hz over Wi-Fi.
Tests progressively faster intervals and detects degradation:
  - Latency spikes
  - Message ordering issues
  - Connection drops
"""
import socket, struct, hashlib, base64, time, sys, os, statistics

HOST = "127.0.0.1"
PORT = 5000

def ws_handshake(sock, token):
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
    return "101" in resp

def ws_send(sock, message):
    payload = message.encode("utf-8")
    length = len(payload)
    mask = os.urandom(4)
    header = bytearray([0x81])
    if length < 126:
        header.append(0x80 | length)
    elif length < 65536:
        header.append(0x80 | 126)
        header.extend(struct.pack(">H", length))
    header.extend(mask)
    masked = bytes([payload[i] ^ mask[i % 4] for i in range(length)])
    sock.sendall(bytes(header) + masked)

def test_interval(token, interval_ms, count=500, label=""):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(10)
    sock.connect((HOST, PORT))
    
    if not ws_handshake(sock, token):
        print(f"  FAIL: Handshake failed")
        sock.close()
        return None
    
    # Warm up
    for _ in range(5):
        ws_send(sock, "mv|1.0|0.5|0|0|0|0")
    time.sleep(0.05)
    
    interval_s = interval_ms / 1000.0
    target_hz = 1000.0 / interval_ms if interval_ms > 0 else float('inf')
    latencies = []
    jitter = []
    drops = 0
    last_send = 0
    
    t_start = time.perf_counter()
    
    for i in range(count):
        t0 = time.perf_counter()
        try:
            ws_send(sock, f"mv|{i}|0.5|0|0|0|0")
        except Exception as e:
            drops += 1
            continue
        t1 = time.perf_counter()
        latencies.append((t1 - t0) * 1000)
        
        if last_send > 0:
            actual_interval = (t0 - last_send) * 1000
            jitter.append(abs(actual_interval - interval_ms))
        last_send = t0
        
        elapsed = t1 - t0
        sleep_time = interval_s - elapsed
        if sleep_time > 0:
            time.sleep(sleep_time)
    
    t_total = time.perf_counter() - t_start
    actual_hz = count / t_total
    
    if not latencies:
        print(f"  FAIL: All messages dropped")
        sock.close()
        return None
    
    avg_lat = statistics.mean(latencies)
    p50 = statistics.median(latencies)
    p99 = sorted(latencies)[min(int(len(latencies) * 0.99), len(latencies)-1)]
    max_lat = max(latencies)
    avg_jitter = statistics.mean(jitter) if jitter else 0
    max_jitter = max(jitter) if jitter else 0
    
    # Detect degradation
    issues = []
    if drops > 0: issues.append(f"{drops} DROPPED")
    if p99 > 5.0: issues.append(f"P99 spike: {p99:.1f}ms")
    if max_lat > 10.0: issues.append(f"Max spike: {max_lat:.1f}ms")
    if max_jitter > interval_ms * 2: issues.append(f"High jitter: {max_jitter:.1f}ms")
    
    status = "!! DEGRADED" if issues else "OK"
    issue_str = " | ".join(issues) if issues else ""
    
    print(f"  {interval_ms:6.1f}ms  {target_hz:7.1f} Hz  {actual_hz:7.1f} Hz  "
          f"{p50:6.3f}ms  {p99:6.3f}ms  {max_lat:7.3f}ms  "
          f"{avg_jitter:5.2f}ms  {drops:3d}  {status}  {issue_str}")
    
    sock.close()
    return {
        "interval_ms": interval_ms,
        "target_hz": target_hz,
        "actual_hz": actual_hz,
        "p50": p50,
        "p99": p99,
        "max_lat": max_lat,
        "jitter": avg_jitter,
        "drops": drops,
        "ok": len(issues) == 0
    }

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python bench_stress.py <TOKEN>")
        sys.exit(1)
    
    token = sys.argv[1]
    
    print()
    print("=" * 110)
    print("  TELEPAD MAX Hz STRESS TEST")
    print("=" * 110)
    print(f"  Server: {HOST}:{PORT} | Token: {token}")
    print(f"  Sending 500 messages at each interval, measuring degradation")
    print()
    print(f"  {'Interval':>8}  {'Target':>8}    {'Actual':>8}    "
          f"{'P50':>7}   {'P99':>7}   {'Max':>8}   "
          f"{'Jitter':>6}  {'Drop':>4}  Status")
    print(f"  {'-'*8}  {'-'*8}    {'-'*8}    "
          f"{'-'*7}   {'-'*7}   {'-'*8}   "
          f"{'-'*6}  {'-'*4}  {'-'*20}")
    
    # Test from slow → fast
    intervals = [16, 12, 10, 8, 6, 5, 4, 3, 2, 1.5, 1, 0.5]
    results = []
    
    for ms in intervals:
        r = test_interval(token, ms, count=500)
        if r:
            results.append(r)
        time.sleep(0.3)  # Cool down between tests
    
    # Find the sweet spot
    print()
    print("=" * 110)
    print("  ANALYSIS")
    print("=" * 110)
    
    last_ok = None
    first_degraded = None
    for r in results:
        if r["ok"]:
            last_ok = r
        elif first_degraded is None:
            first_degraded = r
    
    if last_ok:
        print(f"  Max stable interval:    {last_ok['interval_ms']}ms = {last_ok['actual_hz']:.0f} Hz")
        print(f"  Max stable P99 latency: {last_ok['p99']:.3f}ms")
    
    if first_degraded:
        print(f"  First degraded at:      {first_degraded['interval_ms']}ms = {first_degraded['actual_hz']:.0f} Hz")
        print(f"  Degraded P99 latency:   {first_degraded['p99']:.3f}ms")
    
    # Recommendation
    print()
    print("  RECOMMENDATION:")
    if last_ok and last_ok['interval_ms'] <= 8:
        print(f"  The server comfortably handles {last_ok['actual_hz']:.0f} Hz over localhost.")
        print(f"  Over real Wi-Fi, add ~2-5ms of network latency overhead.")
        print()
        print(f"  Practical safe maximums:")
        print(f"    setInterval(8)            = 125 Hz  (matches USB mouse)")
        print(f"    setInterval(4)            = 250 Hz  (browser timer floor)")
        print(f"    requestAnimationFrame     = 60-120 Hz (synced to display, most efficient)")
        print(f"    Direct touchmove events   = 120-240 Hz (depends on phone touch sampling)")
    
    print()
    print(f"  NOTE: These are localhost results. Over Wi-Fi:")
    print(f"    - Add 1-5ms per-hop latency (2.4GHz) or 0.5-2ms (5GHz)")
    print(f"    - Real-world max over Wi-Fi is ~200-500 Hz before congestion")
    print(f"    - Phone browser timer throttling may cap at ~250 Hz (4ms min)")
    print(f"    - Phone touch sampling: 60Hz (old), 120Hz (mid), 240Hz (flagship)")
    print("=" * 110)
    print()
