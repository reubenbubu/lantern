package io.lantern.llm;

/** Rough token counts, for estimates before a call and for fakes. */
public final class Tokens {

  private Tokens() {}

  /** About four characters per token, rounded up. */
  public static long estimate(long chars) {
    return (chars + 3) / 4;
  }

  /** About four characters per token, rounded up. */
  public static long estimate(String text) {
    return estimate(text.length());
  }
}
