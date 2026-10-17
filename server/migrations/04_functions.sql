-- 04_functions.sql — Nuvio sync server: profiles, PINs, blob sync, addons, devices.
--
-- Plain PostgreSQL 15 / plpgsql. Runs after 01_schema.sql + 02_rls.sql.
-- All functions: SECURITY DEFINER, SET search_path = public, scoped by
-- auth.uid(). Column names follow 01_schema.sql exactly (note: `profiles`
-- uses `profile_index`, every other per-profile table uses `profile_id`).
--
-- Decode shapes (verified against the Kotlin call sites):
--   * sync_pull_profiles / sync_pull_profile_locks -> row sets for
--     decodeList<NuvioProfile> / decodeList<ProfileLockState>.
--   * verify_profile_pin -> RETURNS jsonb single object for
--     decodeSingle<PinVerifyResult> {unlocked, retry_after_seconds, message}.
--   * PINs use pgcrypto bcrypt: crypt(p_pin, gen_salt('bf')) on set,
--     pin_hash = crypt(p_pin, pin_hash) on verify.
--   * Blob pulls return the row, or a synthetic empty row with the client's
--     defaults. EXCEPTION: sync_pull_profile_settings_blob returns an empty
--     row with settings_json = NULL (not '{}'), because the client treats
--     null as "no remote blob" while '{}' would decode to default settings
--     and could overwrite local settings.
--   * provider_credentials is stored as one jsonb array blob
--     (credentials_json); the RPC unpacks it into {provider, credential_json,
--     updated_at} rows for decodeList<SupabaseProviderCredential> and packs
--     it back on push.
--   * sync_push_provider_credentials: the client does NOT send
--     p_origin_client_id here, so it has DEFAULT NULL.

-- ===========================================================================
-- Profiles
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_profiles()
RETURNS TABLE (
    id text,
    user_id text,
    profile_index int,
    name text,
    avatar_color_hex text,
    avatar_id text,
    avatar_url text,
    profile_background_id text,
    profile_background_url text,
    uses_primary_addons boolean,
    uses_primary_plugins boolean,
    pin_enabled boolean,
    pin_locked_until timestamptz,
    created_at timestamptz,
    updated_at timestamptz
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        ''::text,
        p.user_id::text,
        p.profile_index,
        p.name,
        p.avatar_color_hex,
        p.avatar_id,
        p.avatar_url,
        p.profile_background_id,
        p.profile_background_url,
        p.uses_primary_addons,
        p.uses_primary_plugins,
        coalesce(p.pin_enabled, false),
        p.pin_locked_until,
        p.created_at,
        p.updated_at
    FROM profiles AS p
    WHERE p.user_id = auth.uid()
    ORDER BY p.profile_index;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_profiles(
    p_client_max_profiles int,
    p_profiles jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    elem jsonb;
    v_count int := 0;
BEGIN
    IF p_profiles IS NULL OR jsonb_typeof(p_profiles) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_profiles)
    LOOP
        IF elem->>'profile_index' IS NULL THEN
            CONTINUE;
        END IF;
        v_count := v_count + 1;
        IF v_count > coalesce(p_client_max_profiles, 6) THEN
            CONTINUE;
        END IF;
        INSERT INTO profiles AS pr (
            user_id, profile_index, name, avatar_color_hex,
            uses_primary_addons, uses_primary_plugins,
            avatar_id, avatar_url,
            profile_background_id, profile_background_url,
            updated_at
        ) VALUES (
            v_user_id,
            (elem->>'profile_index')::int,
            elem->>'name',
            elem->>'avatar_color_hex',
            coalesce((elem->>'uses_primary_addons')::boolean, false),
            coalesce((elem->>'uses_primary_plugins')::boolean, false),
            elem->>'avatar_id',
            elem->>'avatar_url',
            elem->>'profile_background_id',
            elem->>'profile_background_url',
            now()
        )
        ON CONFLICT (user_id, profile_index) DO UPDATE SET
            name = EXCLUDED.name,
            avatar_color_hex = EXCLUDED.avatar_color_hex,
            uses_primary_addons = EXCLUDED.uses_primary_addons,
            uses_primary_plugins = EXCLUDED.uses_primary_plugins,
            avatar_id = EXCLUDED.avatar_id,
            avatar_url = EXCLUDED.avatar_url,
            profile_background_id = EXCLUDED.profile_background_id,
            profile_background_url = EXCLUDED.profile_background_url,
            updated_at = now();
        -- NOTE: PIN columns are never touched by profile push; PINs are
        -- managed only by the set/clear/verify functions below.
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_delete_profile_data(
    p_profile_id int,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
BEGIN
    -- p_origin_client_id is accepted for a uniform mutating-RPC signature;
    -- profile deletion is not event-sourced, so it is not stored.
    DELETE FROM library_events         WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM library_items          WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM watch_progress_events  WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM watch_progress         WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM watched_events         WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM watched_items          WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM collections            WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM profile_settings_blobs WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM media_servers          WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM provider_credentials   WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM home_catalog_settings  WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM addons                 WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM profiles               WHERE user_id = v_user_id AND profile_index = p_profile_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_pull_profile_locks()
RETURNS TABLE (
    profile_index int,
    pin_enabled boolean,
    pin_locked_until timestamptz
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.profile_index,
        coalesce(p.pin_enabled, false),
        p.pin_locked_until
    FROM profiles AS p
    WHERE p.user_id = auth.uid()
    ORDER BY p.profile_index;
END;
$$;

-- ===========================================================================
-- Profile PINs (bcrypt via pgcrypto)
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.verify_profile_pin(
    p_profile_id int,
    p_pin text
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_enabled boolean;
    v_hash text;
    v_attempts int;
    v_locked_until timestamptz;
    v_retry int;
BEGIN
    SELECT p.pin_enabled, p.pin_hash, p.pin_failed_attempts, p.pin_locked_until
      INTO v_enabled, v_hash, v_attempts, v_locked_until
    FROM profiles AS p
    WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;

    -- No profile row, or PIN not enabled -> unlocked.
    IF NOT FOUND OR NOT coalesce(v_enabled, false) OR v_hash IS NULL THEN
        RETURN jsonb_build_object(
            'unlocked', true,
            'retry_after_seconds', 0,
            'message', NULL
        );
    END IF;

    -- Currently locked out.
    IF v_locked_until IS NOT NULL AND v_locked_until > now() THEN
        v_retry := GREATEST(1, ceil(extract(epoch from (v_locked_until - now())))::int);
        RETURN jsonb_build_object(
            'unlocked', false,
            'retry_after_seconds', v_retry,
            'message', 'Too many incorrect attempts. Try again later.'
        );
    END IF;

    -- Correct PIN.
    IF v_hash = crypt(p_pin, v_hash) THEN
        UPDATE profiles AS p
        SET pin_failed_attempts = 0,
            pin_locked_until = NULL,
            updated_at = now()
        WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
        RETURN jsonb_build_object(
            'unlocked', true,
            'retry_after_seconds', 0,
            'message', NULL
        );
    END IF;

    -- Wrong PIN: count the attempt; lock for 5 minutes after 5 failures.
    v_attempts := coalesce(v_attempts, 0) + 1;
    IF v_attempts >= 5 THEN
        UPDATE profiles AS p
        SET pin_failed_attempts = v_attempts,
            pin_locked_until = now() + interval '5 minutes',
            updated_at = now()
        WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
        RETURN jsonb_build_object(
            'unlocked', false,
            'retry_after_seconds', 300,
            'message', 'Too many incorrect attempts. Try again in 5 minutes.'
        );
    ELSE
        UPDATE profiles AS p
        SET pin_failed_attempts = v_attempts,
            updated_at = now()
        WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
        RETURN jsonb_build_object(
            'unlocked', false,
            'retry_after_seconds', 0,
            'message', 'Incorrect PIN.'
        );
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.set_profile_pin(
    p_profile_id int,
    p_pin text,
    p_current_pin text DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_enabled boolean;
    v_hash text;
BEGIN
    SELECT p.pin_enabled, p.pin_hash INTO v_enabled, v_hash
    FROM profiles AS p
    WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'profile not found';
    END IF;
    -- Changing an existing PIN requires proving the current one.
    IF coalesce(v_enabled, false) AND v_hash IS NOT NULL THEN
        IF p_current_pin IS NULL OR v_hash <> crypt(p_current_pin, v_hash) THEN
            RAISE EXCEPTION 'current PIN verification failed';
        END IF;
    END IF;
    UPDATE profiles AS p
    SET pin_hash = crypt(p_pin, gen_salt('bf')),
        pin_enabled = true,
        pin_failed_attempts = 0,
        pin_locked_until = NULL,
        updated_at = now()
    WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.clear_profile_pin(
    p_profile_id int,
    p_current_pin text DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_enabled boolean;
    v_hash text;
BEGIN
    SELECT p.pin_enabled, p.pin_hash INTO v_enabled, v_hash
    FROM profiles AS p
    WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'profile not found';
    END IF;
    IF coalesce(v_enabled, false) AND v_hash IS NOT NULL THEN
        IF p_current_pin IS NULL OR v_hash <> crypt(p_current_pin, v_hash) THEN
            RAISE EXCEPTION 'current PIN verification failed';
        END IF;
    END IF;
    UPDATE profiles AS p
    SET pin_hash = NULL,
        pin_enabled = false,
        pin_failed_attempts = 0,
        pin_locked_until = NULL,
        updated_at = now()
    WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
END;
$$;

-- Called by ProfileRepository.clearPinWithPassword. Verifies the account
-- password against GoTrue's bcrypt hash in auth.users, then clears the PIN.
CREATE OR REPLACE FUNCTION public.clear_profile_pin_with_account_password(
    p_account_password text,
    p_profile_id int
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_encrypted_password text;
BEGIN
    SELECT u.encrypted_password INTO v_encrypted_password
    FROM auth.users AS u
    WHERE u.id = v_user_id;
    IF v_encrypted_password IS NULL
       OR v_encrypted_password <> crypt(p_account_password, v_encrypted_password) THEN
        RAISE EXCEPTION 'invalid account password';
    END IF;
    UPDATE profiles AS p
    SET pin_hash = NULL,
        pin_enabled = false,
        pin_failed_attempts = 0,
        pin_locked_until = NULL,
        updated_at = now()
    WHERE p.user_id = v_user_id AND p.profile_index = p_profile_id;
END;
$$;

-- ===========================================================================
-- Blob sync: collections
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_collections(p_profile_id int)
RETURNS TABLE (
    profile_id int,
    collections_json jsonb,
    updated_at timestamptz
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    -- No synthetic empty row: the client does decodeList<>().firstOrNull()
    -- and treats null as "no remote data, preserve local". A synthetic '[]'
    -- row would be applied as an empty collection list and wipe local data.
    RETURN QUERY
    SELECT
        c.profile_id,
        c.collections_json,
        c.updated_at
    FROM collections AS c
    WHERE c.user_id = auth.uid()
      AND c.profile_id = p_profile_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_collections(
    p_profile_id int,
    p_collections_json jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO collections AS c (user_id, profile_id, collections_json, updated_at)
    VALUES (
        auth.uid(), p_profile_id,
        CASE WHEN p_collections_json IS NULL OR jsonb_typeof(p_collections_json) <> 'array'
             THEN '[]'::jsonb ELSE p_collections_json END,
        now()
    )
    ON CONFLICT (user_id, profile_id) DO UPDATE SET
        collections_json = EXCLUDED.collections_json,
        updated_at = now();
END;
$$;

-- ===========================================================================
-- Blob sync: profile settings (per platform)
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_profile_settings_blob(
    p_profile_id int,
    p_platform text
)
RETURNS TABLE (
    profile_id int,
    settings_json jsonb,
    updated_at timestamptz
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        b.profile_id,
        b.settings_json,
        b.updated_at
    FROM profile_settings_blobs AS b
    WHERE b.user_id = auth.uid()
      AND b.profile_id = p_profile_id
      AND b.platform = p_platform;
    IF NOT FOUND THEN
        -- settings_json MUST be NULL here (not '{}'): the client treats null
        -- as "no remote blob" and would otherwise decode '{}' into default
        -- settings and overwrite local ones.
        RETURN QUERY SELECT p_profile_id, NULL::jsonb, NULL::timestamptz;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_profile_settings_blob(
    p_profile_id int,
    p_platform text,
    p_settings_json jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO profile_settings_blobs AS b (user_id, profile_id, platform, settings_json, updated_at)
    VALUES (
        auth.uid(), p_profile_id, p_platform,
        coalesce(p_settings_json, '{}'::jsonb),
        now()
    )
    ON CONFLICT (user_id, profile_id, platform) DO UPDATE SET
        settings_json = EXCLUDED.settings_json,
        updated_at = now();
END;
$$;

-- ===========================================================================
-- Blob sync: media servers
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_media_servers(p_profile_id int)
RETURNS TABLE (
    servers_json jsonb
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    -- No synthetic row: the client does decodeList<>().firstOrNull() and
    -- treats null as "no remote servers". A synthetic '[]' could be applied
    -- over locally configured servers.
    RETURN QUERY
    SELECT m.servers_json
    FROM media_servers AS m
    WHERE m.user_id = auth.uid()
      AND m.profile_id = p_profile_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_media_servers(
    p_profile_id int,
    p_servers jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO media_servers AS m (user_id, profile_id, servers_json, updated_at)
    VALUES (
        auth.uid(), p_profile_id,
        CASE WHEN p_servers IS NULL OR jsonb_typeof(p_servers) <> 'array'
             THEN '[]'::jsonb ELSE p_servers END,
        now()
    )
    ON CONFLICT (user_id, profile_id) DO UPDATE SET
        servers_json = EXCLUDED.servers_json,
        updated_at = now();
END;
$$;

-- ===========================================================================
-- Blob sync: provider credentials (packed jsonb array <-> rows)
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_provider_credentials(p_profile_id int)
RETURNS TABLE (
    provider text,
    credential_json jsonb,
    updated_at timestamptz
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_blob jsonb;
    v_updated_at timestamptz;
    elem jsonb;
BEGIN
    SELECT c.credentials_json, c.updated_at INTO v_blob, v_updated_at
    FROM provider_credentials AS c
    WHERE c.user_id = auth.uid()
      AND c.profile_id = p_profile_id;
    IF NOT FOUND OR v_blob IS NULL OR jsonb_typeof(v_blob) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(v_blob)
    LOOP
        provider := elem->>'provider';
        credential_json := elem->'credential_json';
        updated_at := v_updated_at;
        RETURN NEXT;
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_provider_credentials(
    p_profile_id int,
    p_credentials jsonb,
    p_origin_client_id text DEFAULT NULL
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO provider_credentials AS c (user_id, profile_id, credentials_json, updated_at)
    VALUES (
        auth.uid(), p_profile_id,
        CASE WHEN p_credentials IS NULL OR jsonb_typeof(p_credentials) <> 'array'
             THEN '[]'::jsonb ELSE p_credentials END,
        now()
    )
    ON CONFLICT (user_id, profile_id) DO UPDATE SET
        credentials_json = EXCLUDED.credentials_json,
        updated_at = now();
END;
$$;

-- ===========================================================================
-- Blob sync: home catalog settings (per platform)
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_pull_home_catalog_settings(
    p_profile_id int,
    p_platform text
)
RETURNS TABLE (
    profile_id int,
    settings_json jsonb
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    -- No synthetic row: the client does decodeList<>().firstOrNull() and
    -- treats null as "no remote settings, preserve local".
    RETURN QUERY
    SELECT
        h.profile_id,
        h.settings_json
    FROM home_catalog_settings AS h
    WHERE h.user_id = auth.uid()
      AND h.profile_id = p_profile_id
      AND h.platform = p_platform;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_push_home_catalog_settings(
    p_profile_id int,
    p_platform text,
    p_settings_json jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO home_catalog_settings AS h (user_id, profile_id, platform, settings_json, updated_at)
    VALUES (
        auth.uid(), p_profile_id, p_platform,
        coalesce(p_settings_json, '{}'::jsonb),
        now()
    )
    ON CONFLICT (user_id, profile_id, platform) DO UPDATE SET
        settings_json = EXCLUDED.settings_json,
        updated_at = now();
END;
$$;

-- ===========================================================================
-- Addons (replace-all push)
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.sync_push_addons(
    p_profile_id int,
    p_addons jsonb,
    p_origin_client_id text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    elem jsonb;
BEGIN
    DELETE FROM addons AS a
    WHERE a.user_id = v_user_id AND a.profile_id = p_profile_id;
    IF p_addons IS NULL OR jsonb_typeof(p_addons) <> 'array' THEN
        RETURN;
    END IF;
    FOR elem IN SELECT * FROM jsonb_array_elements(p_addons)
    LOOP
        IF elem->>'url' IS NULL THEN
            CONTINUE;
        END IF;
        INSERT INTO addons AS a (user_id, profile_id, url, name, enabled, sort_order)
        VALUES (
            v_user_id, p_profile_id,
            elem->>'url',
            elem->>'name',
            coalesce((elem->>'enabled')::boolean, true),
            coalesce((elem->>'sort_order')::int, 0)
        )
        ON CONFLICT (user_id, profile_id, url) DO UPDATE SET
            name = EXCLUDED.name,
            enabled = EXCLUDED.enabled,
            sort_order = EXCLUDED.sort_order;
    END LOOP;
END;
$$;

-- ===========================================================================
-- Device registration
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.register_current_device(
    p_installation_id text,
    p_client_name text,
    p_client_version text,
    p_platform text,
    p_device_name text
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO devices AS d (
        user_id, installation_id, client_name, client_version,
        platform, device_name, last_seen
    ) VALUES (
        auth.uid(), p_installation_id, p_client_name, p_client_version,
        p_platform, p_device_name, now()
    )
    ON CONFLICT (user_id, installation_id) DO UPDATE SET
        client_name = EXCLUDED.client_name,
        client_version = EXCLUDED.client_version,
        platform = EXCLUDED.platform,
        device_name = EXCLUDED.device_name,
        last_seen = now();
END;
$$;

-- ===========================================================================
-- Grants
-- ===========================================================================

GRANT EXECUTE ON FUNCTION public.sync_pull_profiles() TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_profiles(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_delete_profile_data(int, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_profile_locks() TO authenticated;
GRANT EXECUTE ON FUNCTION public.verify_profile_pin(int, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.set_profile_pin(int, text, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.clear_profile_pin(int, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.clear_profile_pin_with_account_password(text, int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_collections(int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_collections(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_profile_settings_blob(int, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_profile_settings_blob(int, text, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_media_servers(int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_media_servers(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_provider_credentials(int) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_provider_credentials(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_pull_home_catalog_settings(int, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_home_catalog_settings(int, text, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_push_addons(int, jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.register_current_device(text, text, text, text, text) TO authenticated;
