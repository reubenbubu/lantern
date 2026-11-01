-- Tokens written to the prompt cache with the one-hour lifetime, which costs more than a five-minute
-- write (docs/adr/0102-batch-api-and-openai.md). cache_write_tokens keeps the five-minute writes.
ALTER TABLE llm_calls ADD COLUMN cache_write_long_tokens bigint NOT NULL DEFAULT 0
    CHECK (cache_write_long_tokens >= 0);
ALTER TABLE llm_conversations ADD COLUMN cache_write_long_tokens bigint NOT NULL DEFAULT 0
    CHECK (cache_write_long_tokens >= 0);
