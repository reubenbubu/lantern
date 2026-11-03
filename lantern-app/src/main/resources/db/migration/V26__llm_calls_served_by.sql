-- Who served a call when a router in front of the provider says so, such as "Anthropic, own key"
-- through OpenRouter (docs/adr/0102-batch-api-and-openai.md, amendment for #754); null otherwise.
ALTER TABLE llm_calls ADD COLUMN served_by text;
