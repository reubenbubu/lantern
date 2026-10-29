-- Cached answers of the LLM resolver for links static matching leaves open (see docs/design.md,
-- Cross-repo linking and LLM integration, and docs/adr/0045-llm-link-resolver.md). An answer is
-- keyed by a hash of the prompt version, model and prompt, so a changed input gives a new key and the
-- old row is simply no longer used. An answer of unknown has no target and no confidence.
CREATE TABLE link_resolutions (
    cache_key      text        PRIMARY KEY CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    link           text        NOT NULL CHECK (link ~ '\S'),
    target         text        CHECK (target ~ '^[a-z][a-z0-9-]*:\S+$'),
    confidence     text        CHECK (confidence IN ('high', 'medium', 'low')),
    cited          jsonb       NOT NULL CHECK (jsonb_typeof(cited) = 'array'),
    rationale      text        NOT NULL,
    prompt_version text        NOT NULL CHECK (prompt_version ~ '\S'),
    provider       text        NOT NULL CHECK (provider ~ '\S'),
    model          text        NOT NULL CHECK (model ~ '\S'),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CHECK ((target IS NULL) = (confidence IS NULL))
);

-- The resolver falls back to the last answer for a link when it cannot ask again.
CREATE INDEX link_resolutions_link ON link_resolutions (link, created_at);
