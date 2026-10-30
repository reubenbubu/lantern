CREATE TABLE ${flyway:defaultSchema}.${table_prefix}invoice (
  id BIGSERIAL PRIMARY KEY,
  total NUMERIC(12, 2) NOT NULL
);
