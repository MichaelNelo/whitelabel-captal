-- Completes the soft-delete mechanism at the granularity a backoffice needs.
--
-- surveys, advertisers, advertiser_videos and locations already carry is_active and the read
-- path already honours it. These four did not, so the only way to retire a single question,
-- option, rule or translation was to deactivate the whole survey. That is tolerable while the
-- only write path is editing a YAML and redeploying; it stops being tolerable once an operator
-- can delete one question with a single call.
--
-- DEFAULT 1 keeps the migration non-destructive: everything already stored stays active.

ALTER TABLE questions ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1;
ALTER TABLE question_options ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1;
ALTER TABLE question_rules ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1;
ALTER TABLE localized_texts ADD COLUMN is_active INTEGER NOT NULL DEFAULT 1;

-- The question lookups all filter by survey_id first, then by is_active.
CREATE INDEX idx_questions_active ON questions(survey_id, is_active);
CREATE INDEX idx_question_options_active ON question_options(question_id, is_active);
CREATE INDEX idx_question_rules_active ON question_rules(question_id, is_active);
CREATE INDEX idx_localized_texts_active ON localized_texts(entity_id, is_active);
