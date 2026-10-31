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
 * blanked. Both branches of an {@code if} are kept. A {@code range} at the top of a template over a
 * map or list of the values ({@code range $name, $service := .Values.services}) gives one rendering
 * of its body per entry, with its variables, {@code .} and {@code $} bound ({@link #renderAll}); a
 * {@code range} inside another block, or over anything else, is blanked and its body kept once.
 * Line numbers are kept, so evidence points at the template's lines.
 */
final class HelmTemplate {

  private static final Pattern ACTION = Pattern.compile("\\{\\{-?(.*?)-?}}");
  private static final Pattern RANGE =
      Pattern.compile(
          "\\s*\\{\\{-?\\s*range\\s+(?:(\\$\\w+)\\s*,\\s*)?(\\$\\w+)\\s*:=\\s*(\\S+)\\s*-?}}\\s*");
  private static final Pattern BLOCK_START =
      Pattern.compile("\\{\\{-?\\s*(if|range|with|define|block)\\b");
  private static final Pattern BLOCK_END = Pattern.compile("\\{\\{-?\\s*end\\s*-?}}");
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
    return render(text.split("\n", -1), values, chart, Map.of());
  }

  /**
   * The renderings of a template: the template as {@link #render} gives it, with the bodies of the
   * ranges at its top level that range over values blanked, then for each such range and each of
   * its entries, a rendering of that body alone, its variables bound. Each rendering has the
   * template's lines.
   */
  static List<String> renderAll(
      String text, Map<String, Object> values, Map<String, Object> chart) {
    String[] lines = text.split("\n", -1);
    List<int[]> ranges = new ArrayList<>();
    List<Object> over = new ArrayList<>();
    int depth = 0;
    int start = -1;
    for (int n = 0; n < lines.length; n++) {
      Matcher range = RANGE.matcher(lines[n]);
      if (depth == 0 && range.matches()) {
        start = n;
      }
      depth += count(BLOCK_START, lines[n]) - count(BLOCK_END, lines[n]);
      if (start >= 0 && depth == 0) {
        Matcher head = RANGE.matcher(lines[start]);
        head.matches();
        Object node = node(head.group(3), values, Map.of());
        if (node instanceof Map<?, ?> || node instanceof List<?>) {
          ranges.add(new int[] {start, n});
          over.add(node);
        }
        start = -1;
      }
      if (depth < 0) {
        depth = 0;
      }
    }
    List<String> out = new ArrayList<>();
    String[] outside = lines.clone();
    for (int[] r : ranges) {
      for (int n = r[0]; n <= r[1]; n++) {
        outside[n] = "";
      }
    }
    out.add(render(outside, values, chart, Map.of()));
    for (int i = 0; i < ranges.size(); i++) {
      int[] r = ranges.get(i);
      Matcher head = RANGE.matcher(lines[r[0]]);
      head.matches();
      String keyVar = head.group(1);
      String valueVar = head.group(2);
      List<Map.Entry<Object, Object>> entries = new ArrayList<>();
      if (over.get(i) instanceof Map<?, ?> map) {
        map.forEach((k, v) -> entries.add(Map.entry(k, v == null ? "" : v)));
      } else if (over.get(i) instanceof List<?> list) {
        for (int k = 0; k < list.size(); k++) {
          entries.add(Map.entry(k, list.get(k) == null ? "" : list.get(k)));
        }
      }
      for (Map.Entry<Object, Object> entry : entries) {
        String[] body = new String[lines.length];
        java.util.Arrays.fill(body, "");
        System.arraycopy(lines, r[0] + 1, body, r[0] + 1, r[1] - r[0] - 1);
        Map<String, Object> vars = new java.util.HashMap<>();
        if (keyVar != null) {
          vars.put(keyVar, String.valueOf(entry.getKey()));
        }
        vars.put(valueVar, entry.getValue());
        vars.put(".", entry.getValue());
        out.add(render(body, values, chart, vars));
      }
    }
    return out;
  }

  private static int count(Pattern pattern, String line) {
    int found = 0;
    Matcher m = pattern.matcher(line);
    while (m.find()) {
      found++;
    }
    return found;
  }

  private static String render(
      String[] lines,
      Map<String, Object> values,
      Map<String, Object> chart,
      Map<String, Object> vars) {
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
        if (action.startsWith("/*") || (isControl(action) && !isVariable(action, vars))) {
          control = true;
        } else {
          value = evaluate(action, values, chart, vars);
        }
        m.appendReplacement(b, Matcher.quoteReplacement(value == null ? "" : value));
      }
      m.appendTail(b);
      out.add(any && control && b.toString().isBlank() ? "" : b.toString());
    }
    return String.join("\n", out);
  }

  /** Whether an action is a value of a bound variable ({@code $name}, {@code $service.image}). */
  private static boolean isVariable(String action, Map<String, Object> vars) {
    String first = action.split("[\\s|]+")[0];
    if (!first.startsWith("$") || action.contains(":=")) {
      return false;
    }
    String name = first.equals("$") || first.startsWith("$.") ? "$" : first.split("\\.")[0];
    return name.equals("$") ? !vars.isEmpty() : vars.containsKey(name);
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
      String action,
      Map<String, Object> values,
      Map<String, Object> chart,
      Map<String, Object> vars) {
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
        args.add(argument(token, values, chart, vars));
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
              default -> argument(head, values, chart, vars);
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
      String token,
      Map<String, Object> values,
      Map<String, Object> chart,
      Map<String, Object> vars) {
    if (token.startsWith("\u0001")) {
      return token.substring(1);
    }
    if (token.startsWith("$.")) {
      token = token.substring(1);
    }
    if (token.startsWith(".Values.") || token.equals(".Values") || token.startsWith("$")) {
      return Yaml.string(node(token, values, vars));
    }
    if (token.startsWith(".")
        && !token.startsWith(".Chart.")
        && !token.startsWith(".Release.")
        && vars.containsKey(".")) {
      return Yaml.string(path(vars.get("."), token));
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

  /** What a reference names: {@code .Values.a.b}, {@code $.Values.a}, or {@code $var.a}. */
  private static Object node(String token, Map<String, Object> values, Map<String, Object> vars) {
    if (token.startsWith("$.")) {
      token = token.substring(1);
    }
    if (token.startsWith(".Values")) {
      return path(values, token.substring(".Values".length()));
    }
    if (token.startsWith("$")) {
      int dot = token.indexOf('.');
      String name = dot < 0 ? token : token.substring(0, dot);
      return vars.containsKey(name)
          ? path(vars.get(name), dot < 0 ? "" : token.substring(dot))
          : null;
    }
    return null;
  }

  private static Object path(Object node, String path) {
    for (String key : path.split("\\.")) {
      if (key.isEmpty()) {
        continue;
      }
      Map<String, Object> map = Yaml.map(node);
      node = map == null ? null : map.get(key);
    }
    return node;
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
