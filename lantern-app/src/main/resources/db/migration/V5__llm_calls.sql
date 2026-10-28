-- The LLM call log (see docs/design.md, LLM integration, and docs/adr/0009-llm-configuration.md).
-- One row per call, whether it succeeded, failed or was refused before sending. The monthly
-- budget is counted from cost_usd.
CREATE TABLE llm_calls (
    id                 bigserial   PRIMARY KEY,
    started_at         timestamptz NOT NULL,
    duration_ms        bigint      NOT NULL CHECK (duration_ms >= 0),
    role               text        NOT NULL CHECK (role ~ '^[A-Z_]+$'),
    purpose            text        NOT NULL CHECK (purpose ~ '\S'),
    provider           text        NOT NULL CHECK (provider ~ '\S'),
    model              text        NOT NULL CHECK (model ~ '\S'),
    outcome            text        NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'REFUSED')),
    input_tokens       bigint      NOT NULL CHECK (input_tokens >= 0),
    output_tokens      bigint      NOT NULL CHECK (output_tokens >= 0),
    cache_write_tokens bigint      NOT NULL CHECK (cache_write_tokens >= 0),
    cache_read_tokens  bigint      NOT NULL CHECK (cache_read_tokens >= 0),
    cost_usd           numeric     NOT NULL CHECK (cost_usd >= 0),
    error              text
);

CREATE INDEX llm_calls_started_at ON llm_calls (started_at);
