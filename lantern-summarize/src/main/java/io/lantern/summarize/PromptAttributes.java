package io.lantern.summarize;

import java.util.List;

/** What every prompt leaves out of a fact's attributes (knowledge-build epic, section 10). */
public final class PromptAttributes {

  private PromptAttributes() {}

  /**
   * The attributes that name other facts by ID (ADR 0091), or list a link's candidates: never
   * shown, since an ID changes with any edit of its fact's file and would re-ask whatever shows it.
   */
  public static final List<String> ID_ATTRIBUTES =
      List.of("facts", "cites", "fact", "claim", "contradictedBy", "link", "candidates");
}
