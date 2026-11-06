package io.lantern.link;

import io.lantern.harness.FileRead;
import io.lantern.harness.Harness;
import io.lantern.llm.LlmTool;
import io.lantern.llm.ToolBox;
import java.util.List;

/**
 * The tools of one Investigate conversation, and what they showed it: the lines it may cite, the
 * files its answers rest on and the texts the injection screen reads. {@link GraphTools} and {@link
 * ShellTools} (#893) are such tools.
 */
interface CodeTools extends ToolBox {

  /** The tools a lead's conversation (Mode A) is offered. */
  List<LlmTool> leadTools();

  /** The tools a unit's or the system's conversation (Modes B and C) is offered. */
  List<LlmTool> exploreTools();

  /** Records the code excerpts a request gives the conversation as shown (#715). */
  void supplied(String request);

  /** Whether the conversation was shown every line of a range of a file. */
  boolean showed(String repo, String path, int from, int to);

  /** The files the tools read, with their blobs, so a cached answer holds while each keeps it. */
  List<FileRead> filesRead();

  /** The files whose text the tools showed, as shown, for the injection screen (ADR 0101). */
  List<Harness.Shown> textsShown();
}
