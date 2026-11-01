-- Whether a call went through the provider's batch API, at its discount (docs/adr/0102-batch-api-and-openai.md).
ALTER TABLE llm_calls ADD COLUMN batch boolean NOT NULL DEFAULT false;
