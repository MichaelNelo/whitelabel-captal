-- Optional admin-level identifier for the product/campaign a video promotes (beyond just the
-- advertiser/brand). Used for reporting; not surfaced via the SPA.
ALTER TABLE advertiser_videos ADD COLUMN product_campaign_id TEXT;
