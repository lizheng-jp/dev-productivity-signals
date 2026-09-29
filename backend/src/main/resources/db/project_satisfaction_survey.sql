CREATE TABLE IF NOT EXISTS project_satisfaction_survey (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    user_code VARCHAR(255) NOT NULL,
    user_name VARCHAR(255),
    survey_date DATE NOT NULL,
    period_start DATE,
    period_end DATE,
    respondent_name VARCHAR(255),
    q1_work_value SMALLINT NOT NULL CHECK (q1_work_value BETWEEN 1 AND 5),
    q2_work_meaning SMALLINT NOT NULL CHECK (q2_work_meaning BETWEEN 1 AND 5),
    q3_team_satisfaction SMALLINT NOT NULL CHECK (q3_team_satisfaction BETWEEN 1 AND 5),
    q4_recommend_team SMALLINT NOT NULL CHECK (q4_recommend_team BETWEEN 1 AND 5),
    q5_information_access SMALLINT NOT NULL CHECK (q5_information_access BETWEEN 1 AND 5),
    q6_environment_support SMALLINT NOT NULL CHECK (q6_environment_support BETWEEN 1 AND 5),
    q7_support_flow SMALLINT NOT NULL CHECK (q7_support_flow BETWEEN 1 AND 5),
    q8_outcome_confidence SMALLINT NOT NULL CHECK (q8_outcome_confidence BETWEEN 1 AND 5),
    q9_sustainable_workload SMALLINT NOT NULL CHECK (q9_sustainable_workload BETWEEN 1 AND 5),
    q10_fatigue SMALLINT NOT NULL CHECK (q10_fatigue BETWEEN 1 AND 5),
    q11_detachment SMALLINT NOT NULL CHECK (q11_detachment BETWEEN 1 AND 5),
    q12_pressure SMALLINT NOT NULL CHECK (q12_pressure BETWEEN 1 AND 5),
    q13_psychological_safety SMALLINT NOT NULL CHECK (q13_psychological_safety BETWEEN 1 AND 5),
    q14_improvement_expectation SMALLINT NOT NULL CHECK (q14_improvement_expectation BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_project_satisfaction_survey_project_date
    ON project_satisfaction_survey (project_id, survey_date DESC);

CREATE INDEX IF NOT EXISTS idx_project_satisfaction_survey_project_user_date
    ON project_satisfaction_survey (project_id, user_code, survey_date DESC);
