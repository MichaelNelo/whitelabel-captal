-- Fills the state of the locations that existed before `locations.state` (API migration V35) from
-- each one's last run that succeeded: a deploy or a resume left it deployed, a pause paused, a
-- destroy destroyed; with none it stays created. Runs before the backoffice recorded them are not
-- here, so a location deployed only before then is recorded as created, and its next deploy says
-- otherwise.
--
-- A deployed location with no access point turns every guest away, and a deployed location has one
-- at least: one without is recorded as created too, so a deploy with an access point stands it up.

UPDATE locations
SET
    state = COALESCE(
        (
            SELECT CASE r.operation
                WHEN 'deploy' THEN 'deployed'
                WHEN 'resume' THEN 'deployed'
                WHEN 'pause' THEN 'paused'
                WHEN 'destroy' THEN 'destroyed'
            END
            FROM mcp_deploy_runs r
            WHERE r.slug = locations.slug AND r.status = 'succeeded'
            ORDER BY r.started_at DESC
            LIMIT 1
        ),
        'created'
    ),
    state_run = (
        SELECT r.id
        FROM mcp_deploy_runs r
        WHERE r.slug = locations.slug AND r.status = 'succeeded'
        ORDER BY r.started_at DESC
        LIMIT 1
    );

UPDATE locations
SET state = 'created', state_run = NULL
WHERE state = 'deployed'
    AND NOT EXISTS (SELECT 1 FROM access_points ap WHERE ap.location_id = locations.id);
