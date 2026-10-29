-- Orders and their lines.
CREATE TABLE customer (
    id    BIGSERIAL PRIMARY KEY,
    email VARCHAR(200) NOT NULL UNIQUE
);

CREATE TABLE orders (
    id          BIGSERIAL,
    customer_id BIGINT      NOT NULL REFERENCES customer (id),
    status      VARCHAR(20) NOT NULL DEFAULT 'NEW',
    note        TEXT,
    CONSTRAINT orders_pk PRIMARY KEY (id)
);

/* A semicolon in a comment; is not a statement end. */
INSERT INTO customer (email) VALUES ('a;b@example.com');
