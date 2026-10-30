-- Conversations: one request to the model with its tool loop (see docs/adr/0081-tool-use.md). Each
-- turn is a row of llm_calls with the conversation's id, its turn number and the tool calls its
-- answer asked for, by tool name; a call on its own has neither. A conversation is a row here once
-- it ended, with its turns, tool calls by tool, tokens, cost and how it ended.
ALTER TABLE llm_calls ADD COLUMN conversation_id text;
ALTER TABLE llm_calls ADD COLUMN turn integer NOT NULL DEFAULT 0 CHECK (turn >= 0);
ALTER TABLE llm_calls ADD COLUMN tool_calls jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(tool_calls) = 'object');
ALTER TABLE llm_calls ADD CONSTRAINT llm_calls_turn_of_a_conversation
    CHECK ((conversation_id IS NULL) = (turn = 0));

CREATE TABLE llm_conversations (
    id                 text        PRIMARY KEY CHECK (id ~ '\S'),
    started_at         timestamptz NOT NULL,
    duration_ms        bigint      NOT NULL CHECK (duration_ms >= 0),
    role               text        NOT NULL CHECK (role ~ '^[A-Z_]+$'),
    purpose            text        NOT NULL CHECK (purpose ~ '\S'),
    provider           text        NOT NULL CHECK (provider ~ '\S'),
    model              text        NOT NULL CHECK (model ~ '\S'),
    turns              integer     NOT NULL CHECK (turns >= 0),
    tool_calls         jsonb       NOT NULL CHECK (jsonb_typeof(tool_calls) = 'object'),
    input_tokens       bigint      NOT NULL CHECK (input_tokens >= 0),
    output_tokens      bigint      NOT NULL CHECK (output_tokens >= 0),
    cache_write_tokens bigint      NOT NULL CHECK (cache_write_tokens >= 0),
    cache_read_tokens  bigint      NOT NULL CHECK (cache_read_tokens >= 0),
    cost_usd           numeric     NOT NULL CHECK (cost_usd >= 0),
    ending             text        NOT NULL
        CHECK (ending IN ('ANSWERED', 'REJECTED', 'BUDGET', 'TURN_CAP', 'FAILED')),
    error              text
);

CREATE INDEX llm_conversations_started_at ON llm_conversations (started_at);
CREATE INDEX llm_calls_conversation_id ON llm_calls (conversation_id);
