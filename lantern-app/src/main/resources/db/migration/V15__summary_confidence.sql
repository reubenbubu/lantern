-- How far a summary can be trusted, from 0 to 1 as a fact's confidence (see
-- docs/adr/0066-confidence-of-summaries-and-wordings.md): 0.5 for an evidenced purpose and 0.35 for
-- an inferred one, times the share of the answer's sentences kept. Rows written before did not
-- record how many sentences were left out, so they get the confidence of their purpose alone.
ALTER TABLE summaries ADD COLUMN confidence double precision;
UPDATE summaries SET confidence = CASE purpose WHEN 'EVIDENCED' THEN 0.5 ELSE 0.35 END;
ALTER TABLE summaries
    ALTER COLUMN confidence SET NOT NULL,
    ADD CHECK (confidence > 0 AND confidence <= 1);
