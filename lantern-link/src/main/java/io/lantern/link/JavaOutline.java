package io.lantern.link;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The declarations of a Java file as Investigate's tools show them (#893): each class, interface,
 * enum or record and each constructor or method, with its line range and the annotations written on
 * it, read from the text alone. It is a guide to where to read, not a parser: a declaration it
 * misses is still found by a search, and what it shows is never cited, only the lines it points to.
 */
final class JavaOutline {

  /**
   * One declaration.
   *
   * @param from its first line, its first annotation's if it has any
   * @param to its last line, the one that closes its body
   * @param text its annotations and signature on one line, as written, spaces collapsed
   * @param name the class or method name
   */
  record Declaration(int from, int to, String text, String name) {}

  private static final Pattern TYPE =
      Pattern.compile(
          "^(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
              + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");

  private static final Pattern METHOD =
      Pattern.compile(
          "^(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\\s+)*"
              + "(?:<[^>]*>\\s+)?(?:[\\w$.<>\\[\\],?&\\s]+\\s+)?([A-Za-z_$][\\w$]*)\\s*\\(");

  private static final Pattern ANNOTATION = Pattern.compile("^@[A-Za-z_$][\\w$.]*");

  private static final List<String> NOT_METHODS =
      List.of("if", "for", "while", "switch", "catch", "return", "new", "throw", "else", "do");

  private JavaOutline() {}

  /** The declarations of a file's lines, in the order they start. */
  static List<Declaration> of(List<String> lines) {
    List<Declaration> found = new ArrayList<>();
    int annotationsFrom = -1;
    StringBuilder annotations = new StringBuilder();
    int parens = 0;
    for (int i = 0; i < lines.size(); i++) {
      String raw = lines.get(i).strip();
      String line = code(lines.get(i)).strip();
      if (parens > 0) {
        // Inside an annotation's arguments over several lines.
        annotations.append(' ').append(raw);
        parens += count(line, '(') - count(line, ')');
        continue;
      }
      if (ANNOTATION.matcher(line).find() && !line.contains("{")) {
        if (annotationsFrom < 0) {
          annotationsFrom = i;
        }
        annotations.append(annotations.isEmpty() ? "" : " ").append(raw);
        parens = Math.max(0, count(line, '(') - count(line, ')'));
        continue;
      }
      String bare = line.replaceAll("^(?:@[\\w$.]+(?:\\([^)]*\\))?\\s*)+", "");
      Matcher type = TYPE.matcher(bare);
      Matcher method = METHOD.matcher(bare);
      String name = null;
      if (type.find()) {
        name = type.group(1);
      } else if (method.find()
          && !NOT_METHODS.contains(method.group(1))
          && !bare.endsWith(";")
          && !bare.contains("=")
          && !bare.startsWith(".")) {
        name = method.group(1);
      }
      if (name != null) {
        int from = annotationsFrom >= 0 ? annotationsFrom : i;
        int to = end(lines, i);
        String text =
            (annotations.isEmpty() ? "" : annotations + " ") + signature(lines, i).strip();
        found.add(new Declaration(from + 1, to + 1, text.replaceAll("\\s+", " "), name));
      }
      if (!line.isEmpty()) {
        annotationsFrom = -1;
        annotations.setLength(0);
      }
    }
    return found;
  }

  /** The innermost declaration holding a line, or null. */
  static Declaration enclosing(List<Declaration> declarations, int line) {
    Declaration best = null;
    for (Declaration d : declarations) {
      if (d.from() <= line && line <= d.to() && (best == null || d.from() >= best.from())) {
        best = d;
      }
    }
    return best;
  }

  /** The signature from its line up to its body's opening brace or its semicolon. */
  private static String signature(List<String> lines, int start) {
    StringBuilder text = new StringBuilder();
    for (int i = start; i < lines.size() && i < start + 8; i++) {
      String raw = lines.get(i);
      String line = code(raw);
      int brace = line.indexOf('{');
      int semi = line.indexOf(';');
      int stop = brace >= 0 && (semi < 0 || brace < semi) ? brace : semi;
      if (stop >= 0) {
        return text.append(' ').append(raw, 0, stop).toString();
      }
      text.append(' ').append(raw.replaceFirst("\\s*//.*$", ""));
    }
    return text.toString();
  }

  /** The line that closes the body opened at or after the declaration's line. */
  private static int end(List<String> lines, int start) {
    int depth = 0;
    boolean opened = false;
    for (int i = start; i < lines.size(); i++) {
      String line = code(lines.get(i));
      for (int c = 0; c < line.length(); c++) {
        char ch = line.charAt(c);
        if (ch == '{') {
          depth++;
          opened = true;
        } else if (ch == '}') {
          depth--;
          if (opened && depth == 0) {
            return i;
          }
        } else if (ch == ';' && !opened) {
          // An abstract or interface method: it ends where it is declared.
          return i;
        }
      }
    }
    return start;
  }

  /**
   * A line with its string and char literals and its line comment blanked out, so braces count
   * true; of the same length, so a position in it is one in the line.
   */
  static String code(String line) {
    StringBuilder out = new StringBuilder(line.length());
    boolean string = false;
    char quote = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (string) {
        if (c == '\\' && i + 1 < line.length()) {
          out.append("  ");
          i++;
        } else if (c == quote) {
          string = false;
          out.append(c);
        } else {
          out.append(' ');
        }
        continue;
      }
      if (c == '"' || c == '\'') {
        string = true;
        quote = c;
        out.append(c);
      } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
        out.append(" ".repeat(line.length() - i));
        break;
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static int count(String text, char c) {
    return (int) text.chars().filter(ch -> ch == c).count();
  }
}
