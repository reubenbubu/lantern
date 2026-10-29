--liquibase formatted sql

--changeset test:5
CREATE TABLE supplier (id BIGINT PRIMARY KEY, name VARCHAR(100));
--rollback DROP TABLE supplier;

--changeset test:6
ALTER TABLE warehouse DROP COLUMN city;
