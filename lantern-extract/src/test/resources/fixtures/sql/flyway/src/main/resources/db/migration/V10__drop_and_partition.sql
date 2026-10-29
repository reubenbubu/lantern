DROP TABLE IF EXISTS audit_log;
ALTER TABLE orders DROP COLUMN remark;
CREATE TABLE event (
    id      BIGINT NOT NULL,
    created DATE   NOT NULL,
    payload JSONB
) PARTITION BY RANGE (created);
