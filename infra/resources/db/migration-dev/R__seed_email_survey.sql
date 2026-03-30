-- Dev seed: Email survey
-- IDs:
--   survey:   d0020000-0000-4000-8000-000000000001
--   question: d0030000-0000-4000-8000-000000000001

DELETE FROM question_rules;
DELETE FROM questions WHERE survey_id IN (SELECT id FROM surveys WHERE category = 'email');
DELETE FROM localized_texts WHERE category = 'backend' AND entity_id NOT LIKE 'd004%' AND entity_id NOT LIKE 'd007%';
DELETE FROM surveys WHERE category = 'email';

INSERT INTO surveys (id, category, advertiser_id, video_id, is_active, created_at) VALUES
('d0020000-0000-4000-8000-000000000001', 'email', NULL, NULL, 1, '2024-01-01T00:00:00Z');

INSERT INTO questions (id, survey_id, question_type, points_awarded, display_order, hierarchy_level, is_required, created_at) VALUES
('d0030000-0000-4000-8000-000000000001', 'd0020000-0000-4000-8000-000000000001', 'input', 10, 1, NULL, 1, '2024-01-01T00:00:00Z');

INSERT INTO question_rules (id, question_id, rule_type, rule_config) VALUES
('d0050000-0000-4000-8000-000000000001', 'd0030000-0000-4000-8000-000000000001', 'text', '{"type":"email"}'),
('d0050000-0000-4000-8000-000000000002', 'd0030000-0000-4000-8000-000000000001', 'text', '{"type":"max_length","value":100}');

INSERT INTO localized_texts (id, entity_id, locale, value, category, created_at, updated_at) VALUES
('d0010000-0000-4000-8000-000000000101', 'd0030000-0000-4000-8000-000000000001', 'es', '¿Cuál es tu correo electrónico?', 'backend', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z'),
('d0010000-0000-4000-8000-000000000102', 'd0030000-0000-4000-8000-000000000001', 'en', 'What is your email address?', 'backend', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z'),
('d0010000-0000-4000-8000-000000000103', 'd0030000-0000-4000-8000-000000000001_placeholder', 'es', 'correo@ejemplo.com', 'backend', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z'),
('d0010000-0000-4000-8000-000000000104', 'd0030000-0000-4000-8000-000000000001_placeholder', 'en', 'email@example.com', 'backend', '2024-01-01T00:00:00Z', '2024-01-01T00:00:00Z');
