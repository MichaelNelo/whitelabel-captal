CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT,
    locale TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_users_email ON users(email);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT REFERENCES users(id),
    device_id TEXT NOT NULL,
    locale TEXT NOT NULL,
    phase TEXT NOT NULL,
    current_survey_id TEXT REFERENCES surveys(id),
    current_question_id TEXT REFERENCES questions(id),
    created_at TEXT NOT NULL
);

CREATE INDEX idx_sessions_user_id ON sessions(user_id);

CREATE TABLE surveys (
    id TEXT PRIMARY KEY,
    category TEXT NOT NULL CHECK (category IN ('email', 'profiling', 'location', 'advertiser')),
    advertiser_id TEXT,
    is_active INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_surveys_category ON surveys(category);
CREATE INDEX idx_surveys_advertiser ON surveys(advertiser_id);

CREATE TABLE questions (
    id TEXT PRIMARY KEY,
    survey_id TEXT NOT NULL REFERENCES surveys(id),
    question_type TEXT NOT NULL CHECK (question_type IN ('radio', 'checkbox', 'select', 'input', 'rating', 'numeric', 'date')),
    points_awarded INTEGER NOT NULL,
    display_order INTEGER NOT NULL,
    hierarchy_level TEXT CHECK (hierarchy_level IS NULL OR hierarchy_level IN ('state', 'city', 'municipality', 'urbanization')),
    is_required INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_questions_survey ON questions(survey_id);

CREATE TABLE question_options (
    id TEXT PRIMARY KEY,
    question_id TEXT NOT NULL REFERENCES questions(id),
    display_order INTEGER NOT NULL,
    parent_option_id TEXT REFERENCES question_options(id)
);

CREATE INDEX idx_question_options_question ON question_options(question_id);

CREATE TABLE question_rules (
    id TEXT PRIMARY KEY,
    question_id TEXT NOT NULL REFERENCES questions(id),
    rule_type TEXT NOT NULL,
    rule_config TEXT NOT NULL
);

CREATE INDEX idx_question_rules_question ON question_rules(question_id);

CREATE TABLE answers (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    session_id TEXT NOT NULL REFERENCES sessions(id),
    question_id TEXT NOT NULL REFERENCES questions(id),
    answer_value TEXT NOT NULL,
    answered_at TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_answers_user ON answers(user_id);
CREATE INDEX idx_answers_question ON answers(question_id);
CREATE UNIQUE INDEX idx_answers_user_question ON answers(user_id, question_id);

CREATE TABLE user_survey_progress (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    survey_id TEXT NOT NULL REFERENCES surveys(id),
    current_question_id TEXT REFERENCES questions(id),
    completed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_user_survey_progress_unique ON user_survey_progress(user_id, survey_id);

-- Tabla para textos localizados (questions, options, UI del portal)
CREATE TABLE localized_texts (
    id TEXT PRIMARY KEY,
    entity_id TEXT NOT NULL,
    locale TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_localized_texts_entity_locale ON localized_texts(entity_id, locale);
