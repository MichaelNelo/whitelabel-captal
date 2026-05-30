-- ap_mac is conceptually a UniFi concept (MAC of a UCG-managed AP), grouped here with the rest
-- of the unifi_* columns to mirror the new location.yaml schema where it lives inside the
-- `unifi:` block. Also adds unifi_redirect_url so a location can override the captive portal
-- dispatcher's default target (`<cloudfront-host>/<slug>/`) with a custom URL — useful when the
-- location runs its own portal SPA but still wants to route via captal's GA IP.
--
-- rqlite v9 ships SQLite >= 3.35 so both RENAME COLUMN and ADD COLUMN are supported.

ALTER TABLE locations RENAME COLUMN ap_mac TO unifi_ap_mac;
ALTER TABLE locations ADD COLUMN unifi_redirect_url TEXT;
