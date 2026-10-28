-- A call whose answer stopped at its output limit is logged as TRUNCATED rather than SUCCEEDED (see
-- docs/adr/0022-models-that-think.md). Its tokens and cost are stored like a successful call's.
ALTER TABLE llm_calls DROP CONSTRAINT llm_calls_outcome_check;
ALTER TABLE llm_calls ADD CONSTRAINT llm_calls_outcome_check
    CHECK (outcome IN ('SUCCEEDED', 'TRUNCATED', 'FAILED', 'REFUSED'));
