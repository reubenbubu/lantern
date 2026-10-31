package io.lantern.extract;

/**
 * What an extractor's facts are extracted and cached by (ADR 0092): the smallest part of a snapshot
 * whose files are all the facts depend on.
 */
public enum CacheUnit {
  /** One file of one repository: the facts depend on that file alone. */
  FILE,
  /** One repository with its build: the facts depend on nothing outside it. */
  REPOSITORY,
  /** Every repository of the snapshot: the facts may depend on any of them. */
  SNAPSHOT
}
