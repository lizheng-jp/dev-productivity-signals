CREATE TABLE IF NOT EXISTS user_feedback (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    context VARCHAR(300),
    details TEXT NOT NULL,
    locale VARCHAR(8) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_feedback_created_at
    ON user_feedback (created_at DESC);
