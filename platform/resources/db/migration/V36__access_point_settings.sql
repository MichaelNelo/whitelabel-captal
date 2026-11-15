-- What the portal does for a guest is the access point's to say, since each answers to its own
-- controller and its own site's portal link: how long they are let in for, where the dispatcher sends
-- them instead of the portal, whether the controller is asked at all, and whether the redirect has to
-- carry the link shortener's fingerprint. Each access point starts with its location's.
--
-- The location's own columns stay until no API on the previous release reads them.

ALTER TABLE access_points ADD COLUMN duration_minutes INTEGER;
ALTER TABLE access_points ADD COLUMN redirect_url TEXT;
ALTER TABLE access_points ADD COLUMN unifi_is_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE access_points ADD COLUMN fingerprint_is_required INTEGER NOT NULL DEFAULT 1;

UPDATE access_points
SET
    duration_minutes = (
        SELECT l.unifi_duration_minutes FROM locations l WHERE l.id = access_points.location_id),
    redirect_url = (
        SELECT l.unifi_redirect_url FROM locations l WHERE l.id = access_points.location_id),
    unifi_is_enabled = COALESCE(
        (SELECT l.unifi_is_enabled FROM locations l WHERE l.id = access_points.location_id), 1),
    fingerprint_is_required = COALESCE(
        (SELECT l.fingerprint_is_required FROM locations l WHERE l.id = access_points.location_id),
        1);
