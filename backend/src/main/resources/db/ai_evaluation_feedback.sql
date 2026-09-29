CREATE TABLE IF NOT EXISTS ai_evaluation_snapshot (
    id UUID PRIMARY KEY,
    selection_json TEXT NOT NULL,
    output_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_evaluation_feedback (
    id BIGSERIAL PRIMARY KEY,
    evaluation_id UUID NOT NULL REFERENCES ai_evaluation_snapshot(id) ON DELETE CASCADE,
    section VARCHAR(16) NOT NULL CHECK (section IN ('strengths', 'weaknesses', 'suggestions')),
    item_index INTEGER NOT NULL CHECK (item_index >= 0),
    item_text TEXT NOT NULL,
    helpful BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (evaluation_id, section, item_index)
);

CREATE INDEX IF NOT EXISTS idx_ai_evaluation_feedback_evaluation
    ON ai_evaluation_feedback (evaluation_id);
