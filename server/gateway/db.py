"""Database layer for the Nuvio self-hosted sync server gateway.

Uses psycopg v3 and PyJWT. All connections come from the DATABASE_URL
environment variable (a postgres:// DSN pointing at the self-hosted DB).
"""
from __future__ import annotations

import os
import secrets
import time
from typing import Any, Optional

import jwt
import psycopg
from psycopg.rows import dict_row

# App tables keyed by user_id that must be wiped on account deletion.
# Falls back to the list from the task spec; can be overridden with the
# NUVIO_APP_TABLES env var (comma-separated) if the schema changes.
DEFAULT_APP_TABLES = [
    "addons",
    "profiles",
    "library_items",
    "library_events",
    "watch_progress",
    "watch_progress_events",
    "watched_items",
    "watched_events",
    "collections",
    "profile_settings_blobs",
    "media_servers",
    "provider_credentials",
    "home_catalog_settings",
    "devices",
    "device_login_sessions",
]


def _env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"Required environment variable {name} is not set")
    return value


def connect():
    """Open a new psycopg connection to the self-hosted Postgres."""
    return psycopg.connect(os.environ.get("DATABASE_URL", ""), row_factory=dict_row)


def app_tables() -> list[str]:
    override = os.environ.get("NUVIO_APP_TABLES")
    if override:
        return [t.strip() for t in override.split(",") if t.strip()]
    return list(DEFAULT_APP_TABLES)


def get_user_id_from_jwt(token: str) -> Optional[str]:
    """Verify an HS256 user JWT and return its ``sub`` (user UUID), or None.

    Used by /functions/v1/delete-account: only a real user session token is
    accepted, not the publishable (anon) key.
    """
    try:
        claims = jwt.decode(
            token,
            _env("JWT_SECRET"),
            algorithms=["HS256"],
            options={"require": ["exp", "sub"]},
        )
    except jwt.PyJWTError:
        return None
    if claims.get("role") != "authenticated":
        return None
    sub = claims.get("sub")
    return str(sub) if sub else None


def delete_user_data(user_id: str) -> None:
    """Delete every app row for ``user_id`` and then the auth.users row.

    Identities and other auth-schema children cascade from auth.users.
    """
    tables = app_tables()
    with connect() as conn:
        with conn.cursor() as cur:
            for table in tables:
                cur.execute(f'DELETE FROM "{table}" WHERE user_id = %s', (user_id,))
            cur.execute("DELETE FROM auth.users WHERE id = %s", (user_id,))
        conn.commit()


def get_session_by_device_code(device_code: str) -> Optional[dict[str, Any]]:
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT device_code, user_code, user_id, device_name, device_nonce,
                          status, expires_at, approved_at
                   FROM device_login_sessions
                   WHERE device_code = %s""",
                (device_code,),
            )
            return cur.fetchone()


def get_session_by_user_code(user_code: str) -> Optional[dict[str, Any]]:
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT device_code, user_code, user_id, device_name, device_nonce,
                          status, expires_at, approved_at
                   FROM device_login_sessions
                   WHERE user_code = %s""",
                (user_code,),
            )
            return cur.fetchone()


def approve_session(user_code: str, user_id: str) -> bool:
    """Mark a pending session approved by ``user_id``. True if a row changed."""
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """UPDATE device_login_sessions
                   SET status = 'approved', user_id = %s, approved_at = now()
                   WHERE user_code = %s AND status = 'pending'""",
                (user_id, user_code),
            )
            ok = cur.rowcount > 0
        conn.commit()
    return ok


def exchange_session(device_code: str, device_nonce: str) -> dict[str, Any]:
    """Validate an approved TV login session and mint a GoTrue-style session.

    Returns {"access_token", "refresh_token", "token_type", "expires_in", "user"}.
    Raises ValueError with a machine-readable reason on any failure.

    NOTE: hand-minting refresh tokens this way is the standard trick for custom
    OAuth-style flows, but whether GoTrue accepts the refresh token on
    /auth/v1/token?grant_type=refresh_token depends on its internals (token
    format/hashing expectations). Flagged as unverifiable without a live stack.
    """
    now = int(time.time())
    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT device_code, user_code, user_id, device_nonce, status, expires_at
                   FROM device_login_sessions
                   WHERE device_code = %s
                   FOR UPDATE""",
                (device_code,),
            )
            row = cur.fetchone()
            if row is None:
                raise ValueError("unknown_code")
            if row["status"] != "approved":
                raise ValueError("not_approved")
            if row["device_nonce"] != device_nonce:
                raise ValueError("nonce_mismatch")
            if row["expires_at"] is not None and row["expires_at"].timestamp() <= now:
                raise ValueError("expired")
            user_id = str(row["user_id"])
            if not user_id:
                raise ValueError("not_approved")

            cur.execute("SELECT id FROM auth.instances LIMIT 1")
            inst = cur.fetchone()
            if inst is None:
                raise ValueError("no_instance")
            instance_id = inst["id"]

            access_token = jwt.encode(
                {
                    "aud": "authenticated",
                    "exp": now + 3600,
                    "iat": now,
                    "iss": "supabase",
                    "sub": user_id,
                    "role": "authenticated",
                },
                _env("JWT_SECRET"),
                algorithm="HS256",
            )
            refresh_token = secrets.token_urlsafe(32)
            cur.execute(
                """INSERT INTO auth.refresh_tokens (instance_id, token, user_id, revoked)
                   VALUES (%s, %s, %s, false)""",
                (instance_id, refresh_token, user_id),
            )
            cur.execute("SELECT email FROM auth.users WHERE id = %s", (user_id,))
            user_row = cur.fetchone()
            if user_row is None:
                raise ValueError("unknown_user")

            # Consume the code so it cannot be replayed.
            cur.execute(
                "UPDATE device_login_sessions SET status = 'exchanged' WHERE device_code = %s",
                (device_code,),
            )
        conn.commit()

    return {
        "access_token": access_token,
        "refresh_token": refresh_token,
        "token_type": "bearer",
        "expires_in": 3600,
        "user": {
            "id": user_id,
            "aud": "authenticated",
            "email": user_row["email"],
            "role": "authenticated",
        },
    }
