package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.vocabulary.BuiltInTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Chooses the source excerpts a type's prompt shows: the type's declaration with its Javadoc and
 * annotations, then its members' declarations (signature, Javadoc and annotations), and the whole
 * of short members while the budget allows. Each excerpt is taken from the file and lines of one
 * fact's evidence, which is what citing it cites.
 */
final class Excerpts {

  /** The most tokens a type's excerpts take, all together. */
  static final int MAX_TOKENS = 2_000;

  /** Members up to this many lines (Javadoc not counted) are shown whole, budget permitting. */
  static final int SHORT_MEMBER_LINES = 20;

  /** Longer Javadoc is cut to this many lines. */
  static final int MAX_JAVADOC_LINES = 15;

  /** The most lines of a declaration before its body is reached. */
  private static final int MAX_DECLARATION_LINES = 15;

  /** Longer lines are cut. */
  private static final int MAX_LINE_CHARS = 200;

  /** What an excerpt costs beyond its code and path, in characters: its reference and fence. */
  private static final int OVERHEAD_CHARS = 40;

  /** Marks lines left out of an excerpt. */
  static final String ELIDED = "...";

  private static final String METHOD = "method";
  private static final String FIELD = "field";

  private Excerpts() {}

  /**
   * An excerpt of a source file.
   *
   * @param fact the ID of the fact whose evidence it comes from, which citing it cites
   * @param path the file, relative to its repository
   * @param start the first line shown, 1-based
   * @param end the last line shown
   * @param code the lines, with their common indentation removed and left-out lines marked
   */
  record Excerpt(String fact, String path, int start, int end, String code) {}

  /**
   * The excerpts for a unit, in file and line order; none unless it is a type.
   *
   * @param lines a file's lines for a fact's evidence, or empty if the file cannot be read or is
   *     not the version the fact was observed in
   */
  static List<Excerpt> of(SummaryNode node, Function<Evidence, Optional<List<String>>> lines) {
    if (node.level() != SummaryLevel.TYPE) {
      return List.of();
    }
    List<Candidate> declarations = new ArrayList<>();
    List<Candidate> members = new ArrayList<>();
    for (Fact fact : node.facts()) {
      Evidence evidence = fact.evidence();
      if (!fact.subject().equals(node.entity()) || evidence.lines() == null) {
        continue;
      }
      boolean declaration = fact.predicate().equals("kind");
      boolean member = fact.predicate().equals(BuiltInTypes.CONTAINS) && isMember(fact.object());
      if (!declaration && !member) {
        continue;
      }
      Optional<List<String>> file = lines.apply(evidence);
      if (file.isEmpty() || evidence.lines().end() > file.get().size()) {
        continue;
      }
      Candidate candidate = new Candidate(fact, file.get());
      (declaration ? declarations : members).add(candidate);
    }
    Comparator<Candidate> inFileOrder =
        Comparator.comparing((Candidate c) -> c.fact.evidence().path())
            .thenComparingInt(c -> c.fact.evidence().lines().start())
            // A field before the members Lombok places on its line, such as its getter.
            .thenComparing(c -> !c.fact.object().value().startsWith(FIELD + ":"))
            .thenComparing(c -> c.fact.id());
    declarations.sort(inFileOrder);
    members.sort(inFileOrder);

    int budget = MAX_TOKENS * 4;
    List<Excerpt> chosen = new ArrayList<>();
    for (Candidate declaration : declarations) {
      budget -= add(chosen, declaration.declaration(), budget);
    }
    List<Integer> shortForms = new ArrayList<>();
    List<Candidate> shown = new ArrayList<>();
    for (Candidate member : members) {
      Excerpt signature = member.signature();
      int cost = add(chosen, signature, budget);
      if (cost > 0) {
        budget -= cost;
        shortForms.add(chosen.size() - 1);
        shown.add(member);
      }
    }
    for (int i = 0; i < shown.size(); i++) {
      Optional<Excerpt> whole = shown.get(i).whole();
      int index = shortForms.get(i);
      if (whole.isPresent() && !whole.get().equals(chosen.get(index))) {
        int extra = cost(whole.get()) - cost(chosen.get(index));
        if (extra <= budget) {
          chosen.set(index, whole.get());
          budget -= extra;
        }
      }
    }
    chosen.sort(
        Comparator.comparing(Excerpt::path)
            .thenComparingInt(Excerpt::start)
            .thenComparing(Excerpt::fact));
    return List.copyOf(chosen);
  }

  private static boolean isMember(FactObject object) {
    if (object.kind() != FactObject.Kind.ENTITY) {
      return false;
    }
    String type = EntityId.parse(object.value()).type();
    return type.equals(METHOD) || type.equals(FIELD);
  }

  /**
   * Adds the excerpt unless it overlaps one already chosen from the same file (members Lombok
   * generates are placed on their annotation's line) or does not fit the budget.
   *
   * @return what it cost, 0 if it was not added
   */
  private static int add(List<Excerpt> chosen, Excerpt excerpt, int budget) {
    for (Excerpt other : chosen) {
      if (other.path().equals(excerpt.path())
          && other.start() <= excerpt.end()
          && excerpt.start() <= other.end()) {
        return 0;
      }
    }
    int cost = cost(excerpt);
    if (cost > budget) {
      return 0;
    }
    chosen.add(excerpt);
    return cost;
  }

  private static int cost(Excerpt excerpt) {
    return excerpt.code().length() + excerpt.path().length() + OVERHEAD_CHARS;
  }

  /** A fact whose evidence an excerpt can be taken from, with its file's lines. */
  private record Candidate(Fact fact, List<String> file) {

    int start() {
      return fact.evidence().lines().start();
    }

    int end() {
      return fact.evidence().lines().end();
    }

    /** The type's Javadoc, annotations and declaration, up to the opening brace. */
    Excerpt declaration() {
      return excerpt(javadocStart(), start(), Math.min(end(), bodyStart()), false);
    }

    /** The member's Javadoc and annotations, and its declaration up to its body. */
    Excerpt signature() {
      int last = Math.min(end(), bodyStart());
      return excerpt(javadocStart(), start(), last, last < end());
    }

    /** The whole member, if it is short. */
    Optional<Excerpt> whole() {
      if (end() - start() + 1 > SHORT_MEMBER_LINES) {
        return Optional.empty();
      }
      return Optional.of(excerpt(javadocStart(), start(), end(), false));
    }

    /**
     * The first line, from the start, that opens a body or ends a declaration: a brace or semicolon
     * outside parentheses, strings and comments, so that annotations such as
     * {@code @GetMapping("/{id}")} or {@code @SuppressWarnings({"a", "b"})} do not count.
     */
    private int bodyStart() {
      int limit = Math.min(end(), start() + MAX_DECLARATION_LINES - 1);
      int depth = 0;
      boolean inComment = false;
      for (int line = start(); line <= limit; line++) {
        String text = file.get(line - 1);
        for (int i = 0; i < text.length(); i++) {
          char c = text.charAt(i);
          if (inComment) {
            if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
              inComment = false;
              i++;
            }
          } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
            break;
          } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
            inComment = true;
            i++;
          } else if (c == '"' || c == '\'') {
            i = closingQuote(text, i);
          } else if (c == '(') {
            depth++;
          } else if (c == ')') {
            depth = Math.max(0, depth - 1);
          } else if (depth == 0 && (c == '{' || c == ';')) {
            return line;
          }
        }
      }
      return limit;
    }

    /** The index of the quote that closes the literal opened at {@code open}, or the line's end. */
    private static int closingQuote(String text, int open) {
      char quote = text.charAt(open);
      for (int i = open + 1; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          return i;
        }
      }
      return text.length();
    }

    /** The first line of the Javadoc right above the start, or the start if there is none. */
    private int javadocStart() {
      int above = start() - 1;
      if (above < 1 || !file.get(above - 1).strip().endsWith("*/")) {
        return start();
      }
      for (int line = above; line >= 1 && line >= above - 200; line--) {
        String text = file.get(line - 1).strip();
        if (text.startsWith("/**")) {
          return line;
        }
        // Another comment, or code before a comment that ends on this line, is not Javadoc.
        if (text.contains("/*") || (line < above && text.contains("*/"))) {
          return start();
        }
      }
      return start();
    }

    private Excerpt excerpt(int javadoc, int from, int to, boolean bodyLeftOut) {
      List<String> lines = new ArrayList<>();
      int javadocLines = from - javadoc;
      if (javadocLines > MAX_JAVADOC_LINES) {
        lines.addAll(file.subList(javadoc - 1, javadoc - 1 + MAX_JAVADOC_LINES - 1));
        lines.add(indentOf(file.get(javadoc - 1)) + " * " + ELIDED + " */");
      } else {
        lines.addAll(file.subList(javadoc - 1, from - 1));
      }
      lines.addAll(file.subList(from - 1, to));
      if (bodyLeftOut) {
        lines.add(indentOf(file.get(from - 1)) + "  " + ELIDED);
      }
      return new Excerpt(fact.id(), fact.evidence().path(), javadoc, to, dedent(lines));
    }
  }

  private static String indentOf(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }

  /** The lines without their common indentation, overlong lines cut, joined with newlines. */
  static String dedent(List<String> lines) {
    int common = Integer.MAX_VALUE;
    for (String line : lines) {
      if (!line.isBlank()) {
        common = Math.min(common, indentOf(line).length());
      }
    }
    StringBuilder out = new StringBuilder();
    for (String line : lines) {
      String text = line.isBlank() ? "" : line.substring(common).stripTrailing();
      if (text.length() > MAX_LINE_CHARS) {
        text = text.substring(0, MAX_LINE_CHARS) + " " + ELIDED;
      }
      if (!out.isEmpty()) {
        out.append('\n');
      }
      out.append(text);
    }
    return out.toString();
  }
}
