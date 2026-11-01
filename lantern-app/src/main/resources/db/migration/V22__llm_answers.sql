-- The LLM harness's answer cache, the only cache of LLM answers (docs/adr/0100-llm-harness.md;
-- knowledge-build epic, section 10). One row per item fingerprint: the SHA-256 of the role, its
-- prompt version, the model, the role's tools, the item's fingerprint inputs and its rendering. An
-- omitted answer, the model saying its inputs show nothing for the item, is a correct answer and
-- is cached too. files_read holds the files an item's tools read, with their git blobs: the answer
-- is reused only while each still has that blob. inputs holds the item's fingerprint inputs, each
-- with the file it comes from.
CREATE TABLE llm_answers (
    seq            bigserial   UNIQUE,
    fingerprint    text        PRIMARY KEY CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    role           text        NOT NULL CHECK (role ~ '^[a-z][a-z0-9-]*$'),
    subject        text        NOT NULL CHECK (subject ~ '\S'),
    prompt_version text        NOT NULL CHECK (prompt_version ~ '\S'),
    provider       text        NOT NULL CHECK (provider ~ '\S'),
    model          text        NOT NULL CHECK (model ~ '\S'),
    omitted        boolean     NOT NULL,
    answer         jsonb,
    files_read     jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(files_read) = 'array'),
    inputs         jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(inputs) = 'array'),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (omitted = (answer IS NULL))
);

-- A role falls back to the last answer for a subject when it cannot ask again (Resolve).
CREATE INDEX llm_answers_subject ON llm_answers (role, subject, seq);

-- The three caches it replaces. Their answers are not carried over: their keys hashed other inputs,
-- so the first run after this migration asks again what it needs.
DROP TABLE summaries;
DROP TABLE unit_wordings;
DROP TABLE link_resolutions;

-- A conversation that reached one of its caps (tool turns, input tokens) ends as CAPPED.
ALTER TABLE llm_conversations DROP CONSTRAINT llm_conversations_ending_check;
ALTER TABLE llm_conversations ADD CONSTRAINT llm_conversations_ending_check
    CHECK (ending IN ('ANSWERED', 'REJECTED', 'BUDGET', 'TURN_CAP', 'FAILED', 'CAPPED'));
