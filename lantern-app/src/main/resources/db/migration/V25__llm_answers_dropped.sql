-- An item whose answer failed the checks twice is stored too, with why, so a rerun on the same
-- inputs repeats the drop without a call (#682; docs/adr/0100-llm-harness.md, amendment for #682).
-- It is asked again only when its fingerprint changes. Such a row has no answer and is not omitted.
ALTER TABLE llm_answers ADD COLUMN dropped text CHECK (dropped ~ '\S');
ALTER TABLE llm_answers DROP CONSTRAINT llm_answers_check;
ALTER TABLE llm_answers ADD CONSTRAINT llm_answers_check
    CHECK ((answer IS NULL) = (omitted OR dropped IS NOT NULL) AND NOT (omitted AND dropped IS NOT NULL));
