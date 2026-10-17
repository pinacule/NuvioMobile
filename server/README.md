# Nuvio — self-hosted sync backend

Everything needed to run your own Nuvio sync backend instead of the hosted one.
One public port, five containers (four long-running + a one-shot migrator), docker compose, no Supabase account.

## Architecture

```
                                    :8080
┌─────────────┐  /.well-known/nuvio  ┌──────────┐
│  Nuvio app  │─────────────────────▶│ gateway  │
│             │  /rest/v1 ──proxy──▶ │ (custom, │
└─────────────┘  /auth/v1 ──proxy──▶ │  ./gateway│
                 /functions/v1/*     └─────┬────┘
                 /link (TV login)          │ REST_URL=http://rest:3000
                                           │ AUTH_URL=http://auth:9999
                                           │ DATABASE_URL (tv logins,
                                           │  delete-account)
                 ┌────────┐  ┌──────┐       │
                 │  rest  │  │ auth │       │
                 │PostgREST│ │GoTrue│       │
                 └───┬────┘  └──┬───┘       │
                     └────┬─────┘           │
                      ┌───┴───┐─────────────┘
                      │  db   │  postgres 15 (supabase image)
                      │       │  auth schema + anon/authenticated/
                      └───────┘  service_role roles
```

| Container | Image (verified 2026-10-17) | Job |
|---|---|---|
| `db` | `supabase/postgres:15.19.0.004` | Postgres 15. Supabase build: ships the `auth` schema and the `anon`/`authenticated`/`service_role` roles. `supabase_auth_admin` owns the auth schema; `authenticator` is the PostgREST entry role. |
| `migrate` | `postgres:16-alpine` | One-shot: applies `./migrations/*.sql` in order on every `docker compose up` (idempotent). `rest`/`auth`/`gateway` wait for it to succeed. |
| `rest` | `postgrest/postgrest:v16.4` | REST API over the `public` schema — the app's sync reads/writes go here (`/rest/v1`). |
| `auth` | `supabase/gotrue:v2.197.0` | Email+password sign-up/sign-in/refresh at `/auth/v1`. |
| `gateway` | built from `./gateway` | The only publicly exposed service (port 8080). Serves `/.well-known/nuvio` (the app's discovery doc), reverse-proxies `/rest/v1` and `/auth/v1`, and implements the edge-function equivalents `/functions/v1/delete-account` and `/functions/v1/tv-logins-exchange` plus `/link` for TV login. |

Only `gateway:8080` is published. `db`, `rest` and `auth` talk over the internal
compose network and are never directly reachable from outside.

## Prerequisites

- Docker Engine 24+ with the compose plugin (`docker compose version`).
- A public URL for the backend, e.g. `https://sync.example.com` (see reverse proxy notes).
- Nothing else — no cloud account, no Supabase project.

## Quickstart

```bash
cd server

# 1. Generate secrets (writes a ready-to-paste .env)
python3 scripts/gen-keys.py > .env

# 2. Set your public URL in .env
#    NUVIO_BACKEND_URL=https://sync.example.com   (no trailing slash)

# 3. Start everything (the migrate service applies ./migrations automatically)
docker compose up -d --build

# 4. Verify
curl http://localhost:8080/healthz
curl http://localhost:8080/.well-known/nuvio
```

`/.well-known/nuvio` should return something like:

```json
{
  "version": 1,
  "service": "nuvio",
  "self_hosted": true,
  "backend_url": "https://sync.example.com",
  "publishable_key": "<anon JWT>",
  "capabilities": {
    "email_password_auth": true,
    "tv_login": true,
    "watch_party": false
  }
}
```

## Pointing the app at it

On the Nuvio **sign-in screen**, open the server menu (top corner) and choose
the custom-server option. Enter your public URL, e.g. `https://sync.example.com`.
The app fetches `/.well-known/nuvio` to discover the API endpoints, the
publishable key, and the capabilities, shows a trust dialog, then signs up /
signs in against your backend with email + password (or the TV login flow).
Nothing else needs configuring. Switching servers signs you out locally, so
you'll sign in again on the new backend.

## Home-server notes

**Caddy reverse proxy + TLS (recommended on a home box):**

```caddy
sync.example.com {
    reverse_proxy localhost:8080
}
```

Caddy fetches a Let's Encrypt cert automatically. Put this in your `Caddyfile`,
reload, done. Tailscale Serve is also an option if you only want the server
reachable from your own devices — but the reverse proxy above is the simplest
path that works from mobile networks.

Note `db`, `rest` and `auth` expose **no** ports to the host, so the reverse
proxy is the only ingress point.

## VPS notes

- Firewall: allow inbound **443 and 80** only (80 for ACME HTTP challenges).
  Everything else closed.
- **Never expose db (5432), rest (3000) or auth (9999) directly.** There is no
  legitimate reason for the app to reach them; it always goes through the
  gateway on 8080/443.
- A 1 vCPU / 2 GB RAM VPS is plenty for personal/family use.

## Email

Out of the box, `GOTRUE_MAILER_AUTOCONFIRM=true`: signups work immediately and
no SMTP is needed. This is fine for a personal server where you trust your users.

To send real confirmation / password-reset emails instead, fill in the commented
`GOTRUE_SMTP_*` vars in `docker-compose.yml` and set
`GOTRUE_MAILER_AUTOCONFIRM=false`. Note that with autoconfirm disabled,
password-reset flows depend on your SMTP actually delivering.

## Backups

Postgres data lives in the `db-data` named volume. Back up with:

```bash
docker compose exec -T db pg_dump -U postgres postgres | gzip > nuvio-$(date +%F).sql.gz
```

Store the dump (and your `.env`) somewhere safe — the `JWT_SECRET` in `.env` is
required to keep existing API keys and sessions valid after a restore.

## Updating

```bash
docker compose pull
docker compose up -d
```

Migrations in `./migrations` are **additive** and numbered, and the `migrate`
service applies them on every `docker compose up` (they're written to be
re-runnable: `IF NOT EXISTS` / `CREATE OR REPLACE`). To add a schema change,
drop a new numbered file in `./migrations` and run `docker compose up -d` —
`migrate` picks it up automatically.

To pin or change image versions, edit the `image:` lines in `docker-compose.yml`.

## Security notes

- `JWT_SECRET` signs every API key and session. Generate it once with
  `scripts/gen-keys.py`, keep it in `.env` only, and **never commit `.env`**
  (it's in `.gitignore`). If it leaks, rotate it — but know that rotating
  invalidates all existing sessions and keys.
- `SERVICE_ROLE_KEY` bypasses PostgREST row-level security. Never ship it in the
  app or hand it to clients — it's for your own admin use only.
- `POSTGRES_PASSWORD` protects the `postgres`, `authenticator` and
  `supabase_auth_admin` roles. Use a strong one.

## Troubleshooting

```bash
docker compose logs -f            # everything
docker compose logs -f db         # postgres
docker compose ps                 # container health
```

Common issues:

- **`db` unhealthy / `pg_isready` failing** — first boot takes 30–60 s while
  the supabase image initializes. Check `docker compose logs db`.
- **`migrate` failing** — a migration has a SQL error. Check
  `docker compose logs migrate`, fix the file in `./migrations`, and run
  `docker compose up -d` again (migrations are idempotent, so re-running is
  safe).
- **`rest` 401s / "Invalid API key"** — `JWT_SECRET` in `.env` doesn't match what
  the keys were signed with. Regenerate `.env` and restart (`docker compose up -d`).
- **`auth` can't connect to db** — check `POSTGRES_PASSWORD` is set and that
  `db` is healthy; GoTrue only starts after `db` reports healthy.
- **App can't reach the server** — the app must use the *public*
  `NUVIO_BACKEND_URL` (e.g. `https://sync.example.com`), not `localhost`.
  `NUVIO_BACKEND_URL` must match what you put in `.env` before first boot.
- **Gateway build fails** — `./gateway` needs its `Dockerfile`, `gateway.py`,
  `db.py`, and `requirements.txt`; the compose file expects `build: ./gateway`.

## What was deliberately left out

- **Watch-party / Realtime** — the app's discovery doc advertises
  `"watch_party": false`. Realtime presence would need another stateful
  service; out of scope for the slim stack.
- **Storage avatars** — purely membership cosmetics. Out of scope initially;
  could be served as plain files later.

## Limitations

- **Not live-tested.** This compose setup was written against the app source and
  the upstream image docs; there was no Docker available in this environment to
  actually boot the stack. The first real boot should be treated as a smoke test:
  run the quickstart, then exercise sign-up → sync → TV login → delete-account.
- **Image tags verified (2026-10-17)**: `supabase/postgres:15.19.0.004`,
  `postgrest/postgrest:v16.4`, `supabase/gotrue:v2.197.0` all exist on Docker Hub
  as multi-arch (amd64+arm64) tags. GoTrue's latest tags at check time were
  `v2.198.0-rc.*` release candidates, so the compose pins the newest *stable*,
  `v2.197.0`.
- **GoTrue hand-minting caveat**: if you ever mint `auth.users` rows by hand
  (e.g. for the TV-login exchange), GoTrue expects its internal bookkeeping —
  particularly a matching `refresh_tokens` row for any session you create —
  otherwise `POST /auth/v1/token?grant_type=refresh_token` will 401. The gateway
  is written to go through GoTrue's own flows where possible; see
  `./gateway` for the details.
