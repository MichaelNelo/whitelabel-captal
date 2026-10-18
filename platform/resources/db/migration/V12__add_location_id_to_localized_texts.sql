-- Add location_id to localized_texts for location-scoped frontend translations
ALTER TABLE localized_texts ADD COLUMN location_id TEXT;
CREATE INDEX idx_localized_texts_location ON localized_texts(location_id);

-- Recreate unique index to include location_id (allows same key per location)
DROP INDEX IF EXISTS idx_localized_texts_entity_locale;
CREATE UNIQUE INDEX idx_localized_texts_entity_locale ON localized_texts(entity_id, locale, COALESCE(location_id, ''));
