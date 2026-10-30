--liquibase formatted sql
--property name:audit.type value:json dbms:mysql
--property name:audit.type value:jsonb dbms:postgresql

--changeset test:10 dbms:mysql
CREATE TABLE audit_log (id BIGINT PRIMARY KEY, payload ${audit.type});

--changeset test:11 dbms:h2
CREATE TABLE h2_log (id BIGINT);

--changeset test:12 context:faker
CREATE TABLE fake_rows (id BIGINT);

--changeset test:13 contextFilter:"eu or us"
CREATE TABLE regional (id BIGINT);
