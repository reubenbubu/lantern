package io.lantern.harness.verify;

/**
 * A claim an answer proposes, such as a connection Investigate found, for the Conflict check.
 *
 * @param field the field it is in
 * @param subject its subject entity
 * @param predicate its predicate
 * @param object its object
 */
public record ProposedClaim(String field, String subject, String predicate, String object) {}
