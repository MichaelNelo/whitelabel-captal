-- Rename column to match the new naming convention (Propaganda -> Promo)
ALTER TABLE sessions RENAME COLUMN last_propaganda_video_id TO last_promo_video_id;
