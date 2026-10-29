-- Cached answers of the LLM's wording of deployable units: the responsibility sentences of a unit
-- and its components and the intent phrases of its links (see docs/design.md, LLM integration,
-- Grounding, and docs/adr/0050-responsibility-sentences-and-intent-phrases.md). An answer is keyed
-- by a hash of the prompt version, provider, model and prompt, so a changed input gives a new key and the old
-- row is simply no longer used. Its items are texts by the prompt's references, each citing the
-- references it rests on.
CREATE TABLE unit_wordings (
    cache_key      text        PRIMARY KEY CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    unit           text        NOT NULL CHECK (unit ~ '^[a-z][a-z0-9-]*:\S+$'),
    items          jsonb       NOT NULL CHECK (jsonb_typeof(items) = 'object'),
    prompt_version text        NOT NULL CHECK (prompt_version ~ '\S'),
    provider       text        NOT NULL CHECK (provider ~ '\S'),
    model          text        NOT NULL CHECK (model ~ '\S'),
    created_at     timestamptz NOT NULL DEFAULT now()
);
