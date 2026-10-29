CREATE TABLE public.shipment (
    id       BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL
);
ALTER TABLE shipment ADD CONSTRAINT shipment_order_fk FOREIGN KEY (order_id) REFERENCES orders;
