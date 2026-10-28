package io.lantern.sandbox;

/** How a build ended. */
public enum BuildStatus {
  /** Maven compiled every module. */
  SUCCEEDED,
  /**
   * The build failed, timed out or could not start. The repository is analyzed in degraded mode,
   * with whatever outputs the build left.
   */
  FAILED
}
