from prometheus_client import Counter, Histogram


EXECUTIONS = Counter("signals_agent_executions_total", "Agent requests by outcome", ["outcome"])
EXECUTION_DURATION = Histogram("signals_agent_execution_duration_seconds", "Agent request duration",
                               buckets=(1, 5, 10, 30, 60, 120, 240, 300))
MODEL_CALLS = Counter("signals_agent_model_calls_total", "Model calls by outcome", ["outcome"])
MODEL_DURATION = Histogram("signals_agent_model_duration_seconds", "Model call duration",
                           buckets=(1, 5, 10, 30, 60, 120, 180))
TOOL_CALLS = Counter("signals_agent_tool_calls_total", "Tool calls by name and outcome",
                     ["tool", "outcome"])
TOOL_DURATION = Histogram("signals_agent_tool_duration_seconds", "Tool call duration by name",
                          ["tool"], buckets=(0.1, 1, 5, 10, 30, 60, 120, 180))
INDEX_RUNS = Counter("signals_agent_index_runs_total", "Evidence index runs by outcome", ["outcome"])
INDEX_DURATION = Histogram("signals_agent_index_duration_seconds", "Evidence index duration",
                           buckets=(1, 5, 10, 30, 60, 120, 180))
INCOMPLETE_COVERAGE = Counter("signals_agent_incomplete_coverage_total",
                              "Index or search results with incomplete evidence coverage", ["operation"])
MODEL_TOKENS = Counter("signals_agent_model_tokens_total",
                       "Gemini tokens by kind (input, output, thinking, cached_input)", ["kind"])
ANSWER_REFERENCES = Counter("signals_agent_answer_references_total",
                            "PR/issue references in answers: supported by tool evidence, unsupported, "
                            "or links outside the selected project", ["kind"])
