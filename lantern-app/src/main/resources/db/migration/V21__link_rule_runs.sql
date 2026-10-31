-- Each link rule's last run per system (ADR 0095): its version and digests of the facts it read and
-- gave, so a rule whose input and version are unchanged is not run again.
CREATE TABLE link_rule_runs (
    scope    TEXT   NOT NULL,
    rule     TEXT   NOT NULL,
    version  TEXT   NOT NULL,
    inputs   TEXT   NOT NULL,
    outputs  TEXT   NOT NULL,
    snapshot BIGINT NOT NULL,
    PRIMARY KEY (scope, rule)
);
