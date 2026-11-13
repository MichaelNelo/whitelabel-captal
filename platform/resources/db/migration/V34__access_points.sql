-- A location's access points, each with the UniFi controller that authorizes the guests who join
-- through it: a venue has many, and they need not share a controller. The API reads the one a
-- guest's session came through when it authorizes or revokes them; the portal dispatcher finds a
-- location by the AP a redirect names.
--
-- `mac` is lowercase with colons, as `Mac.show` writes it, so the dispatcher and the API compare
-- text. One row per location and MAC: an AP may sit in several locations, so that one can take a
-- venue over from another, and the backoffice keeps at most one of them running.

CREATE TABLE access_points (
    id TEXT PRIMARY KEY,
    location_id TEXT NOT NULL REFERENCES locations(id),
    mac TEXT NOT NULL,
    unifi_host TEXT NOT NULL,
    unifi_port INTEGER,
    unifi_site_id TEXT NOT NULL,
    unifi_api_token TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (location_id, mac)
);

CREATE INDEX idx_access_points_mac ON access_points(mac);

-- Each location with a complete configuration and an AP keeps them, as its one access point. The
-- MAC is written as `Mac.show` would, whatever separators it was stored with; one that is not
-- twelve hex digits is left behind, as the portal could never have read it. The id is derived
-- rather than random, and the stamps are the location's: rqlite runs a statement on every node,
-- and a value that differs between them would split the replicas.
INSERT INTO access_points
    (id, location_id, mac, unifi_host, unifi_port, unifi_site_id, unifi_api_token, created_at,
     updated_at)
SELECT
    location_id || '/' || mac, location_id, mac, unifi_host, unifi_port, unifi_site_id,
    unifi_api_token, updated_at, updated_at
FROM (
    SELECT
        id AS location_id,
        substr(hex, 1, 2) || ':' || substr(hex, 3, 2) || ':' || substr(hex, 5, 2) || ':' ||
            substr(hex, 7, 2) || ':' || substr(hex, 9, 2) || ':' || substr(hex, 11, 2) AS mac,
        hex, unifi_host, unifi_port, unifi_site_id, unifi_api_token, updated_at
    FROM (
        SELECT
            id, replace(replace(lower(trim(unifi_ap_mac)), ':', ''), '-', '') AS hex,
            unifi_host, unifi_port, unifi_site_id, unifi_api_token, updated_at
        FROM locations
        WHERE unifi_ap_mac IS NOT NULL
          AND trim(unifi_host) <> ''
          AND trim(unifi_site_id) <> ''
          AND unifi_api_token <> ''
    )
)
WHERE length(hex) = 12 AND hex NOT GLOB '*[^0-9a-f]*';

-- `unifi_ap_mac`, `unifi_host`, `unifi_port`, `unifi_site_id` and `unifi_api_token` stay on
-- `locations`, unread, for one release: a dispatcher or an API still on the previous one reads them
-- until it is replaced. A later migration drops them.
