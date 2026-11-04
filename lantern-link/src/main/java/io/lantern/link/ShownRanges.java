package io.lantern.link;

import io.lantern.harness.verify.Check;
import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Rejection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Every file range an answer cites is one the conversation was shown: lines {@code read_file} read,
 * a line {@code find_in_code} found, or an excerpt of the request (#715). A range the model never
 * saw is rejected with a reason naming it, so a citation always traces to what the model was shown
 * ({@code docs/quality-bar.md}).
 */
final class ShownRanges implements Check {

  /** A file range as an answer cites it, {@code repo:path:from-to}, or one line. */
  static final Pattern RANGE = Pattern.compile("^([^:\\s]+):([^:\\s]+):(\\d+)(?:-(\\d+))?$");

  private final Supplier<GraphTools> tools;
  private final Function<JsonNode, Map<String, List<String>>> cites;

  /**
   * @param tools the tools of the conversation being checked
   * @param cites an answer's cites, by the field a rejection names
   */
  ShownRanges(Supplier<GraphTools> tools, Function<JsonNode, Map<String, List<String>>> cites) {
    this.tools = tools;
    this.cites = cites;
  }

  @Override
  public String name() {
    return "Shown";
  }

  @Override
  public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
    if (answer.omitted() || answer.raw() == null) {
      return List.of();
    }
    GraphTools shown = tools.get();
    List<Rejection> found = new ArrayList<>();
    for (Map.Entry<String, List<String>> field : cites.apply(answer.raw()).entrySet()) {
      for (String cite : field.getValue()) {
        Matcher range = RANGE.matcher(cite);
        if (!range.matches()) {
          continue;
        }
        int from = Integer.parseInt(range.group(3));
        int to = range.group(4) == null ? from : Integer.parseInt(range.group(4));
        if (shown == null || !shown.showed(range.group(1), range.group(2), from, to)) {
          found.add(
              new Rejection(
                  answer.item(),
                  field.getKey(),
                  name(),
                  "Cites "
                      + cite
                      + ", lines this conversation was not shown; cite only lines read_file"
                      + " showed, lines find_in_code found or an excerpt of the request."));
        }
      }
    }
    return found;
  }
}
