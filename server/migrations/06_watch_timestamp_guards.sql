-- 06_watch_timestamp_guards.sql — Nuvio sync server: timestamp-guarded
-- watch-progress / watched pushes (audit finding M10).
--
-- Plain PostgreSQL 15 / plpgsql. Runs after 01_schema.sql + 02_rls.sql.
-- Migrations are additive and re-runnable: both functions below are
-- CREATE OR REPLACE with unchanged signatures, so existing installs pick
-- the guards up on the next `docker compose up` with no data migration.
--
-- Before this file, sync_push_watch_progress and sync_push_watched_items
-- overwrote the stored row unconditionally: whichever device pushed LAST
-- won, even if its position/watched stamp was older (e.g. a stale device
-- coming back online and re-pushing an old snapshot). Now a stored row is
-- only overwritten when the incoming stamp is at least as new as the
-- stored one, and the delta event is only logged when the write actually
-- applied (a skipped stale push must not look like a change to peers).
--
-- The client stamps are device wall-clock (WatchProgressClock /
-- markedAtEpochMs), so a device whose clock is set far ahead could mint
-- "newest" stamps forever and keep beating genuinely newer progress from
-- other devices. Incoming stamps implausibly far in the future (more than
-- 5 minutes ahead of the server clock) are therefore clamped to the
-- server's now() before the comparison — the write still lands, it just
-- can't win against the future.

-- ===========================================================================
-- Watch progress
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_push_watch_progress(
    p_profile_id int,
    p_entries jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    elem jsonb;
    v_last_watched timestamptz;
BEGIN
    IF p_entries IS NULL OR jsonb_typeof(p_entries) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_entries)
    LOOP
        IF elem->>'progress_key' IS NULL THEN
            CONTINUE;
        END IF;
        v_last_watched := CASE WHEN elem->>'last_watched' IS NULL THEN NULL
             ELSE to_timestamp((elem->>'last_watched')::double precision / 1000.0)
        END;
        -- Clamp future stamps from skewed device clocks to server time.
        IF v_last_watched IS NOT NULL AND v_last_watched > now() + interval '5 minutes' THEN
            v_last_watched := now();
        END IF;
        -- The event insert is driven by the upsert's RETURNING via a CTE:
        -- when the timestamp guard skips the update, no row comes back and
        -- no delta event is written for the stale element.
        WITH applied AS (
            INSERT INTO watch_progress AS w (
                user_id, profile_id, progress_key, content_id, content_type,
                video_id, season, episode, position, duration, last_watched,
                updated_at
            ) VALUES (
                v_user_id, p_profile_id,
                elem->>'progress_key',
                coalesce(elem->>'content_id', ''),
                coalesce(elem->>'content_type', ''),
                coalesce(elem->>'video_id', ''),
                (elem->>'season')::int,
                (elem->>'episode')::int,
                coalesce((elem->>'position')::bigint, 0),
                coalesce((elem->>'duration')::bigint, 0),
                v_last_watched,
                now()
            )
            ON CONFLICT (user_id, profile_id, progress_key) DO UPDATE SET
                content_id = EXCLUDED.content_id,
                content_type = EXCLUDED.content_type,
                video_id = EXCLUDED.video_id,
                season = EXCLUDED.season,
                episode = EXCLUDED.episode,
                position = EXCLUDED.position,
                duration = EXCLUDED.duration,
                last_watched = EXCLUDED.last_watched,
                updated_at = now()
            WHERE w.last_watched IS NULL
               OR EXCLUDED.last_watched >= w.last_watched
            RETURNING progress_key
        )
        INSERT INTO watch_progress_events (
            user_id, profile_id, progress_key, operation, origin_client_id
        )
        SELECT v_user_id, p_profile_id, progress_key, 'upsert', p_origin_client_id
        FROM applied;
    END LOOP;
END;
$$;

-- ===========================================================================
-- Watched items
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_push_watched_items(
    p_profile_id int,
    p_items jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    elem jsonb;
    v_watched_at timestamptz;
    v_existing_watched_at timestamptz;
BEGIN
    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        IF elem->>'content_id' IS NULL THEN
            CONTINUE;
        END IF;
        v_watched_at := CASE WHEN elem->>'watched_at' IS NULL THEN NULL
             ELSE to_timestamp((elem->>'watched_at')::double precision / 1000.0)
        END;
        -- Clamp future stamps from skewed device clocks to server time.
        IF v_watched_at IS NOT NULL AND v_watched_at > now() + interval '5 minutes' THEN
            v_watched_at := now();
        END IF;
        -- Manual match: season/episode are NULL for movies, and ON CONFLICT
        -- arbiters don't match NULL keys, so match with IS NOT DISTINCT FROM.
        -- The pre-read also distinguishes "row exists but is newer" (skip —
        -- the UPDATE/INSERT dance below cannot tell that apart from
        -- "row missing", and a blind INSERT would hit the unique key) from
        -- "row missing" (insert).
        SELECT wi.watched_at INTO v_existing_watched_at
        FROM watched_items AS wi
        WHERE wi.user_id = v_user_id
          AND wi.profile_id = p_profile_id
          AND wi.content_id = elem->>'content_id'
          AND wi.content_type = coalesce(elem->>'content_type', '')
          AND wi.season IS NOT DISTINCT FROM (elem->>'season')::int
          AND wi.episode IS NOT DISTINCT FROM (elem->>'episode')::int;
        IF FOUND
           AND v_existing_watched_at IS NOT NULL
           AND (v_watched_at IS NULL OR v_watched_at < v_existing_watched_at) THEN
            -- A newer mark is already stored; this push is stale. Skip the
            -- write and its event entirely.
            CONTINUE;
        END IF;
        UPDATE watched_items AS wi SET
            title = coalesce(elem->>'title', ''),
            watched_at = v_watched_at,
            updated_at = now()
        WHERE wi.user_id = v_user_id
          AND wi.profile_id = p_profile_id
          AND wi.content_id = elem->>'content_id'
          AND wi.content_type = coalesce(elem->>'content_type', '')
          AND wi.season IS NOT DISTINCT FROM (elem->>'season')::int
          AND wi.episode IS NOT DISTINCT FROM (elem->>'episode')::int;
        IF NOT FOUND THEN
            INSERT INTO watched_items (
                user_id, profile_id, content_id, content_type, title,
                season, episode, watched_at, updated_at
            ) VALUES (
                v_user_id, p_profile_id,
                elem->>'content_id',
                coalesce(elem->>'content_type', ''),
                coalesce(elem->>'title', ''),
                (elem->>'season')::int,
                (elem->>'episode')::int,
                v_watched_at,
                now()
            );
        END IF;
        INSERT INTO watched_events (
            user_id, profile_id, content_id, content_type, season, episode,
            operation, origin_client_id
        ) VALUES (
            v_user_id, p_profile_id,
            elem->>'content_id',
            coalesce(elem->>'content_type', ''),
            (elem->>'season')::int,
            (elem->>'episode')::int,
            'upsert', p_origin_client_id
        );
    END LOOP;
END;
$$;

-- Signatures are unchanged, so the grants from 03_functions.sql still
-- apply; they are restated here to keep this file self-contained.
GRANT EXECUTE ON FUNCTION public.sync_push_watch_progress(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_watched_items(int, jsonb, text) TO authenticated;
