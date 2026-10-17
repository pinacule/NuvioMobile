-- 05_functions.sql — Nuvio sync server: device-link login sessions + membership stubs.
--
-- Plain PostgreSQL 15 / plpgsql. Runs after 01_schema.sql + 02_rls.sql.
-- All functions: SECURITY DEFINER, SET search_path = public.
--
-- Device-link flow (verified against
-- composeApp/.../core/auth/DeviceLinkAuthRepository.kt):
--   * start_device_login_session is called by an UNAUTHENTICATED device, so
--     it must NOT scope by auth.uid() — the session row has no user yet.
--     (tv-logins-exchange, which approves the session and mints tokens, is a
--     gateway HTTP handler, not an RPC — see server/gateway/gateway.py.)
--   * Both device functions are granted to anon + authenticated.
--   * start returns a one-row TABLE (device_code, user_code,
--     verification_uri_complete, poll_interval_seconds) because the client
--     uses decodeList<DeviceLinkStartResponse>().firstOrNull().
--   * poll returns a one-row TABLE (status) for
--     decodeList<DeviceLinkPollResponse>().firstOrNull(). Missing or expired
--     sessions (nonce mismatch is treated as not found) -> 'expired';
--     otherwise the stored status ('pending' / 'approved').
--
-- Membership (verified against features/membership/*RemoteDataSource.kt and
-- features/profiles/AvatarRepository.kt / ProfileBackgroundRepository.kt):
--   * The client decodes these with decodeList<...>, so the stubs must make
--     PostgREST return a JSON array body:
--       - get_my_member_access() -> one row (tier text, entitlements text[]).
--         MemberTier has no 'none' entry, so tier='none' maps to
--         MemberAccess.None client-side.
--       - get_my_membership_overview() -> one all-NULL row; every Kotlin
--         field is nullable-with-default, so this decodes to the default
--         MembershipOverview() (status "inactive").
--       - get_avatar_catalog(), get_member_profile_avatar_catalog(),
--         get_member_profile_background_catalog() -> RETURNS jsonb '[]',
--         which PostgREST returns as a [] body for decodeList<...>().

-- ===========================================================================
-- Device-link login sessions
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.start_device_login_session(
    p_device_nonce text,
    p_redirect_base_url text,
    p_device_name text,
    p_device_type text DEFAULT 'mobile'
)
RETURNS TABLE (
    device_code text,
    user_code text,
    verification_uri_complete text,
    poll_interval_seconds int
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    c_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; -- no 0/O/1/I
    v_device_code text;
    v_user_code text;
    v_uri text;
    v_tries int := 0;
BEGIN
    LOOP
        v_device_code := encode(gen_random_bytes(32), 'hex'); -- 64 hex chars
        EXIT WHEN NOT EXISTS (
            SELECT 1 FROM device_login_sessions AS s WHERE s.device_code = v_device_code
        );
        v_tries := v_tries + 1;
        IF v_tries > 10 THEN
            RAISE EXCEPTION 'could not generate a unique device code';
        END IF;
    END LOOP;

    v_tries := 0;
    LOOP
        SELECT string_agg(substr(c_alphabet, (floor(random() * 32))::int + 1, 1), '' ORDER BY g)
          INTO v_user_code
        FROM generate_series(1, 6) AS g;
        EXIT WHEN NOT EXISTS (
            SELECT 1 FROM device_login_sessions AS s WHERE s.user_code = v_user_code
        );
        v_tries := v_tries + 1;
        IF v_tries > 10 THEN
            RAISE EXCEPTION 'could not generate a unique user code';
        END IF;
    END LOOP;

    v_uri := rtrim(p_redirect_base_url, '/') || '?user_code=' || v_user_code;

    INSERT INTO device_login_sessions AS s (
        device_code, device_nonce, user_code, device_name, device_type,
        redirect_base_url, verification_uri_complete, poll_interval_seconds,
        status, expires_at
    ) VALUES (
        v_device_code, p_device_nonce, v_user_code, p_device_name,
        coalesce(p_device_type, 'mobile'),
        p_redirect_base_url, v_uri, 3,
        'pending', now() + interval '10 minutes'
    );

    RETURN QUERY SELECT v_device_code, v_user_code, v_uri, 3;
END;
$$;

CREATE OR REPLACE FUNCTION public.poll_tv_login_session(
    p_code text,
    p_device_nonce text
)
RETURNS TABLE (
    status text
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_status text;
    v_expires_at timestamptz;
BEGIN
    -- The nonce proves the polling device owns the session; a mismatch is
    -- treated as not found (-> 'expired' below).
    SELECT s.status, s.expires_at INTO v_status, v_expires_at
    FROM device_login_sessions AS s
    WHERE s.device_code = p_code
      AND s.device_nonce = p_device_nonce;

    IF NOT FOUND THEN
        RETURN QUERY SELECT 'expired'::text;
        RETURN;
    END IF;

    IF v_expires_at < now() THEN
        UPDATE device_login_sessions AS s
        SET status = 'expired'
        WHERE s.device_code = p_code;
        RETURN QUERY SELECT 'expired'::text;
        RETURN;
    END IF;

    RETURN QUERY SELECT v_status;
END;
$$;

-- ===========================================================================
-- Membership stubs (no paid tiers on the self-hosted server)
-- ===========================================================================

CREATE OR REPLACE FUNCTION public.get_my_member_access()
RETURNS TABLE (
    tier text,
    entitlements text[]
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY SELECT 'none'::text, '{}'::text[];
END;
$$;

CREATE OR REPLACE FUNCTION public.get_my_membership_overview()
RETURNS TABLE (
    status text,
    tier text,
    verified_at timestamptz,
    supporter_since timestamptz,
    provider_connected boolean,
    has_subscription boolean,
    subscription_access_active boolean,
    subscription_status text,
    provider text,
    membership_level text,
    current_period_end timestamptz,
    cancels_at_period_end boolean,
    monthly_amount_cents int,
    currency_code text,
    has_active_grant boolean,
    grant_is_lifetime boolean,
    grant_expires_at timestamptz,
    grant_kind text,
    grant_tier text,
    grant_source text,
    has_lifetime_grant boolean,
    lifetime_grant_tier text
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN QUERY SELECT
        NULL::text, NULL::text,
        NULL::timestamptz, NULL::timestamptz,
        NULL::boolean, NULL::boolean, NULL::boolean, NULL::text, NULL::text,
        NULL::text, NULL::timestamptz, NULL::boolean,
        NULL::int, NULL::text,
        NULL::boolean, NULL::boolean, NULL::timestamptz,
        NULL::text, NULL::text, NULL::text,
        NULL::boolean, NULL::text;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_avatar_catalog()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN '[]'::jsonb; -- decodeList<AvatarCatalogItem> over a [] body
END;
$$;

CREATE OR REPLACE FUNCTION public.get_member_profile_avatar_catalog()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN '[]'::jsonb;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_member_profile_background_catalog()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    RETURN '[]'::jsonb;
END;
$$;

-- ===========================================================================
-- Grants
-- ===========================================================================

-- Device-link flow runs pre-authentication: grant to anon as well.
GRANT EXECUTE ON FUNCTION public.start_device_login_session(text, text, text, text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.poll_tv_login_session(text, text) TO anon, authenticated;

GRANT EXECUTE ON FUNCTION public.get_my_member_access() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_my_membership_overview() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_avatar_catalog() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_member_profile_avatar_catalog() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_member_profile_background_catalog() TO authenticated;
