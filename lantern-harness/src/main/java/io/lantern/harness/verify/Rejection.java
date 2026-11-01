package io.lantern.harness.verify;

/**
 * One part of an answer a check rejected.
 *
 * @param item the item's local ID, such as {@code I3}
 * @param field the field, such as {@code components.C2}, or empty for the whole item
 * @param check the check's name
 * @param reason why, in a sentence the repair turn shows the model
 */
public record Rejection(String item, String field, String check, String reason) {}
