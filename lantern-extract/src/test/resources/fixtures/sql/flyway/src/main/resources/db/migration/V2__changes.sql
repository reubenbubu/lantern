ALTER TABLE orders RENAME COLUMN note TO remark;
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(40);
ALTER TABLE order_line ALTER COLUMN sku SET NOT NULL;
ALTER TABLE orders ADD COLUMN placed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_line ADD CONSTRAINT order_line_sku_uk UNIQUE (order_id, sku);
CREATE TABLE audit_log (id INT);
CREATE OR REPLACE FUNCTION touch() RETURNS trigger AS $$
BEGIN
  -- ; inside a function body
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
