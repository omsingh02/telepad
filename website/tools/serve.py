#!/usr/bin/env python3
"""Serves the landing page locally with the same headers Vercel will add (see vercel.json),
so a Content Security Policy mistake shows up here and not in production.

    python3 website/tools/serve.py [port]

Then open http://127.0.0.1:8080. Console messages about blocked resources are CSP violations.
"""
import http.server
import json
import socketserver
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
CONFIG = json.loads((ROOT / "vercel.json").read_text())


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def end_headers(self):
        for rule in CONFIG.get("headers", []):
            prefix = rule["source"].replace("/(.*)", "").replace("(.*)", "")
            if self.path.startswith(prefix or "/"):
                for header in rule["headers"]:
                    self.send_header(header["key"], header["value"])
        super().end_headers()

    def send_error(self, code, message=None, explain=None):
        if code == 404 and (ROOT / "404.html").exists():
            body = (ROOT / "404.html").read_bytes()
            self.send_response(404)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            super().send_error(code, message, explain)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    with Server(("127.0.0.1", PORT), Handler) as server:
        print(f"Serving {ROOT} at http://127.0.0.1:{PORT} (Ctrl-C to stop)")
        server.serve_forever()
