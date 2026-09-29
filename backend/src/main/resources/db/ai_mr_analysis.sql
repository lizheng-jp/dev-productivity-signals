CREATE TABLE IF NOT EXISTS ai_prompt_version (
    id BIGSERIAL PRIMARY KEY,
    version_key VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255),
    model VARCHAR(255) NOT NULL,
    prompt_text TEXT NOT NULL,
    prompt_hash VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_mr_analysis_job (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    ref_name VARCHAR(255),
    since_date DATE NOT NULL,
    until_date DATE NOT NULL,
    prompt_version_id BIGINT NOT NULL,
    model VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    target_count INTEGER NOT NULL,
    analyzed_count INTEGER NOT NULL,
    skipped_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    requested_by VARCHAR(255),
    requested_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_mr_analysis_job_requested_at
    ON ai_mr_analysis_job (requested_at DESC);

CREATE TABLE IF NOT EXISTS ai_mr_analysis_job_item (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    mr_iid INTEGER NOT NULL,
    author_username VARCHAR(255),
    source_branch VARCHAR(255),
    target_branch VARCHAR(255),
    merged_at TIMESTAMP,
    status VARCHAR(255) NOT NULL,
    evaluation_id BIGINT,
    skip_reason TEXT,
    error_message TEXT,
    diff_line_count INTEGER,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_mr_analysis_job_item_job_id
    ON ai_mr_analysis_job_item (job_id, id);

CREATE TABLE IF NOT EXISTS ai_mr_evaluation (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    mr_iid INTEGER NOT NULL,
    author_username VARCHAR(255),
    target_branch VARCHAR(255),
    source_branch VARCHAR(255),
    merged_at TIMESTAMP,
    job_id BIGINT,
    prompt_version_id BIGINT,
    model VARCHAR(255),
    prompt_hash VARCHAR(255),
    change_type VARCHAR(255),
    type_coefficient DOUBLE PRECISION,
    complexity VARCHAR(255),
    complexity_coefficient DOUBLE PRECISION,
    maintainability VARCHAR(255),
    maintainability_coefficient DOUBLE PRECISION,
    has_bug BOOLEAN,
    bug_coefficient DOUBLE PRECISION,
    contribution VARCHAR(255),
    contribution_coefficient DOUBLE PRECISION,
    reasoning TEXT,
    raw_response TEXT,
    analyzed_at TIMESTAMP
);

ALTER TABLE ai_mr_evaluation
    ADD COLUMN IF NOT EXISTS job_id BIGINT,
    ADD COLUMN IF NOT EXISTS prompt_version_id BIGINT,
    ADD COLUMN IF NOT EXISTS model VARCHAR(255),
    ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_ai_mr_evaluation_project_mr
    ON ai_mr_evaluation (project_id, mr_iid, analyzed_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_mr_evaluation_project_merged_at
    ON ai_mr_evaluation (project_id, merged_at);
