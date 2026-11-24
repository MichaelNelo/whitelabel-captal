-- An access point and the UniFi controller it answers to become the system's: a location is
-- configured with any of them, and which location takes an access point's guests is a row of its
-- own, one per access point at most, written as a deploy or a resume starts. A controller's token is
-- stored once. See docs/context/access-points-plan.adoc.
--
-- The table there holds a row per access point as a location configures it. It is renamed, the four
-- tables are filled from it, and it is dropped. Every id derives from the data, never `random()`:
-- rqlite runs each statement on every node, and a value that differed between them would split the
-- replicas.

ALTER TABLE access_points RENAME TO access_points_by_location;

-- A UniFi controller: one per address, whatever access points and locations use it.
CREATE TABLE unifi_controllers (
    id TEXT PRIMARY KEY,
    host TEXT NOT NULL,
    -- NULL is the controller's default, 443.
    port INTEGER,
    site_id TEXT NOT NULL,
    api_token TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_unifi_controllers_address
    ON unifi_controllers(host, COALESCE(port, 443), site_id);

-- A physical access point, by its MAC — lowercase with colons, as `Mac.show` writes it — and the
-- controller it answers to.
CREATE TABLE access_points (
    id TEXT PRIMARY KEY,
    mac TEXT NOT NULL UNIQUE,
    controller_id TEXT NOT NULL REFERENCES unifi_controllers(id),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- An access point a location is configured with, and what the location does for the guests who join
-- through it. Any number of locations can be configured with one access point.
CREATE TABLE location_access_points (
    location_id TEXT NOT NULL REFERENCES locations(id),
    access_point_id TEXT NOT NULL REFERENCES access_points(id),
    -- NULL is 1440 minutes, a day.
    duration_minutes INTEGER,
    -- Where the load balancer sends these guests instead of the location's portal.
    redirect_url TEXT,
    unifi_is_enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (location_id, access_point_id)
);
CREATE INDEX idx_location_access_points_access_point
    ON location_access_points(access_point_id);

-- The location taking an access point's guests: `deploying` while the run `run_id`, a deploy or a
-- resume, goes, and `deployed` once it succeeded. One location per access point at most, which is
-- what refuses a second location the same access point as its run starts.
CREATE TABLE active_access_points (
    access_point_id TEXT PRIMARY KEY REFERENCES access_points(id),
    location_id TEXT NOT NULL REFERENCES locations(id),
    state TEXT NOT NULL CHECK (state IN ('deploying', 'deployed')),
    run_id TEXT NOT NULL,
    since TEXT NOT NULL,
    FOREIGN KEY (location_id, access_point_id)
        REFERENCES location_access_points(location_id, access_point_id)
);
CREATE INDEX idx_active_access_points_location ON active_access_points(location_id);

-- One controller per address, named after the smallest row id among those that use it, with the
-- token of the one changed last.
INSERT INTO unifi_controllers (id, host, port, site_id, api_token, created_at, updated_at)
SELECT
    'controller-' || MIN(a.id),
    a.unifi_host,
    MIN(a.unifi_port),
    a.unifi_site_id,
    (SELECT b.unifi_api_token FROM access_points_by_location b
     WHERE b.unifi_host = a.unifi_host
       AND COALESCE(b.unifi_port, 443) = COALESCE(a.unifi_port, 443)
       AND b.unifi_site_id = a.unifi_site_id
     ORDER BY b.updated_at DESC, b.id DESC
     LIMIT 1),
    MIN(a.created_at),
    MAX(a.updated_at)
FROM access_points_by_location a
GROUP BY a.unifi_host, COALESCE(a.unifi_port, 443), a.unifi_site_id;

-- One access point per MAC, its id the MAC, answering to the controller of the row changed last.
INSERT INTO access_points (id, mac, controller_id, created_at, updated_at)
SELECT
    a.mac,
    a.mac,
    (SELECT c.id FROM unifi_controllers c
     WHERE c.host = last.unifi_host
       AND COALESCE(c.port, 443) = COALESCE(last.unifi_port, 443)
       AND c.site_id = last.unifi_site_id),
    MIN(a.created_at),
    MAX(a.updated_at)
FROM access_points_by_location a
JOIN access_points_by_location last ON last.id = (
    SELECT b.id FROM access_points_by_location b
    WHERE b.mac = a.mac
    ORDER BY b.updated_at DESC, b.id DESC
    LIMIT 1)
GROUP BY a.mac;

INSERT INTO location_access_points
    (location_id, access_point_id, duration_minutes, redirect_url, unifi_is_enabled, created_at,
     updated_at)
SELECT location_id, mac, duration_minutes, redirect_url, unifi_is_enabled, created_at, updated_at
FROM access_points_by_location;

-- A deployed location has taken every access point it is configured with, by the run that left it
-- deployed.
INSERT INTO active_access_points (access_point_id, location_id, state, run_id, since)
SELECT a.mac, a.location_id, 'deployed', COALESCE(l.state_run, 'V40'), l.updated_at
FROM access_points_by_location a
JOIN locations l ON l.id = a.location_id
WHERE l.state = 'deployed';

DROP TABLE access_points_by_location;
