-- Add category column to localized_texts for filtering frontend/backend/email messages
ALTER TABLE localized_texts ADD COLUMN category TEXT NOT NULL DEFAULT 'backend';
CREATE INDEX idx_localized_texts_category ON localized_texts(category);
