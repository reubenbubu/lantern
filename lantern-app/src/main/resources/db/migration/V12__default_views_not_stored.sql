-- Default view definitions are no longer stored; a stored view overrides the default
-- (docs/adr/0047-sequence-flows.md). Removes the rows earlier runs stored with the defaults of the
-- time, so new default sections, such as the C4 report's main flows, reach existing workspaces.
-- Rows that differ from every earlier default are kept as overrides.
DELETE FROM view_definitions
WHERE NOT (definition ? 'audience')
  AND (
    (definition->'scope' = jsonb_build_object('kind', 'repo', 'repo', definition->>'title')
      AND (
        (id = definition->>'title'
          AND definition->'template' = '{"name": "architecture-explorer", "version": "1"}'
          AND definition->'sections' IN (
            '["overview", "context", "containers", "components", "dependencies", "structure"]'::jsonb))
        OR (id = (definition->>'title') || '.brief'
          AND definition->'template' = '{"name": "executive-brief", "version": "1"}'
          AND definition->'sections' = '["overview", "headlines", "risks", "context", "containers"]')
        OR (id = (definition->>'title') || '.c4'
          AND definition->'template' = '{"name": "c4-model-sequence-diagrams", "version": "1"}'
          AND definition->'sections' = '["overview", "context", "containers", "components"]')))
    OR (id = 'system:c4'
      AND definition->>'title' = 'System'
      AND definition->'scope' = '{"kind": "system"}'
      AND definition->'template' = '{"name": "c4-model-sequence-diagrams", "version": "1"}'
      AND definition->'sections' = '["overview", "context", "containers", "components"]'));
