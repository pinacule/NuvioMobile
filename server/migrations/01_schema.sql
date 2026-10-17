-- 01_schema.sql — Nuvio self-hosted sync server: base schema.
--
-- Plain PostgreSQL 15. Run once against the supabase/postgres database
-- (which already provides the `auth` schema and the anon/authenticated
-- roles). All sync tables live in `public`.
--
-- Conventions (verified against the Kotlin adapters in
-- composeApp/src/commonMain/kotlin/com/nuvio/app):
--   * Profiles are keyed by (auth user uuid, profile_index int). The app
--     sends p_profile_id on every call; the RPC resolves user_id from
--     auth.uid() — the client never sends user_id.
--   * library / watch-progress / watched are event-sourced: an items table
--     plus an event-log table whose event_id bigserial is the delta cursor.
--   * Every mutating RPC takes p_origin_client_id (echo suppression) —
--     stored in the event logs as origin_client_id.
--   * Epoch-millisecond values the client sends (added_at, last_watched,
--     watched_at) are stored as timestamptz; the RPC layer converts.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Addons: the only table the app reads directly via PostgREST (see RLS file).
-- Columns match AddonRow in features/addons/AddonRepository.kt.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS addons (
    user_id    uuid    NOT NULL,
    profile_id integer NOT NULL,
    url        text    NOT NULL,
    name       text,
    enabled    boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, profile_id, url)
);

-- ---------------------------------------------------------------------------
-- Profiles. Columns match ProfilePushPayload / NuvioProfile in
-- features/profiles/ProfileModels.kt and ProfileLockState in
-- features/profiles/ProfileRepository.kt.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS profiles (
    user_id                uuid        NOT NULL,
    profile_index          integer     NOT NULL,
    name                   text,
    avatar_color_hex       text,
    avatar_id              text,
    avatar_url             text,
    profile_background_id  text,
    profile_background_url text,
    uses_primary_addons    boolean     DEFAULT true,
    uses_primary_plugins   boolean     DEFAULT false,
    pin_hash               text,
    pin_enabled            boolean     DEFAULT false,
    pin_locked_until       timestamptz,
    pin_failed_attempts    integer     DEFAULT 0,
    created_at             timestamptz DEFAULT now(),
    updated_at             timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_index)
);

-- ---------------------------------------------------------------------------
-- Library (event-sourced). Item columns match LibrarySyncItem in
-- features/library/sync/SupabaseLibrarySyncAdapter.kt.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS library_items (
    user_id        uuid,
    profile_id     integer,
    content_id     text,
    content_type   text,
    name           text,
    poster         text,
    poster_shape   text,
    background     text,
    logo           text,
    description    text,
    release_info   text,
    imdb_rating    numeric,
    genres         text[],
    addon_base_url text,
    added_at       timestamptz,
    updated_at     timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id, content_id, content_type)
);

CREATE TABLE IF NOT EXISTS library_events (
    event_id         bigserial PRIMARY KEY,
    user_id          uuid NOT NULL,
    profile_id       integer NOT NULL,
    content_id       text,
    content_type     text,
    operation        text NOT NULL,
    origin_client_id text,
    created_at       timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS library_events_user_profile_event_idx
    ON library_events (user_id, profile_id, event_id);

-- ---------------------------------------------------------------------------
-- Watch progress (event-sourced). Item columns match WatchProgressSyncEntry in
-- features/watching/sync/SupabaseProgressSyncAdapter.kt.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS watch_progress (
    user_id      uuid,
    profile_id   integer,
    progress_key text,
    content_id   text,
    content_type text,
    video_id     text,
    season       integer,
    episode      integer,
    position     bigint,
    duration     bigint,
    last_watched timestamptz,
    updated_at   timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id, progress_key)
);

CREATE TABLE IF NOT EXISTS watch_progress_events (
    event_id         bigserial PRIMARY KEY,
    user_id          uuid,
    profile_id       integer,
    progress_key     text,
    operation        text,
    origin_client_id text,
    created_at       timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS watch_progress_events_user_profile_event_idx
    ON watch_progress_events (user_id, profile_id, event_id);

-- ---------------------------------------------------------------------------
-- Watched items (event-sourced). Item columns match WatchedSyncItem and the
-- delete-key shape (content_id, season, episode) in
-- features/watching/sync/SupabaseWatchedSyncAdapter.kt.
-- ---------------------------------------------------------------------------
-- NOTE: season/episode are NULL for movies, so the uniqueness key cannot be a
-- plain PRIMARY KEY (PK columns are implicitly NOT NULL). NULLS NOT DISTINCT
-- (Postgres 15+) treats NULLs as equal for dedup; upserts in the RPC layer
-- use explicit IS NOT DISTINCT FROM matching rather than ON CONFLICT.
CREATE TABLE IF NOT EXISTS watched_items (
    user_id      uuid,
    profile_id   integer,
    content_id   text,
    content_type text,
    title        text,
    season       integer,
    episode      integer,
    watched_at   timestamptz,
    updated_at   timestamptz DEFAULT now(),
    CONSTRAINT watched_items_unique_key UNIQUE NULLS NOT DISTINCT
        (user_id, profile_id, content_id, content_type, season, episode)
);

CREATE TABLE IF NOT EXISTS watched_events (
    event_id         bigserial PRIMARY KEY,
    user_id          uuid,
    profile_id       integer,
    content_id       text,
    content_type     text,
    season           integer,
    episode          integer,
    operation        text,
    origin_client_id text,
    created_at       timestamptz DEFAULT now()
);
CREATE INDEX IF NOT EXISTS watched_events_user_profile_event_idx
    ON watched_events (user_id, profile_id, event_id);

-- ---------------------------------------------------------------------------
-- Collections blob. Column matches SupabaseCollectionBlob in
-- features/collection/CollectionModels.kt (collections_json); the app decodes
-- it as a JSON *array* of collections (see CollectionSyncService.kt).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collections (
    user_id          uuid,
    profile_id       integer,
    collections_json jsonb NOT NULL DEFAULT '[]',
    updated_at       timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id)
);

-- ---------------------------------------------------------------------------
-- Profile settings blob, one per (profile, platform). platform = 'mobile' on
-- mobile (MOBILE_SYNC_PLATFORM in core/sync/SyncPlatform.kt). settings_json
-- decodes to the mobile settings blob (ProfileSettingsSync.kt).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS profile_settings_blobs (
    user_id       uuid,
    profile_id    integer,
    platform      text,
    settings_json jsonb NOT NULL DEFAULT '{}',
    updated_at    timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id, platform)
);

-- ---------------------------------------------------------------------------
-- Media servers blob. The client pushes p_servers (array of SyncedServer) via
-- sync_push_media_servers and pulls rows decoding servers_json (array) — see
-- core/sync/MediaServerSync.kt.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS media_servers (
    user_id      uuid,
    profile_id   integer,
    servers_json jsonb NOT NULL DEFAULT '[]',
    updated_at   timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id)
);

-- ---------------------------------------------------------------------------
-- Provider credentials blob. The client pushes p_credentials (array of
-- {provider, credential_json}) via sync_push_provider_credentials and pulls
-- rows of {provider, credential_json, updated_at} — see
-- core/sync/ProviderCredentialSync.kt and ProviderCredentialModels.kt.
-- The RPC layer unpacks/packs credentials_json.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS provider_credentials (
    user_id          uuid,
    profile_id       integer,
    credentials_json jsonb NOT NULL DEFAULT '[]',
    updated_at       timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id)
);

-- ---------------------------------------------------------------------------
-- Home catalog settings blob, one per (profile, platform). platform =
-- 'home_catalog_shared' (HOME_CATALOG_SHARED_SYNC_PLATFORM in
-- core/sync/SyncPlatform.kt); settings_json decodes to SyncHomeCatalogPayload
-- (features/home/HomeCatalogSettingsSyncService.kt).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS home_catalog_settings (
    user_id       uuid,
    profile_id    integer,
    platform      text NOT NULL DEFAULT 'home_catalog_shared',
    settings_json jsonb NOT NULL DEFAULT '{}',
    updated_at    timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id, platform)
);

-- ---------------------------------------------------------------------------
-- Registered devices. Params match register_current_device (p_installation_id,
-- p_client_name, p_client_version, p_platform, p_device_name) in
-- core/auth/DeviceSessionRegistration.kt.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS devices (
    user_id        uuid NOT NULL,
    installation_id text NOT NULL,
    client_name    text,
    client_version text,
    platform       text,
    device_name    text,
    last_seen      timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, installation_id)
);

-- ---------------------------------------------------------------------------
-- Device-link (TV-style) login sessions. Flows match
-- core/auth/DeviceLinkAuthRepository.kt:
--   start_device_login_session(p_device_nonce, p_redirect_base_url,
--     p_device_name, p_device_type) -> {device_code, user_code,
--     verification_uri_complete, poll_interval_seconds}
--   poll_tv_login_session(p_code, p_device_nonce) -> {status}
--   tv-logins-exchange {code, device_nonce} -> GoTrue tokens
-- device_nonce proves the polling/exchanging device owns the session.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_login_sessions (
    device_code            text PRIMARY KEY,
    device_nonce           text NOT NULL,
    user_code              text UNIQUE NOT NULL,
    user_id                uuid,
    device_name            text,
    device_type            text,
    redirect_base_url      text,
    verification_uri_complete text,
    poll_interval_seconds  integer DEFAULT 3,
    status                 text NOT NULL DEFAULT 'pending',
    expires_at             timestamptz NOT NULL,
    approved_at            timestamptz,
    created_at             timestamptz DEFAULT now()
);
