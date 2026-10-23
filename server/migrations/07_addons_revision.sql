-- 07_addons_revision.sql — Nuvio sync server: revision-guarded addons
-- push (audit finding M11).
--
-- Plain PostgreSQL 15 / plpgsql. Runs after 01_schema.sql + 02_rls.sql.
-- Additive and re-runnable, per the migrations convention.
--
-- Before this file, sync_push_addons was a blind replace-all
-- (DELETE + re-insert) with no version check: whichever device pushed
-- last won, so a device holding a stale list — or retrying a push that
-- had actually failed — silently deleted addons another device had
-- added in the meantime.
--
-- The fix is a per-(user, profile) revision counter:
--   * addon_sync_state.revision starts at 0 and is bumped by every
--     accepted push. The app reads it next to the addons rows on pull
--     and echoes it back as p_base_revision on push.
--   * A push whose base revision does not match the stored revision is
--     REJECTED with an 'addons_revision_conflict' exception (the client
--     merges the newer server list with its pending changes and
--     re-pushes against the fresh revision) instead of overwriting.
--   * p_base_revision is optional (DEFAULT NULL): pre-revision clients
--     keep the old replace-all behaviour, but their pushes still bump
--     the revision, so a revision-aware client detects the overwrite
--     on its next push instead of silently losing its own changes.
--   * The old 3-argument signature is dropped and replaced by the
--     4-argument one; PostgREST matches calls by parameter name, so
--     3-argument calls from older app versions resolve to the new
--     function via the default.

-- ---------------------------------------------------------------------------
-- Revision state. Read directly by the app via PostgREST (like `addons`),
-- so it gets the same owner-scoped RLS treatment; writes happen only
-- inside the SECURITY DEFINER push RPC.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS addon_sync_state (
    user_id    uuid    NOT NULL,
    profile_id integer NOT NULL,
    revision   bigint  NOT NULL DEFAULT 0,
    updated_at timestamptz DEFAULT now(),
    PRIMARY KEY (user_id, profile_id)
);

ALTER TABLE addon_sync_state ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS addon_sync_state_owner_access ON addon_sync_state;
CREATE POLICY addon_sync_state_owner_access ON addon_sync_state
    FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Read-only for clients (mirrors the addons grants in 02_rls.sql); the
-- table itself is only written by the push RPC below.
GRANT SELECT ON addon_sync_state TO anon, authenticated;

-- ===========================================================================
-- Addons (revision-guarded replace-all push)
-- ===========================================================================

DROP FUNCTION IF EXISTS public.sync_push_addons(int, jsonb, text);

CREATE OR REPLACE FUNCTION public.sync_push_addons(
    p_profile_id int,
    p_addons jsonb,
    p_origin_client_id text,
    p_base_revision bigint DEFAULT NULL
)
RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_revision bigint;
    elem jsonb;
BEGIN
    INSERT INTO addon_sync_state (user_id, profile_id, revision)
    VALUES (v_user_id, p_profile_id, 0)
    ON CONFLICT (user_id, profile_id) DO NOTHING;

    -- Lock the state row so two concurrent pushes for the same profile
    -- serialize: the second one observes the bumped revision and, if it
    -- based its list on the old one, conflicts instead of overwriting.
    SELECT revision INTO v_revision
    FROM addon_sync_state
    WHERE user_id = v_user_id AND profile_id = p_profile_id
    FOR UPDATE;

    IF p_base_revision IS NOT NULL AND p_base_revision <> v_revision THEN
        RAISE EXCEPTION 'addons_revision_conflict: base revision % does not match current revision %',
            p_base_revision, v_revision;
    END IF;

    DELETE FROM addons AS a
    WHERE a.user_id = v_user_id AND a.profile_id = p_profile_id;
    IF p_addons IS NOT NULL AND jsonb_typeof(p_addons) = 'array' THEN
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
    END IF;

    UPDATE addon_sync_state
    SET revision = revision + 1, updated_at = now()
    WHERE user_id = v_user_id AND profile_id = p_profile_id
    RETURNING revision INTO v_revision;

    RETURN v_revision;
END;
$$;

GRANT EXECUTE ON FUNCTION public.sync_push_addons(int, jsonb, text, bigint) TO authenticated;
