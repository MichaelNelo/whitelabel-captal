# A stand-in for a venue's UniFi controller, for the local configuration only: the two calls of the
# Network Integration v1 API a location's API makes, over HTTPS as a controller answers them, and a
# record of what it was asked, over plain HTTP, for the end-to-end features to read.
#
#   GET  /proxy/network/integration/v1/sites/{site}/clients?filter=macAddress.eq('<mac>')
#        Any MAC is a connected client, with an id derived from it.
#   POST /proxy/network/integration/v1/sites/{site}/clients/{id}/actions
#        The action is recorded and answered 200.
#
#   GET  /calls[?mac=<mac>]   every action recorded, oldest first; one MAC's alone with `mac`
#   GET  /health
#
# Run by the task as `python -c`, from the `CONTROLLER` variable; its certificate and key arrive in
# `TLS_CERT` and `TLS_KEY`.
import json
import os
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

SITES = "/proxy/network/integration/v1/sites/"
calls = []
lock = threading.Lock()


def client_id(mac):
    return "client-" + mac.replace(":", "")


def mac_of(client):
    hexes = client[len("client-"):]
    return ":".join(hexes[i:i + 2] for i in range(0, len(hexes), 2))


class Handler(BaseHTTPRequestHandler):
    def answer(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass


class Controller(Handler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path.startswith(SITES) and url.path.endswith("/clients"):
            wanted = parse_qs(url.query).get("filter", [""])[0]
            mac = wanted.split("'")[1] if wanted.count("'") == 2 else None
            data = [{"id": client_id(mac), "macAddress": mac}] if mac else []
            self.answer(200, {"offset": 0, "count": len(data), "totalCount": len(data), "data": data})
        else:
            self.answer(404, {"message": "not a call this stand-in knows"})

    def do_POST(self):
        url = urlparse(self.path)
        parts = url.path[len(SITES):].split("/") if url.path.startswith(SITES) else []
        if len(parts) == 4 and parts[1] == "clients" and parts[3] == "actions":
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
            call = {
                "site": parts[0],
                "mac": mac_of(parts[2]),
                "action": body.get("action"),
                "timeLimitMinutes": body.get("timeLimitMinutes"),
                "apiKey": self.headers.get("X-API-KEY"),
                "at": time.time(),
            }
            with lock:
                calls.append(call)
            print(json.dumps(call), flush=True)
            self.answer(200, {})
        else:
            self.answer(404, {"message": "not a call this stand-in knows"})


class Records(Handler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            self.answer(200, {"status": "ok"})
        elif url.path == "/calls":
            mac = parse_qs(url.query).get("mac", [None])[0]
            with lock:
                found = [call for call in calls if mac is None or call["mac"] == mac.lower()]
            self.answer(200, found)
        else:
            self.answer(404, {})


def serve_tls(port):
    with open("/tmp/cert.pem", "w") as cert, open("/tmp/key.pem", "w") as key:
        cert.write(os.environ["TLS_CERT"])
        key.write(os.environ["TLS_KEY"])
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain("/tmp/cert.pem", "/tmp/key.pem")
    server = ThreadingHTTPServer(("0.0.0.0", port), Controller)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


threading.Thread(target=serve_tls, args=(int(os.environ["CONTROLLER_PORT"]),), daemon=True).start()
print("UniFi stand-in answering", flush=True)
ThreadingHTTPServer(("0.0.0.0", int(os.environ["RECORDS_PORT"])), Records).serve_forever()
