#!/usr/bin/env python3
"""PrideChat cross-server relay (roadmap #39). Python 3 standard library only.

    python3 pridechat-relay.py --port 8765 --secret CHANGE-ME

Servers POST /send {"server","name","mode","channel","text"} and long-poll GET /poll?since=N&server=Name.
Keeps the last 500 lines in memory (nothing on disk). Every request needs the header X-PrideChat-Secret.
"""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

KEEP = 500
lines = []          # [{"seq", "server", "name", "mode", "channel", "text"}]
seq = 0
cond = threading.Condition()
SECRET = ""


class Relay(BaseHTTPRequestHandler):
    def _reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _ok(self):
        if self.headers.get("X-PrideChat-Secret", "") != SECRET:
            self._reply(403, {"error": "bad secret"})
            return False
        return True

    def do_POST(self):
        global seq
        if urlparse(self.path).path != "/send":
            return self._reply(404, {})
        if not self._ok():
            return
        try:
            n = min(int(self.headers.get("Content-Length", 0)), 200_000)
            m = json.loads(self.rfile.read(n))
            line = {k: str(m.get(k, ""))[:100_000] for k in ("server", "name", "mode", "channel", "text")}
        except Exception:
            return self._reply(400, {"error": "bad json"})
        with cond:
            seq += 1
            line["seq"] = seq
            lines.append(line)
            del lines[:-KEEP]
            cond.notify_all()
        self._reply(200, {"seq": seq})

    def do_GET(self):
        u = urlparse(self.path)
        if u.path != "/poll":
            return self._reply(404, {})
        if not self._ok():
            return
        q = parse_qs(u.query)
        since = int(q.get("since", ["-1"])[0])
        with cond:
            if since >= 0 and seq <= since:
                cond.wait(timeout=20)          # long poll: answer as soon as something new arrives
            new = [l for l in lines if since >= 0 and l["seq"] > since]
            self._reply(200, {"seq": seq, "messages": new})

    def log_message(self, *a):
        pass


def main():
    global SECRET
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--secret", required=True)
    a = ap.parse_args()
    SECRET = a.secret
    print(f"PrideChat relay on {a.host}:{a.port}")
    ThreadingHTTPServer((a.host, a.port), Relay).serve_forever()


if __name__ == "__main__":
    main()
