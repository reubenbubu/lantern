package io.lantern.sandbox;

/**
 * The sandbox itself could not run a build, for example because Docker is unavailable. Unlike a
 * failed build, this says nothing about the repository, so no build result is stored.
 */
public class SandboxException extends RuntimeException {

  public SandboxException(String message) {
    super(message);
  }

  public SandboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
