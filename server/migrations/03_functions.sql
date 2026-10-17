-- 03_functions.sql — Nuvio sync server: event-sourced domain RPCs
-- (library, watch progress, watched items).
--
-- Plain PostgreSQL 15 / plpgsql. Runs after 01_schema.sql + 02_rls.sql.
-- All functions: SECURITY DEFINER, SET search_path = public. Every query is
-- scoped by auth.uid() (caller UUID from the JWT); p_profile_id is the
-- profile_index / profile_id column. Never trust client-sent user ids.
--
-- Schema notes (from 01_schema.sql):
--   * item tables use column `profile_id`; `profiles` uses `profile_index`.
--   * epoch-millisecond values the client sends (added_at, last_watched,
--     watched_at) are stored as timestamptz; these RPCs convert both ways:
--       ms -> ts : to_timestamp(ms::double precision / 1000.0)
--       ts -> ms : (floor(extract(epoch from ts) * 1000))::bigint
--   * position/duration stay bigint on both sides.
--   * event tables carry origin_client_id (echo suppression) from
--     p_origin_client_id.
--
-- Decode shapes (verified against the Kotlin adapters):
--   * snapshot/delta pulls return row sets -> PostgREST serializes as JSON
--     arrays for decodeList<...>().
--   * sync_get_*_delta_cursor returns bigint -> decodeAs<Long>().
--   * Delta rows include event_id + operation. For delete events the item row
--     is gone, so required keys are COALESCE'd from the event row and
--     non-nullable scalars get safe defaults ('' / 0 / 'POSTER' / '{}'),
--     because kotlinx.serialization fails on explicit null for non-null
--     Kotlin types that only have defaults.

-- ===========================================================================
-- Library
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_library(
    p_profile_id int,
    p_limit int,
    p_offset int
)
RETURNS TABLE (
    content_id text,
    content_type text,
    name text,
    poster text,
    poster_shape text,
    background text,
    logo text,
    description text,
    release_info text,
    imdb_rating numeric,
    genres text[],
    addon_base_url text,
    added_at bigint
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        i.content_id,
        i.content_type,
        i.name,
        i.poster,
        i.poster_shape,
        i.background,
        i.logo,
        i.description,
        i.release_info,
        i.imdb_rating,
        i.genres,
        i.addon_base_url,
        coalesce((floor(extract(epoch from i.added_at) * 1000))::bigint, 0)
    FROM library_items AS i
    WHERE i.user_id = auth.uid()
      AND i.profile_id = p_profile_id
    ORDER BY i.added_at DESC NULLS LAST, i.content_id
    LIMIT p_limit
    OFFSET p_offset;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_get_library_delta_cursor(p_profile_id int)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN coalesce((
        SELECT max(e.event_id)
        FROM library_events AS e
        WHERE e.user_id = auth.uid()
          AND e.profile_id = p_profile_id
    ), 0);
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_pull_library_delta(
    p_profile_id int,
    p_since_event_id bigint,
    p_limit int
)
RETURNS TABLE (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    name text,
    poster text,
    poster_shape text,
    background text,
    logo text,
    description text,
    release_info text,
    imdb_rating numeric,
    genres text[],
    addon_base_url text,
    added_at bigint
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        e.event_id,
        e.operation,
        coalesce(i.content_id, e.content_id),
        coalesce(i.content_type, e.content_type),
        coalesce(i.name, ''),
        i.poster,
        coalesce(i.poster_shape, 'POSTER'),
        i.background,
        i.logo,
        i.description,
        i.release_info,
        i.imdb_rating,
        coalesce(i.genres, '{}'),
        i.addon_base_url,
        coalesce((floor(extract(epoch from i.added_at) * 1000))::bigint, 0)
    FROM library_events AS e
    LEFT JOIN library_items AS i
      ON i.user_id = e.user_id
     AND i.profile_id = e.profile_id
     AND i.content_id = e.content_id
     AND i.content_type = e.content_type
    WHERE e.user_id = auth.uid()
      AND e.profile_id = p_profile_id
      AND e.event_id > p_since_event_id
    ORDER BY e.event_id
    LIMIT p_limit;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_library_items(
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
    v_genres text[];
BEGIN
    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        IF elem->>'content_id' IS NULL OR elem->>'content_type' IS NULL THEN
            CONTINUE;
        END IF;
        v_genres := '{}';
        IF (elem ? 'genres') AND jsonb_typeof(elem->'genres') = 'array' THEN
            SELECT coalesce(array_agg(g), '{}') INTO v_genres
            FROM jsonb_array_elements_text(elem->'genres') AS g;
        END IF;
        INSERT INTO library_items AS li (
            user_id, profile_id, content_id, content_type, name, poster,
            poster_shape, background, logo, description, release_info,
            imdb_rating, genres, addon_base_url, added_at, updated_at
        ) VALUES (
            v_user_id, p_profile_id,
            elem->>'content_id', elem->>'content_type',
            elem->>'name', elem->>'poster',
            coalesce(elem->>'poster_shape', 'POSTER'),
            elem->>'background', elem->>'logo', elem->>'description',
            elem->>'release_info',
            (elem->>'imdb_rating')::numeric,
            v_genres,
            elem->>'addon_base_url',
            CASE WHEN elem->>'added_at' IS NULL THEN NULL
                 ELSE to_timestamp((elem->>'added_at')::double precision / 1000.0)
            END,
            now()
        )
        ON CONFLICT (user_id, profile_id, content_id, content_type) DO UPDATE SET
            name = EXCLUDED.name,
            poster = EXCLUDED.poster,
            poster_shape = EXCLUDED.poster_shape,
            background = EXCLUDED.background,
            logo = EXCLUDED.logo,
            description = EXCLUDED.description,
            release_info = EXCLUDED.release_info,
            imdb_rating = EXCLUDED.imdb_rating,
            genres = EXCLUDED.genres,
            addon_base_url = EXCLUDED.addon_base_url,
            added_at = EXCLUDED.added_at,
            updated_at = now();
        INSERT INTO library_events (
            user_id, profile_id, content_id, content_type, operation, origin_client_id
        ) VALUES (
            v_user_id, p_profile_id,
            elem->>'content_id', elem->>'content_type',
            'upsert', p_origin_client_id
        );
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_delete_library_items(
    p_profile_id int,
    p_keys jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    key jsonb;
BEGIN
    IF p_keys IS NULL OR jsonb_typeof(p_keys) <> 'array' THEN
        RETURN;
    END IF;
    FOR key IN SELECT * FROM jsonb_array_elements(p_keys)
    LOOP
        DELETE FROM library_items AS i
        WHERE i.user_id = v_user_id
          AND i.profile_id = p_profile_id
          AND i.content_id = key->>'content_id'
          AND i.content_type = key->>'content_type';
        INSERT INTO library_events (
            user_id, profile_id, content_id, content_type, operation, origin_client_id
        ) VALUES (
            v_user_id, p_profile_id,
            key->>'content_id', key->>'content_type',
            'delete', p_origin_client_id
        );
    END LOOP;
END;
$$;

-- ===========================================================================
-- Watch progress
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_watch_progress(
    p_profile_id int,
    p_since_last_watched bigint DEFAULT NULL,
    p_limit int DEFAULT NULL
)
RETURNS TABLE (
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season int,
    episode int,
    position bigint,
    duration bigint,
    last_watched bigint
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        w.progress_key,
        w.content_id,
        w.content_type,
        w.video_id,
        w.season,
        w.episode,
        w.position,
        w.duration,
        coalesce((floor(extract(epoch from w.last_watched) * 1000))::bigint, 0)
    FROM watch_progress AS w
    WHERE w.user_id = auth.uid()
      AND w.profile_id = p_profile_id
      AND (
            p_since_last_watched IS NULL
            OR w.last_watched > to_timestamp(p_since_last_watched::double precision / 1000.0)
          )
    ORDER BY w.last_watched DESC NULLS LAST
    LIMIT p_limit;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_get_watch_progress_delta_cursor(p_profile_id int)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN coalesce((
        SELECT max(e.event_id)
        FROM watch_progress_events AS e
        WHERE e.user_id = auth.uid()
          AND e.profile_id = p_profile_id
    ), 0);
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_pull_watch_progress_delta(
    p_profile_id int,
    p_since_event_id bigint,
    p_limit int
)
RETURNS TABLE (
    event_id bigint,
    operation text,
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season int,
    episode int,
    position bigint,
    duration bigint,
    last_watched bigint
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        e.event_id,
        e.operation,
        e.progress_key,
        coalesce(w.content_id, ''),
        coalesce(w.content_type, ''),
        coalesce(w.video_id, ''),
        w.season,
        w.episode,
        coalesce(w.position, 0),
        coalesce(w.duration, 0),
        coalesce((floor(extract(epoch from w.last_watched) * 1000))::bigint, 0)
    FROM watch_progress_events AS e
    LEFT JOIN watch_progress AS w
      ON w.user_id = e.user_id
     AND w.profile_id = e.profile_id
     AND w.progress_key = e.progress_key
    WHERE e.user_id = auth.uid()
      AND e.profile_id = p_profile_id
      AND e.event_id > p_since_event_id
    ORDER BY e.event_id
    LIMIT p_limit;
END;
$$;

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
BEGIN
    IF p_entries IS NULL OR jsonb_typeof(p_entries) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_entries)
    LOOP
        IF elem->>'progress_key' IS NULL THEN
            CONTINUE;
        END IF;
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
            CASE WHEN elem->>'last_watched' IS NULL THEN NULL
                 ELSE to_timestamp((elem->>'last_watched')::double precision / 1000.0)
            END,
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
            updated_at = now();
        INSERT INTO watch_progress_events (
            user_id, profile_id, progress_key, operation, origin_client_id
        ) VALUES (
            v_user_id, p_profile_id,
            elem->>'progress_key',
            'upsert', p_origin_client_id
        );
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_delete_watch_progress(
    p_profile_id int,
    p_keys jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_key text;
BEGIN
    IF p_keys IS NULL OR jsonb_typeof(p_keys) <> 'array' THEN
        RETURN;
    END IF;
    FOR v_key IN SELECT * FROM jsonb_array_elements_text(p_keys)
    LOOP
        DELETE FROM watch_progress AS w
        WHERE w.user_id = v_user_id
          AND w.profile_id = p_profile_id
          AND w.progress_key = v_key;
        INSERT INTO watch_progress_events (
            user_id, profile_id, progress_key, operation, origin_client_id
        ) VALUES (
            v_user_id, p_profile_id, v_key, 'delete', p_origin_client_id
        );
    END LOOP;
END;
$$;

-- ===========================================================================
-- Watched items
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_watched_items(
    p_profile_id int,
    p_page int,
    p_page_size int
)
RETURNS TABLE (
    content_id text,
    content_type text,
    title text,
    season int,
    episode int,
    watched_at bigint
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_offset int := GREATEST(coalesce(p_page, 1) - 1, 0) * GREATEST(coalesce(p_page_size, 100), 1);
BEGIN
    RETURN QUERY
    SELECT
        i.content_id,
        i.content_type,
        i.title,
        i.season,
        i.episode,
        coalesce((floor(extract(epoch from i.watched_at) * 1000))::bigint, 0)
    FROM watched_items AS i
    WHERE i.user_id = auth.uid()
      AND i.profile_id = p_profile_id
    ORDER BY i.watched_at DESC NULLS LAST, i.content_id
    LIMIT p_page_size
    OFFSET v_offset;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_get_watched_items_delta_cursor(p_profile_id int)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN coalesce((
        SELECT max(e.event_id)
        FROM watched_events AS e
        WHERE e.user_id = auth.uid()
          AND e.profile_id = p_profile_id
    ), 0);
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_pull_watched_items_delta(
    p_profile_id int,
    p_since_event_id bigint,
    p_limit int
)
RETURNS TABLE (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    title text,
    season int,
    episode int,
    watched_at bigint
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        e.event_id,
        e.operation,
        coalesce(i.content_id, e.content_id),
        coalesce(i.content_type, e.content_type, ''),
        coalesce(i.title, ''),
        coalesce(i.season, e.season),
        coalesce(i.episode, e.episode),
        coalesce((floor(extract(epoch from i.watched_at) * 1000))::bigint, 0)
    FROM watched_events AS e
    LEFT JOIN watched_items AS i
      ON i.user_id = e.user_id
     AND i.profile_id = e.profile_id
     AND i.content_id = e.content_id
     AND i.content_type IS NOT DISTINCT FROM e.content_type
     AND i.season IS NOT DISTINCT FROM e.season
     AND i.episode IS NOT DISTINCT FROM e.episode
    WHERE e.user_id = auth.uid()
      AND e.profile_id = p_profile_id
      AND e.event_id > p_since_event_id
    ORDER BY e.event_id
    LIMIT p_limit;
END;
$$;

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
BEGIN
    IF p_items IS NULL OR jsonb_typeof(p_items) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_items)
    LOOP
        IF elem->>'content_id' IS NULL THEN
            CONTINUE;
        END IF;
        -- Manual upsert: season/episode are NULL for movies, and ON CONFLICT
        -- arbiters don't match NULL keys, so match with IS NOT DISTINCT FROM.
        UPDATE watched_items AS wi SET
            title = coalesce(elem->>'title', ''),
            watched_at = CASE WHEN elem->>'watched_at' IS NULL THEN NULL
                 ELSE to_timestamp((elem->>'watched_at')::double precision / 1000.0)
            END,
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
                CASE WHEN elem->>'watched_at' IS NULL THEN NULL
                     ELSE to_timestamp((elem->>'watched_at')::double precision / 1000.0)
                END,
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

CREATE OR REPLACE FUNCTION public.sync_delete_watched_items(
    p_profile_id int,
    p_keys jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    key jsonb;
    v_content_type text;
BEGIN
    IF p_keys IS NULL OR jsonb_typeof(p_keys) <> 'array' THEN
        RETURN;
    END IF;
    FOR key IN SELECT * FROM jsonb_array_elements(p_keys)
    LOOP
        -- WatchedDeltaSyncItem requires content_type, so capture it before delete.
        SELECT i.content_type INTO v_content_type
        FROM watched_items AS i
        WHERE i.user_id = v_user_id
          AND i.profile_id = p_profile_id
          AND i.content_id = key->>'content_id'
          AND i.season IS NOT DISTINCT FROM (key->>'season')::int
          AND i.episode IS NOT DISTINCT FROM (key->>'episode')::int
        LIMIT 1;
        DELETE FROM watched_items AS i
        WHERE i.user_id = v_user_id
          AND i.profile_id = p_profile_id
          AND i.content_id = key->>'content_id'
          AND i.season IS NOT DISTINCT FROM (key->>'season')::int
          AND i.episode IS NOT DISTINCT FROM (key->>'episode')::int;
        INSERT INTO watched_events (
            user_id, profile_id, content_id, content_type, season, episode,
            operation, origin_client_id
        ) VALUES (
            v_user_id, p_profile_id,
            key->>'content_id',
            coalesce(v_content_type, ''),
            (key->>'season')::int,
            (key->>'episode')::int,
            'delete', p_origin_client_id
        );
    END LOOP;
END;
$$;

-- ===========================================================================
-- Grants (PostgREST needs EXECUTE; RLS policies still apply to tables)
-- ===========================================================================

GRANT EXECUTE ON FUNCTION public.sync_pull_library(int, int, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_get_library_delta_cursor(int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_library_delta(int, bigint, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_library_items(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_delete_library_items(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_watch_progress(int, bigint, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_get_watch_progress_delta_cursor(int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_watch_progress_delta(int, bigint, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_watch_progress(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_delete_watch_progress(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_watched_items(int, int, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_get_watched_items_delta_cursor(int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_watched_items_delta(int, bigint, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_watched_items(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_delete_watched_items(int, jsonb, text) TO authenticated;
