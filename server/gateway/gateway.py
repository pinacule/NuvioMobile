"""Nuvio self-hosted sync server gateway.

Python 3 stdlib only (plus db.py, which needs psycopg and PyJWT).

Responsibilities:
  - GET /.well-known/nuvio      -> discovery document for the Nuvio app
  - /rest/v1/*                  -> reverse proxy to PostgREST
  - /auth/v1/*                  -> reverse proxy to GoTrue
  - POST /functions/v1/delete-account      -> wipe user data + auth.users row
  - POST /functions/v1/tv-logins-exchange -> complete the TV device-login flow
  - GET /link (+ POST /link/approve)      -> browser approval page for TV login
  - GET /healthz                -> liveness probe

Env vars:
  NUVIO_BACKEND_URL      public base URL of this gateway (for /.well-known/nuvio)
  NUVIO_PUBLISHABLE_KEY  anon JWT the app sends as apikey
  JWT_SECRET             HS256 secret shared with GoTrue/PostgREST
  DATABASE_URL           postgres DSN for db.py
  REST_URL               PostgREST base, default http://rest:3000
  AUTH_URL               GoTrue base,    default http://auth:9999
  PORT                   listen port,    default 8080
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import db

PORT = int(os.environ.get("PORT", "8080"))
REST_URL = os.environ.get("REST_URL", "http://rest:3000").rstrip("/")
AUTH_URL = os.environ.get("AUTH_URL", "http://auth:9999").rstrip("/")

# Headers that belong to a single hop and must not be forwarded upstream or
# relayed back downstream.
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
}


def _log(msg: str) -> None:
    print(f"[{time.strftime('%Y-%m-%dT%H:%M:%S')}] {msg}", flush=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "NuvioGateway/1.0"

    # -- helpers -----------------------------------------------------------
    def log_message(self, fmt, *args):  # noqa: N802 - BaseHTTPRequestHandler API
        _log("%s - %s" % (self.address_string(), fmt % args))

    def _send_json(self, status: int, obj, extra_headers: dict | None = None) -> None:
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, status: int, text: str, content_type: str = "text/html; charset=utf-8") -> None:
        body = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self) -> bytes:
        length = self.headers.get("Content-Length")
        if not length:
            return b""
        try:
            return self.rfile.read(int(length))
        except (ValueError, OSError):
            return b""

    def _cors_headers(self) -> dict:
        return {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "authorization, apikey, content-type, prefer, range, x-client-info",
            "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
        }

    def _bearer_token(self) -> str | None:
        auth = self.headers.get("Authorization", "")
        if auth.lower().startswith("bearer "):
            return auth[7:].strip()
        return None

    # -- routing ------------------------------------------------------------
    def do_OPTIONS(self):  # noqa: N802
        self.send_response(204)
        for k, v in self._cors_headers().items():
            self.send_header(k, v)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path == "/healthz":
            self._send_json(200, {"ok": True})
        elif path == "/.well-known/nuvio":
            self._send_json(200, {
                "version": 1,
                "service": "nuvio",
                "self_hosted": True,
                "backend_url": os.environ.get("NUVIO_BACKEND_URL", ""),
                "publishable_key": os.environ.get("NUVIO_PUBLISHABLE_KEY", ""),
                "capabilities": {
                    "email_password_auth": True,
                    "tv_login": True,
                    "watch_party": False,
                },
            })
        elif path == "/link":
            self._handle_link_page(urllib.parse.parse_qs(parsed.query))
        elif path.startswith("/rest/v1/") or path == "/rest/v1":
            self._proxy(REST_URL)
        elif path.startswith("/auth/v1/") or path == "/auth/v1":
            self._proxy(AUTH_URL)
        else:
            self._send_json(404, {"error": "not_found"})

    def do_POST(self):  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path == "/functions/v1/delete-account":
            self._handle_delete_account()
        elif path == "/functions/v1/tv-logins-exchange":
            self._handle_tv_exchange()
        elif path == "/link/approve":
            self._handle_link_approve()
        elif path.startswith("/rest/v1/") or path == "/rest/v1":
            self._proxy(REST_URL)
        elif path.startswith("/auth/v1/") or path == "/auth/v1":
            self._proxy(AUTH_URL)
        else:
            self._send_json(404, {"error": "not_found"})

    # allow PUT/PATCH/DELETE through the proxies too
    do_PUT = do_POST  # noqa: N815
    do_PATCH = do_POST  # noqa: N815
    do_DELETE = do_POST  # noqa: N815
    do_HEAD = do_GET  # noqa: N815

    # -- reverse proxy -------------------------------------------------------
    def _proxy(self, upstream: str) -> None:
        # Strip the /rest/v1 or /auth/v1 mount prefix and rebuild the upstream URL.
        parsed = urllib.parse.urlparse(self.path)
        prefix = "/rest/v1" if upstream == REST_URL else "/auth/v1"
        suffix = parsed.path[len(prefix):] or "/"
        url = upstream + suffix
        if parsed.query:
            url += "?" + parsed.query

        body = self._read_body() or None
        req = urllib.request.Request(url, data=body, method=self.command)
        for key, value in self.headers.items():
            if key.lower() in HOP_BY_HOP or key.lower() == "host":
                continue
            req.add_header(key, value)

        try:
            resp = urllib.request.urlopen(req, timeout=60)
            status, headers, data = resp.status, resp.headers, resp.read()
        except urllib.error.HTTPError as e:
            status, headers, data = e.code, e.headers, e.read()
        except (OSError, urllib.error.URLError) as e:
            _log(f"proxy {self.command} {self.path} -> upstream error: {e}")
            self._send_json(502, {"error": "upstream_unavailable"})
            return

        self.send_response(status)
        for key, value in headers.items():
            if key.lower() in HOP_BY_HOP or key.lower() == "content-length":
                continue
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # -- edge functions -------------------------------------------------------
    def _handle_delete_account(self) -> None:
        """supabase-kt functions.invoke("delete-account") — no body expected."""
        token = self._bearer_token()
        if not token:
            self._send_json(401, {"error": "missing_token"}, self._cors_headers())
            return
        user_id = db.get_user_id_from_jwt(token)
        if not user_id:
            self._send_json(401, {"error": "invalid_token"}, self._cors_headers())
            return
        try:
            db.delete_user_data(user_id)
        except Exception as e:  # noqa: BLE001 - surface DB failures as 500
            _log(f"delete-account for {user_id} failed: {e}")
            self._send_json(500, {"error": "delete_failed"}, self._cors_headers())
            return
        _log(f"delete-account ok for {user_id}")
        self._send_json(200, {"ok": True}, self._cors_headers())

    def _handle_tv_exchange(self) -> None:
        """supabase-kt functions.invoke("tv-logins-exchange", {code, device_nonce})."""
        try:
            payload = json.loads(self._read_body() or b"{}")
        except json.JSONDecodeError:
            payload = {}
        code = str(payload.get("code", ""))
        nonce = str(payload.get("device_nonce", ""))
        if not code or not nonce:
            self._send_json(400, {"error": "missing_params"}, self._cors_headers())
            return
        try:
            result = db.exchange_session(code, nonce)
        except ValueError as e:
            _log(f"tv-logins-exchange denied: {e}")
            self._send_json(400, {"error": str(e)}, self._cors_headers())
            return
        except Exception as e:  # noqa: BLE001
            _log(f"tv-logins-exchange failed: {e}")
            self._send_json(500, {"error": "exchange_failed"}, self._cors_headers())
            return
        self._send_json(200, result, self._cors_headers())

    # -- /link approval page ---------------------------------------------------
    def _handle_link_page(self, query: dict) -> None:
        code = (query.get("user_code") or [""])[0].strip()
        if not code:
            self._send_text(200, _LINK_FALLBACK_PAGE)
            return
        try:
            session = db.get_session_by_user_code(code)
        except Exception as e:  # noqa: BLE001
            _log(f"/link lookup failed: {e}")
            session = None
        if session is None:
            self._send_text(404, _LINK_ERROR_PAGE.format(
                message="This code wasn't recognised. It may have expired — "
                        "ask the TV to show a new code."))
            return
        status = str(session.get("status", ""))
        expires_at = session.get("expires_at")
        expired = expires_at is not None and expires_at.timestamp() <= time.time()
        if status != "pending" or expired:
            self._send_text(410, _LINK_ERROR_PAGE.format(
                message="This code has already been used or has expired. "
                        "Ask the TV to show a new code."))
            return
        device_name = str(session.get("device_name") or "your TV")
        self._send_text(200, _LINK_APPROVE_PAGE.format(
            code=code, device_name=_html_escape(device_name)))

    def _handle_link_approve(self) -> None:
        """POST /link/approve {user_code, email, password} -> approve the session."""
        try:
            payload = json.loads(self._read_body() or b"{}")
        except json.JSONDecodeError:
            payload = {}
        user_code = str(payload.get("user_code", "")).strip()
        email = str(payload.get("email", "")).strip()
        password = str(payload.get("password", ""))
        if not user_code or not email or not password:
            self._send_json(400, {"ok": False, "error": "missing_params"})
            return

        # Verify credentials against GoTrue's password grant.
        token_url = AUTH_URL + "/token?grant_type=password"
        body = json.dumps({"email": email, "password": password}).encode()
        req = urllib.request.Request(token_url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        apikey = os.environ.get("NUVIO_PUBLISHABLE_KEY", "")
        if apikey:
            req.add_header("apikey", apikey)
        try:
            resp = urllib.request.urlopen(req, timeout=30)
            auth_data = json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            _log(f"/link/approve: GoTrue rejected credentials ({e.code})")
            self._send_json(401, {"ok": False, "error": "invalid_credentials"})
            return
        except (OSError, urllib.error.URLError) as e:
            _log(f"/link/approve: GoTrue unreachable: {e}")
            self._send_json(502, {"ok": False, "error": "auth_unavailable"})
            return
        user = auth_data.get("user") or {}
        user_id = str(user.get("id") or "")
        if not user_id:
            self._send_json(401, {"ok": False, "error": "invalid_credentials"})
            return

        try:
            approved = db.approve_session(user_code, user_id)
        except Exception as e:  # noqa: BLE001
            _log(f"/link/approve DB error: {e}")
            self._send_json(500, {"ok": False, "error": "approve_failed"})
            return
        if not approved:
            self._send_json(410, {"ok": False, "error": "code_expired_or_used"})
            return
        _log(f"/link/approve ok for code ending ...{user_code[-3:]} user {user_id}")
        self._send_json(200, {"ok": True})


def _html_escape(s: str) -> str:
    return (s.replace("&", "&amp;").replace("<", "&lt;")
             .replace(">", "&gt;").replace('"', "&quot;"))


_LINK_FALLBACK_PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio — Link a device</title>
<style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
.card{max-width:26rem;padding:2rem;text-align:center}.code{font-size:2rem;letter-spacing:.2em;margin:1rem 0}</style>
</head><body><div class="card">
<h1>Link a device to Nuvio</h1>
<p>Open this link in the <strong>Nuvio app</strong> on the TV or device you want
to sign in, then enter the code shown there.</p>
<p>If you're setting up a TV, start <em>Sign in with a code</em> on the TV first —
it will show you a link with the code already filled in.</p>
</div></body></html>"""

_LINK_ERROR_PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio — Link expired</title>
<style>body{font-family:system-ui,sans-serif;background:#111;color:#eee;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
.card{max-width:26rem;padding:2rem;text-align:center}</style>
</head><body><div class="card">
<h1>Couldn't link this device</h1>
<p>{message}</p>
</div></body></html>"""

_LINK_APPROVE_PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nuvio — Approve sign-in</title>
<style>
body{{font-family:system-ui,sans-serif;background:#111;color:#eee;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}
.card{{max-width:22rem;width:100%;padding:2rem;background:#1c1c1e;border-radius:1rem}}
.code{{font-size:2.2rem;letter-spacing:.25em;text-align:center;margin:.5rem 0 1.5rem}}
label{{display:block;margin:.75rem 0 .25rem;font-size:.85rem;color:#aaa}}
input{{width:100%;padding:.6rem;border-radius:.5rem;border:1px solid #333;background:#0c0c0d;color:#eee;box-sizing:border-box}}
button{{width:100%;margin-top:1.25rem;padding:.75rem;border:0;border-radius:.5rem;background:#4f7cff;color:#fff;font-size:1rem;cursor:pointer}}
button:disabled{{opacity:.5;cursor:wait}}
#msg{{min-height:1.5em;margin-top:.75rem;font-size:.9rem;color:#ff9}}
.ok{{color:#7dffa8!important}}
</style></head><body><div class="card">
<h1>Sign in <span id="dev">{device_name}</span>?</h1>
<div class="code">{code}</div>
<p style="font-size:.85rem;color:#aaa">Check the code matches the one shown on your device,
then sign in with your Nuvio account to approve it.</p>
<form id="f">
<label for="email">Email</label>
<input id="email" type="email" autocomplete="email" required>
<label for="password">Password</label>
<input id="password" type="password" autocomplete="current-password" required>
<button id="btn" type="submit">Approve sign-in</button>
<div id="msg"></div>
</form></div>
<script>
const form = document.getElementById('f'), btn = document.getElementById('btn'),
      msg = document.getElementById('msg');
form.addEventListener('submit', async (e) => {{
  e.preventDefault();
  btn.disabled = true; msg.className = ''; msg.textContent = 'Approving…';
  try {{
    const r = await fetch('/link/approve', {{
      method: 'POST', headers: {{'Content-Type': 'application/json'}},
      body: JSON.stringify({{user_code: '{code}',
        email: document.getElementById('email').value,
        password: document.getElementById('password').value}})
    }});
    const d = await r.json();
    if (d.ok) {{
      msg.className = 'ok';
      msg.textContent = 'Approved! Your device should sign in shortly.';
      form.querySelectorAll('input,button').forEach(el => el.disabled = true);
    }} else {{
      msg.textContent = d.error === 'invalid_credentials'
        ? 'Wrong email or password.' : 'That code expired or was already used.';
      btn.disabled = false;
    }}
  }} catch (err) {{
    msg.textContent = 'Network error — try again.';
    btn.disabled = false;
  }}
}});
</script></body></html>"""


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    _log(f"nuvio gateway listening on :{PORT} "
          f"(rest={REST_URL} auth={AUTH_URL})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    server.server_close()


if __name__ == "__main__":
    main()
