CREATE OR REPLACE VIEW open_orders AS SELECT * FROM orders WHERE status = 'NEW';
