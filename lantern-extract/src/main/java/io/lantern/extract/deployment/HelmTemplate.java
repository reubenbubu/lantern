package io.lantern.extract.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small reading of Helm templates, enough to see the images, environment variables and names a
 * chart deploys: each action that is a value ({@code .Values.x}, {@code .Chart.*}, {@code
 * .Release.*}, literals, {@code printf}, and the pipeline functions {@code default}, {@code quote},
 * {@code squote}, {@code upper}, {@code lower}, {@code trim}, {@code trunc}, {@code trimSuffix},
 * {@code trimPrefix}, {@code toString}) is replaced by its value; a line holding only control
 * actions ({@code if}, {@code range}, {@code end}, {@code toYaml}, {@code include}, ...) is
 * blanked. Both branches of an {@code if} are kept, and nothing is repeated for a {@code range}.
 * Line numbers are kept, so evidence points at the template's lines.
 */
final class HelmTemplate {

  private static final Pattern ACTION = Pattern.compile("\\{\\{-?(.*?)-?}}");
  private static final Pattern TOKEN =
      Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'|(\\S+)");

  /** Words that start an action that is not a value. */
  private static final Set<String> CONTROL =
      Set.of(
          "if",
          "else",
          "end",
          "range",
          "with",
          "define",
          "template",
          "include",
          "block",
          "toYaml",
          "tpl",
          "nindent",
          "indent",
          "required",
          "fail",
          "$");

  private HelmTemplate() {}

  /**
   * The template with its value actions replaced and its control lines blanked.
   *
   * @param text the template
   * @param values the chart's values
   * @param chart the chart's {@code Chart.yaml}
   */
  static String render(String text, Map<String, Object> values, Map<String, Object> chart) {
    String[] lines = text.split("\n", -1);
    List<String> out = new ArrayList<>();
    for (String line : lines) {
      Matcher m = ACTION.matcher(line);
      StringBuilder b = new StringBuilder();
      boolean control = false;
      boolean any = false;
      while (m.find()) {
        any = true;
        String action = m.group(1).strip();
        String value = null;
        if (action.startsWith("/*") || isControl(action)) {
          control = true;
        } else {
          value = evaluate(action, values, chart);
        }
        m.appendReplacement(b, Matcher.quoteReplacement(value == null ? "" : value));
      }
      m.appendTail(b);
      out.add(any && control && b.toString().isBlank() ? "" : b.toString());
    }
    return String.join("\n", out);
  }

  private static boolean isControl(String action) {
    String first = action.split("\\s+")[0];
    if (CONTROL.contains(first) || first.startsWith("$")) {
      return true;
    }
    for (String part : action.split("\\|")) {
      String word = part.strip().split("\\s+")[0];
      if (word.equals("toYaml")
          || word.equals("nindent")
          || word.equals("indent")
          || word.equals("include")) {
        return true;
      }
    }
    return false;
  }

  /** A pipeline's value, or null when it is not a scalar this reading knows. */
  private static String evaluate(
      String action, Map<String, Object> values, Map<String, Object> chart) {
    String value = null;
    boolean first = true;
    for (String part : action.split("\\|")) {
      List<String> tokens = tokens(part.strip());
      if (tokens.isEmpty()) {
        return null;
      }
      String head = tokens.getFirst();
      List<String> args = new ArrayList<>();
      for (String token : tokens.subList(1, tokens.size())) {
        args.add(argument(token, values, chart));
      }
      if (first) {
        value =
            switch (head) {
              case "printf" -> printf(args);
              case "default" ->
                  args.size() >= 2 && !blank(args.get(1))
                      ? args.get(1)
                      : args.isEmpty() ? null : args.getFirst();
              case "quote" -> args.isEmpty() ? null : "\"" + nonNull(args.getFirst()) + "\"";
              default -> argument(head, values, chart);
            };
        first = false;
        continue;
      }
      String argument = args.isEmpty() ? null : args.getFirst();
      value =
          switch (head) {
            case "default" -> blank(value) ? argument : value;
            case "quote" -> "\"" + nonNull(value) + "\"";
            case "squote" -> "'" + nonNull(value) + "'";
            case "upper" -> value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
            case "lower" -> value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
            case "trim", "toString", "int", "toJson" -> value;
            case "trunc" ->
                value == null || argument == null || !argument.matches("\\d+")
                    ? value
                    : value.substring(0, Math.min(value.length(), Integer.parseInt(argument)));
            case "trimSuffix" ->
                value != null && argument != null && value.endsWith(argument)
                    ? value.substring(0, value.length() - argument.length())
                    : value;
            case "trimPrefix" ->
                value != null && argument != null && value.startsWith(argument)
                    ? value.substring(argument.length())
                    : value;
            default -> null;
          };
    }
    return value;
  }

  private static String argument(
      String token, Map<String, Object> values, Map<String, Object> chart) {
    if (token.startsWith("\u0001")) {
      return token.substring(1);
    }
    if (token.startsWith(".Values.") || token.equals(".Values")) {
      Object node = values;
      for (String key : token.substring(".Values".length()).split("\\.")) {
        if (key.isEmpty()) {
          continue;
        }
        Map<String, Object> map = Yaml.map(node);
        node = map == null ? null : map.get(key);
      }
      return Yaml.string(node);
    }
    if (token.startsWith(".Chart.")) {
      String key = token.substring(".Chart.".length());
      return Yaml.string(chart.get(Character.toLowerCase(key.charAt(0)) + key.substring(1)));
    }
    if (token.equals(".Release.Name")) {
      return Yaml.string(chart.get("name"));
    }
    if (token.equals(".Release.Namespace")) {
      return "default";
    }
    if (token.matches("-?\\d+(\\.\\d+)?|true|false")) {
      return token;
    }
    return null;
  }

  private static List<String> tokens(String text) {
    List<String> tokens = new ArrayList<>();
    Matcher m = TOKEN.matcher(text);
    while (m.find()) {
      if (m.group(1) != null) {
        tokens.add("\u0001" + m.group(1).replace("\\\"", "\""));
      } else if (m.group(2) != null) {
        tokens.add("\u0001" + m.group(2));
      } else {
        tokens.add(m.group(3));
      }
    }
    return tokens;
  }

  private static String printf(List<String> args) {
    if (args.isEmpty() || args.getFirst() == null) {
      return null;
    }
    String format = args.getFirst();
    StringBuilder b = new StringBuilder();
    int next = 1;
    for (int i = 0; i < format.length(); i++) {
      char c = format.charAt(i);
      if (c == '%' && i + 1 < format.length()) {
        char verb = format.charAt(++i);
        if (verb == '%') {
          b.append('%');
        } else {
          b.append(next < args.size() ? nonNull(args.get(next++)) : "");
        }
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }

  private static boolean blank(String value) {
    return value == null || value.isEmpty();
  }

  private static String nonNull(String value) {
    return value == null ? "" : value;
  }
}
