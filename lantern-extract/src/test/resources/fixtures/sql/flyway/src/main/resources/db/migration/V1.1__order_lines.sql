CREATE TABLE order_line (
    order_id BIGINT NOT NULL,
    line_no  INT    NOT NULL,
    sku      VARCHAR(40),
    quantity INT    NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT order_line_order_fk FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);
CREATE INDEX order_line_sku ON order_line (sku);
