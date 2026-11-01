package io.lantern.harness;

/** Small checks on values the harness is given. */
final class Text {

  private Text() {}

  static String nonBlank(String what, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    return value;
  }
}
