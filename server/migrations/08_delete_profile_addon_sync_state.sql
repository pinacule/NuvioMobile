-- 08_delete_profile_addon_sync_state.sql — Nuvio sync server: profile
-- deletion also removes the profile's addon revision state (audit
-- finding M11 follow-up).
--
-- Plain PostgreSQL 15 / plpgsql. Runs after 01_schema.sql + 02_rls.sql.
-- Additive and re-runnable, per the migrations convention: the function
-- below is CREATE OR REPLACE with an unchanged signature, so existing
-- installs pick the fix up on the next `docker compose up`.
--
-- Migration 07 added the per-(user, profile) addon_sync_state table,
-- but sync_delete_profile_data (04_functions.sql) predates it and does
-- not delete from it: deleting a profile orphaned its revision row,
-- and a profile later re-created at the same index inherited the stale
-- revision as its baseline — a client holding local sync state for
-- that index then hit a spurious conflict on its first guarded push.
-- The function is therefore recreated here, body identical to
-- 04_functions.sql, with the missing DELETE added next to the addons
-- one.

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
    DELETE FROM addon_sync_state       WHERE user_id = v_user_id AND profile_id = p_profile_id;
    DELETE FROM profiles               WHERE user_id = v_user_id AND profile_index = p_profile_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.sync_delete_profile_data(int, text) TO authenticated;
