package io.lantern.extract.spring;

import io.lantern.extract.java.ModuleStructure.Annotation;
import io.lantern.extract.java.ModuleStructure.TypeDecl;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A small reader of AspectJ pointcut expressions, enough to say which methods of the analyzed code
 * an advice selects (docs/adr/0079-aspects-as-components.md). It reads the designators {@code
 * @annotation}, {@code @within}, {@code @target}, {@code within} and {@code execution}, named
 * pointcuts, and {@code &&}, {@code ||}, {@code !} and parentheses. Any other designator ({@code
 * args}, {@code this}, {@code bean} and so on) is unknown: it neither selects nor excludes, unless
 * nothing else in the expression is known, which selects nothing.
 */
final class AspectPointcuts {

  private AspectPointcuts() {}

  /** What a designator is asked about: a method and the type declaring it. */
  record Candidate(
      TypeDecl type, List<TypeDecl> lineage, List<Annotation> annotations, String methodName) {}

  /** Resolves names a pointcut mentions. */
  interface Names {

    /** The binary name an annotation designator's argument stands for, or null. */
    String annotationType(String argument);

    /** The expression of a named pointcut, or null. */
    String pointcut(String name);
  }

  /**
   * Evaluates an expression for a candidate.
   *
   * @return true if it selects the candidate, false if it does not or cannot be told
   */
  static boolean selects(String expression, Candidate candidate, Names names) {
    return Boolean.TRUE.equals(evaluate(expression, candidate, names, 0));
  }

  /** The annotation types an expression selects by (its {@code @annotation} arguments). */
  static List<String> annotationTypes(String expression, Names names) {
    List<String> result = new ArrayList<>();
    collect(expression, names, result, 0);
    return result;
  }

  private static void collect(String expression, Names names, List<String> result, int depth) {
    if (depth > 5) {
      return;
    }
    for (Term term : terms(expression)) {
      if (term.argument != null
          && (term.designator.equals("@annotation")
              || term.designator.equals("@within")
              || term.designator.equals("@target"))) {
        String type = names.annotationType(term.argument);
        if (type != null && !result.contains(type)) {
          result.add(type);
        }
      } else if (term.argument == null || term.argument.isBlank()) {
        String named = names.pointcut(term.designator);
        if (named != null) {
          collect(named, names, result, depth + 1);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Parsing

  private record Term(String designator, String argument) {}

  private static List<Term> terms(String expression) {
    List<Term> result = new ArrayList<>();
    try {
      new Parser(expression).collect(result);
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      // A malformed expression gives the terms read before the error.
    }
    return result;
  }

  private static Boolean evaluate(String expression, Candidate c, Names names, int depth) {
    if (depth > 5) {
      return null;
    }
    try {
      return new Parser(expression).or(term -> term(term, c, names, depth));
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      return null;
    }
  }

  private static Boolean term(Term term, Candidate c, Names names, int depth) {
    String argument = term.argument == null ? "" : term.argument.strip();
    switch (term.designator) {
      case "@annotation" -> {
        String type = names.annotationType(argument);
        return type == null ? null : hasAnnotation(c.annotations, type);
      }
      case "@within", "@target" -> {
        String type = names.annotationType(argument);
        return type == null ? null : hasAnnotation(c.type.annotations(), type);
      }
      case "within" -> {
        return typeMatches(argument, c);
      }
      case "execution" -> {
        return execution(argument, c);
      }
      default -> {
        if (term.argument == null || argument.isEmpty()) {
          String named = names.pointcut(term.designator);
          return named == null ? null : evaluate(named, c, names, depth + 1);
        }
        return null;
      }
    }
  }

  private static boolean hasAnnotation(List<Annotation> annotations, String type) {
    return annotations.stream().anyMatch(a -> a.type().equals(type));
  }

  private static boolean typeMatches(String pattern, Candidate c) {
    boolean subtypes = pattern.endsWith("+");
    String p = subtypes ? pattern.substring(0, pattern.length() - 1) : pattern;
    Pattern regex = typePattern(p);
    if (!subtypes) {
      return regex.matcher(c.type.binaryName()).matches();
    }
    return c.lineage.stream().anyMatch(t -> regex.matcher(t.binaryName()).matches());
  }

  /** An {@code execution} pattern: the declaring type and the method name; the rest is ignored. */
  private static Boolean execution(String pattern, Candidate c) {
    int paren = pattern.indexOf('(');
    String head = (paren < 0 ? pattern : pattern.substring(0, paren)).strip();
    String[] tokens = head.split("\\s+");
    String target = tokens[tokens.length - 1];
    int dot = target.lastIndexOf('.');
    String declaring;
    String name;
    if (dot < 0) {
      declaring = null;
      name = target;
    } else if (dot > 0 && target.charAt(dot - 1) == '.') {
      declaring = target.substring(0, dot - 1) + "..*";
      name = target.substring(dot + 1);
    } else {
      declaring = target.substring(0, dot);
      name = target.substring(dot + 1);
    }
    if (!namePattern(name).matcher(c.methodName).matches()) {
      return false;
    }
    return declaring == null || typeMatches(declaring, c);
  }

  private static Pattern namePattern(String name) {
    return Pattern.compile(globToRegex(name, false));
  }

  private static Pattern typePattern(String pattern) {
    return Pattern.compile(globToRegex(pattern, true));
  }

  /** {@code *} is any run without a dot, {@code ..} any packages. */
  private static String globToRegex(String glob, boolean dots) {
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char ch = glob.charAt(i);
      if (ch == '.' && dots && i + 1 < glob.length() && glob.charAt(i + 1) == '.') {
        regex.append("\\.(?:.*\\.)?");
        i++;
      } else if (ch == '*') {
        regex.append(dots ? "[^.]*" : ".*");
      } else {
        regex.append(Pattern.quote(String.valueOf(ch)));
      }
    }
    return regex.toString();
  }

  /** Recursive descent over {@code ||}, {@code &&}, {@code !}, parentheses and terms. */
  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    void collect(List<Term> out) {
      while (pos < text.length()) {
        skipSpaces();
        if (pos >= text.length()) {
          return;
        }
        char ch = text.charAt(pos);
        if (ch == '&' || ch == '|' || ch == '!' || ch == '(' || ch == ')') {
          pos++;
        } else {
          out.add(term());
        }
      }
    }

    Boolean or(Function<Term, Boolean> eval) {
      Boolean result = and(eval);
      boolean unknown = result == null;
      boolean any = Boolean.TRUE.equals(result);
      while (accept("||")) {
        Boolean next = and(eval);
        unknown |= next == null;
        any |= Boolean.TRUE.equals(next);
      }
      skipSpaces();
      if (pos != text.length() && text.charAt(pos) != ')') {
        throw new IllegalArgumentException("Unexpected text in pointcut: " + text);
      }
      return any ? Boolean.TRUE : unknown ? null : Boolean.FALSE;
    }

    private Boolean and(Function<Term, Boolean> eval) {
      Boolean result = unary(eval);
      boolean unknown = result == null;
      boolean all = !Boolean.FALSE.equals(result);
      boolean known = result != null;
      while (accept("&&")) {
        Boolean next = unary(eval);
        unknown |= next == null;
        all &= !Boolean.FALSE.equals(next);
        known |= next != null;
      }
      if (!all) {
        return Boolean.FALSE;
      }
      return known ? Boolean.TRUE : null;
    }

    private Boolean unary(Function<Term, Boolean> eval) {
      skipSpaces();
      if (accept("!")) {
        Boolean inner = unary(eval);
        return inner == null ? null : !inner;
      }
      if (accept("(")) {
        Boolean inner = or(eval);
        if (!accept(")")) {
          throw new IllegalArgumentException("Unbalanced parentheses in pointcut: " + text);
        }
        return inner;
      }
      return eval.apply(term());
    }

    private Term term() {
      skipSpaces();
      int start = pos;
      while (pos < text.length()
          && (Character.isJavaIdentifierPart(text.charAt(pos))
              || text.charAt(pos) == '@'
              || text.charAt(pos) == '.')) {
        pos++;
      }
      if (pos == start) {
        throw new IllegalArgumentException("Unexpected text in pointcut: " + text);
      }
      String designator = text.substring(start, pos);
      if (pos < text.length() && text.charAt(pos) == '(') {
        int depth = 0;
        int open = pos;
        do {
          char ch = text.charAt(pos);
          if (ch == '(') {
            depth++;
          } else if (ch == ')') {
            depth--;
          }
          pos++;
        } while (depth > 0 && pos < text.length());
        if (depth != 0) {
          throw new IllegalArgumentException("Unbalanced parentheses in pointcut: " + text);
        }
        return new Term(designator, text.substring(open + 1, pos - 1));
      }
      return new Term(designator, null);
    }

    private boolean accept(String token) {
      skipSpaces();
      if (text.startsWith(token, pos)) {
        pos += token.length();
        return true;
      }
      return false;
    }

    private void skipSpaces() {
      while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
        pos++;
      }
    }
  }
}
