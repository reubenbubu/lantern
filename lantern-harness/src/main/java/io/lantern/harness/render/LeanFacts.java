package io.lantern.harness.render;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.types.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fact lines in a lean form (#873), for a prompt that shows many facts of a few files: the same
 * facts as {@link FactLines} writes them, each still on its own line with its reference, but
 * nothing repeated.
 *
 * <ul>
 *   <li>The facts of one file are listed under one {@code @ repo:path} heading.
 *   <li>The facts about one subject are listed under it, indented, without repeating it.
 *   <li>What a subject contains is listed under it, on the line of the {@code CONTAINS} fact, with
 *       its own facts indented under that: {@code [F8] method authorize(AuthorizationRequest)} for
 *       a member of a type, {@code [F2] CONTAINS module:g:orders} for any other part.
 *   <li>A class is written by its simple name, and a {@code names} list gives each one's full name
 *       once ({@link Names}); classes of {@code java.} and {@code javax.} packages are written by
 *       their simple names alone. Two classes of one simple name keep their full names.
 *   <li>The {@code type:}, {@code method:} and {@code field:} prefixes are left out where the line
 *       or the indentation already says what something is.
 * </ul>
 *
 * <p>The text depends only on the facts and the order they are given in, as {@link FactLines} does:
 * no fact IDs, commits or line numbers.
 */
public final class LeanFacts {

  /** A fact with the reference the prompt cites it by, such as {@code F12}. */
  public record Labeled(String label, Fact fact) {}

  /** A qualified Java class name: lower-case package segments, then the class. */
  private static final Pattern CLASS =
      Pattern.compile("(?<![\\w.$])((?:[a-z_][\\w]*\\.)+)([A-Z][\\w$]*)");

  /** The entity kinds whose prefix the lean form leaves out. */
  private static final List<String> KINDS = List.of("type:", "method:", "field:");

  /** The entity kinds that are members of a type, listed under it even without facts of theirs. */
  private static final List<String> MEMBERS = List.of("method:", "field:");

  private LeanFacts() {}

  /**
   * The short names of the classes some facts name, and which of them a rendering used, so one
   * {@code names} list serves every block of facts in a prompt.
   */
  public static final class Names {

    private final Set<String> ambiguous = new HashSet<>();
    private final Map<String, String> used = new LinkedHashMap<>();

    /** The names of every fact the prompt shows, so a simple name two classes share is known. */
    public Names(Collection<Fact> facts) {
      Map<String, Set<String>> byName = new HashMap<>();
      for (Fact fact : facts) {
        for (String text : List.of(fact.subject().toString(), entity(fact), attributesOf(fact))) {
          Matcher m = CLASS.matcher(text);
          while (m.find()) {
            if (!library(m.group(1))) {
              byName.computeIfAbsent(m.group(2), k -> new TreeSet<>()).add(m.group(0));
            }
          }
        }
      }
      byName.forEach(
          (name, full) -> {
            if (full.size() > 1) {
              ambiguous.add(name);
            }
          });
    }

    /** The text with each class written by its simple name, where that is unambiguous. */
    String shorten(String text) {
      Matcher m = CLASS.matcher(text);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String name = m.group(2);
        String replacement;
        if (library(m.group(1))) {
          replacement = name;
        } else if (ambiguous.contains(name)) {
          replacement = m.group(0);
        } else {
          used.putIfAbsent(name, m.group(0));
          replacement = name;
        }
        m.appendReplacement(out, Matcher.quoteReplacement(replacement));
      }
      m.appendTail(out);
      return out.toString();
    }

    /**
     * The {@code names} list: one line per simple name the renderings used, {@code Name =
     * full.Name}, in the order they were first used; empty when they used none.
     */
    public String legend() {
      StringBuilder out = new StringBuilder();
      used.forEach((name, full) -> out.append(name).append(" = ").append(full).append('\n'));
      return out.toString();
    }

    private static boolean library(String pkg) {
      return pkg.startsWith("java.") || pkg.startsWith("javax.");
    }

    private static String entity(Fact fact) {
      return fact.object().kind() == FactObject.Kind.ENTITY ? fact.object().value() : "";
    }

    private static String attributesOf(Fact fact) {
      return FactLines.attributes(fact);
    }
  }

  /**
   * The facts in the lean form, one line each with its reference, grouped by file in the order the
   * files first occur, and in each file by subject in the order the subjects first occur.
   *
   * @param names the short names, shared by the prompt's blocks; this rendering adds the names it
   *     uses to its {@code names} list
   */
  public static String render(List<Labeled> facts, Names names) {
    Map<String, List<Labeled>> byPlace = new LinkedHashMap<>();
    for (Labeled labeled : facts) {
      byPlace.computeIfAbsent(FactLines.place(labeled.fact()), k -> new ArrayList<>()).add(labeled);
    }
    StringBuilder out = new StringBuilder();
    byPlace.forEach(
        (place, group) -> {
          out.append("@ ").append(FactLines.oneLine(place)).append('\n');
          new File(group, names, out).render();
        });
    return out.toString();
  }

  /** The facts of one file, as a tree of subjects. */
  private static final class File {

    private final Map<String, List<Labeled>> bySubject = new LinkedHashMap<>();
    private final Set<String> contained = new HashSet<>();
    private final Set<String> written = new HashSet<>();
    private final Names names;
    private final StringBuilder out;

    File(List<Labeled> facts, Names names, StringBuilder out) {
      this.names = names;
      this.out = out;
      for (Labeled labeled : facts) {
        bySubject
            .computeIfAbsent(labeled.fact().subject().toString(), k -> new ArrayList<>())
            .add(labeled);
      }
      for (Labeled labeled : facts) {
        if (nests(labeled.fact())) {
          contained.add(labeled.fact().object().value());
        }
      }
    }

    void render() {
      for (String subject : bySubject.keySet()) {
        if (!contained.contains(subject)) {
          subject(subject, 0, null, null);
        }
      }
      // Subjects contained only in a cycle, which no root reaches.
      for (String subject : bySubject.keySet()) {
        if (!written.contains(subject)) {
          subject(subject, 0, null, null);
        }
      }
    }

    /** Whether the fact's object is listed under its subject: a part contained in this file. */
    private boolean nests(Fact fact) {
      if (!Types.CONTAINS.equals(fact.predicate())
          || fact.object().kind() != FactObject.Kind.ENTITY) {
        return false;
      }
      String object = fact.object().value();
      return !object.equals(fact.subject().toString())
          && (bySubject.containsKey(object) || MEMBERS.stream().anyMatch(object::startsWith));
    }

    /**
     * A subject's line, then its facts indented under it.
     *
     * @param label the reference of the {@code CONTAINS} fact it is listed by, or {@code null}
     * @param parent what contains it, or {@code null} for a subject at the top
     */
    private void subject(String subject, int depth, String label, String parent) {
      written.add(subject);
      String head;
      if (parent == null) {
        head = names.shorten(withoutKind(subject));
      } else {
        String kind = kind(subject);
        // A member reads by its kind, "method price()"; any other part by the fact's predicate.
        head =
            (kind == null ? Types.CONTAINS : kind) + " " + names.shorten(member(parent, subject));
      }
      line(depth, label, head);
      for (Labeled labeled : bySubject.getOrDefault(subject, List.of())) {
        Fact fact = labeled.fact();
        String object = fact.object().value();
        if (nests(fact) && !written.contains(object)) {
          subject(object, depth + 1, labeled.label(), subject);
        } else {
          line(depth + 1, labeled.label(), fact(fact));
        }
      }
    }

    /** A fact's line under its subject: predicate, object and attributes. */
    private String fact(Fact fact) {
      String object =
          fact.object().kind() == FactObject.Kind.ENTITY
              ? names.shorten(withoutKind(fact.object().value()))
              : FactLines.object(fact, UnaryOperator.identity());
      String attributes = FactLines.attributes(fact);
      return fact.predicate()
          + " "
          + object
          + (attributes.isEmpty() ? "" : " " + names.shorten(attributes));
    }

    private void line(int depth, String label, String text) {
      out.append("  ".repeat(depth));
      if (label != null) {
        out.append('[').append(label).append("] ");
      }
      out.append(FactLines.oneLine(Refs.clean(text))).append('\n');
    }
  }

  /** The entity's kind when the lean form leaves its prefix out, such as {@code method}. */
  private static String kind(String entity) {
    return KINDS.stream()
        .filter(entity::startsWith)
        .findFirst()
        .map(k -> k.substring(0, k.length() - 1))
        .orElse(null);
  }

  /** The entity without a {@code type:}, {@code method:} or {@code field:} prefix. */
  private static String withoutKind(String entity) {
    String kind = kind(entity);
    return kind == null ? entity : entity.substring(kind.length() + 1);
  }

  /** A member as listed under its type: without its type, {@code authorize(Request)}. */
  private static String member(String parent, String entity) {
    String own = withoutKind(entity);
    String prefix = withoutKind(parent) + "#";
    return own.startsWith(prefix) ? own.substring(prefix.length()) : own;
  }
}
