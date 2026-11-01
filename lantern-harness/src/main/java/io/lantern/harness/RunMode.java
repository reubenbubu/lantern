package io.lantern.harness;

/** How a role's requests are sent (knowledge-build epic, section 10, Run modes). */
public enum RunMode {
  /** Synchronous requests. */
  INTERACTIVE,
  /** Through the provider's batch API when the role has enough cache misses in the run. */
  BULK
}
