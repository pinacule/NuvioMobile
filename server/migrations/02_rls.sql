-- 02_rls.sql — Nuvio self-hosted sync server: Row Level Security + grants.
--
-- Plain PostgreSQL 15. Runs after 01_schema.sql.
--
-- Access model (per the server design):
--   * `addons` is the only table the app touches directly via PostgREST, so it
--     gets an owner-scoped policy (auth.uid() = user_id).
--   * Every other table has RLS ENABLED with NO policies: direct PostgREST
--     access is denied for anon/authenticated, and only SECURITY DEFINER RPCs
--     (which run as the table owner and therefore bypass RLS) may read/write
--     them.

ALTER TABLE addons                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE profiles               ENABLE ROW LEVEL SECURITY;
ALTER TABLE library_items          ENABLE ROW LEVEL SECURITY;
ALTER TABLE library_events         ENABLE ROW LEVEL SECURITY;
ALTER TABLE watch_progress         ENABLE ROW LEVEL SECURITY;
ALTER TABLE watch_progress_events  ENABLE ROW LEVEL SECURITY;
ALTER TABLE watched_items          ENABLE ROW LEVEL SECURITY;
ALTER TABLE watched_events         ENABLE ROW LEVEL SECURITY;
ALTER TABLE collections            ENABLE ROW LEVEL SECURITY;
ALTER TABLE profile_settings_blobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_servers          ENABLE ROW LEVEL SECURITY;
ALTER TABLE provider_credentials   ENABLE ROW LEVEL SECURITY;
ALTER TABLE home_catalog_settings  ENABLE ROW LEVEL SECURITY;
ALTER TABLE devices                ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_login_sessions  ENABLE ROW LEVEL SECURITY;

-- addons: owner-only direct access, mirroring the row's user_id against the
-- JWT subject. PostgREST direct reads still hit this policy.
DROP POLICY IF EXISTS addons_owner_access ON addons;
CREATE POLICY addons_owner_access ON addons
    FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- All other tables: RLS enabled, zero policies -> deny-all for direct access.
-- SECURITY DEFINER RPCs bypass RLS as the table owner.

-- Grants needed so PostgREST can serve the addons table (and the RPC
-- endpoints) to the JWT roles; RLS policies still apply on top of grants.
GRANT USAGE ON SCHEMA public TO anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON addons TO anon, authenticated;
