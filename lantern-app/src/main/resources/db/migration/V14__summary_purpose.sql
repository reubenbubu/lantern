-- Whether a summary's purpose sentence takes the unit's purpose from what people wrote (a README,
-- Javadoc, a part's evidenced summary) or infers it from the code (see
-- docs/adr/0059-evidenced-and-inferred-purpose.md). Rows written before have no such mark and are
-- no longer used, since the prompt version changed; they count as inferred.
ALTER TABLE summaries
    ADD COLUMN purpose text NOT NULL DEFAULT 'INFERRED'
        CHECK (purpose IN ('EVIDENCED', 'INFERRED'));
