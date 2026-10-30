package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
import io.lantern.core.document.Citation;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains the acronyms a document uses, so no LLM call is needed: from what the repositories'
 * documentation says each stands for, citing it (#229), else from the table bundled with Lantern
 * ({@code glossary.csv}, ADR 0063). A document's glossary section lists the acronyms it uses that
 * either explains, and names those neither does as code, so the Quality bar still reports them.
 */
final class Glossary {

  /** The table bundled with Lantern. */
  static final Glossary BUNDLED = load("glossary.csv");

  private final SortedMap<String, String> meanings;

  Glossary(SortedMap<String, String> meanings) {
    this.meanings = Collections.unmodifiableSortedMap(new TreeMap<>(meanings));
  }

  /** What the acronym stands for, if the table explains it. */
  Optional<String> meaning(String acronym) {
    return Optional.ofNullable(meanings.get(acronym));
  }

  /** Every acronym the table explains, with its meaning. */
  SortedMap<String, String> meanings() {
    return meanings;
  }

  /**
   * The glossary section: a table of the acronyms used that the table explains; a note naming, as
   * code, those it does not; or, with no acronym used, a note saying so. Definitions are not claims
   * about the system, so they cite no facts.
   *
   * @param id the section's ID
   * @param used the acronyms the rest of the document uses
   */
  Section section(String id, SortedSet<String> used) {
    return section(id, used, Map.of());
  }

  /**
   * The glossary section as {@link #section(String, SortedSet)}, preferring what the repositories'
   * documentation says an acronym stands for, which the row cites.
   *
   * @param documented what the documentation defines, by acronym, with the IDs of the facts that
   *     say so
   */
  Section section(String id, SortedSet<String> used, Map<String, Definition> documented) {
    List<Block> blocks = new ArrayList<>();
    List<Table.Row> rows = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String acronym : withThoseOfTheirMeanings(used, documented)) {
      Definition definition = documented.get(acronym);
      Optional<String> meaning = meaning(acronym);
      if (definition != null) {
        rows.add(
            new Table.Row(
                null,
                List.of(
                    List.of(TextRun.of(acronym)),
                    List.of(TextRun.of(definition.meaning()), new Citation(definition.facts())),
                    definition.source() == null
                        ? List.of()
                        : List.of(new TextRun(definition.source(), TextRun.Style.CODE))),
                definition.facts()));
      } else if (meaning.isPresent()) {
        rows.add(
            new Table.Row(
                null,
                List.of(
                    List.of(TextRun.of(acronym)),
                    List.of(TextRun.of(meaning.get())),
                    List.of(TextRun.of(BUNDLED_SOURCE))),
                List.of()));
      } else {
        unknown.add(acronym);
      }
    }
    if (!rows.isEmpty()) {
      blocks.add(
          new Table(
              id + ".terms",
              null,
              List.of(
                  new Table.Column("Term"),
                  new Table.Column("Meaning"),
                  new Table.Column("Source")),
              rows,
              List.of()));
    }
    if (!unknown.isEmpty()) {
      List<Inline> content = new ArrayList<>();
      content.add(
          TextRun.of(
              "Neither the repositories' documentation nor Lantern's glossary says what these"
                  + " stand for: "));
      for (int i = 0; i < unknown.size(); i++) {
        if (i > 0) {
          content.add(TextRun.of(i == unknown.size() - 1 ? " and " : ", "));
        }
        content.add(new TextRun(unknown.get(i), TextRun.Style.CODE));
      }
      content.add(TextRun.of("."));
      blocks.add(note(id + ".unknown", content));
    }
    if (used.isEmpty()) {
      blocks.add(note(id + ".none", List.of(TextRun.of("The document uses no acronyms."))));
    }
    return new Section(
        id,
        SectionKind.GLOSSARY.title(),
        SectionKind.GLOSSARY.levels(),
        null,
        List.of(),
        blocks,
        List.of());
  }

  /**
   * The acronyms with those their meanings use, and those of their meanings in turn, so a row such
   * as HTTPS's ("HTTP encrypted with TLS") brings the rows of HTTP and TLS.
   */
  private SortedSet<String> withThoseOfTheirMeanings(
      SortedSet<String> used, Map<String, Definition> documented) {
    SortedSet<String> all = new TreeSet<>(used);
    Deque<String> todo = new ArrayDeque<>(used);
    while (!todo.isEmpty()) {
      String acronym = todo.pop();
      Definition definition = documented.get(acronym);
      String meaning = definition != null ? definition.meaning() : meanings.get(acronym);
      if (meaning != null) {
        Matcher m = QualityBar.ACRONYM.matcher(meaning);
        while (m.find()) {
          if (all.add(m.group(1))) {
            todo.push(m.group(1));
          }
        }
      }
    }
    return all;
  }

  /**
   * What an acronym stands for, as the repositories' documentation writes it.
   *
   * @param meaning the words the acronym stands for
   * @param facts the IDs of the facts whose text defines it
   * @param source where the reader finds it, such as {@code README.md}, or {@code null}
   */
  record Definition(String meaning, List<String> facts, String source) {
    Definition {
      Objects.requireNonNull(meaning, "meaning");
      facts = List.copyOf(facts);
    }
  }

  /** The source of a meaning from Lantern's table. */
  static final String BUNDLED_SOURCE = "Lantern's glossary";

  /** Words an expansion may hold without their initial being in the acronym. */
  private static final Set<String> MINOR_WORDS =
      Set.of(
          "a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "with");

  /** The most facts a definition cites. */
  private static final int MAX_CITED = 3;

  /** The longest acronym the documentation is searched for. */
  private static final int MAX_LETTERS = 10;

  /** The most words an expansion has for each letter of its acronym, minor words included. */
  private static final int WORDS_PER_LETTER = 2;

  /** {@code Words Spelled Out (WSO)}: the acronym in parentheses after its expansion. */
  private static final Pattern AFTER =
      Pattern.compile("\\(\\s*(\\p{Lu}[\\p{Lu}\\p{N}]*\\p{Lu})s?\\s*\\)");

  /** {@code WSO (Words Spelled Out)}: the expansion in parentheses after its acronym. */
  private static final Pattern BEFORE =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_-])(\\p{Lu}[\\p{Lu}\\p{N}]*\\p{Lu})s?\\s*\\(([^()]{3,120})\\)");

  /** {@code WSO stands for Words Spelled Out}. */
  private static final Pattern STANDS_FOR =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_-])(\\p{Lu}[\\p{Lu}\\p{N}]*\\p{Lu})s?\\s+(?:stands for|is short"
              + " for|means)\\s+([^.;:()\\n]{3,120})");

  /** Where an expansion before its acronym starts at the latest. */
  private static final Pattern CLAUSE_END = Pattern.compile("[.;:!?()\\[\\]\\n,\"]");

  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");

  /**
   * The acronyms the texts define, by the words each stands for, the first text in order that
   * defines an acronym winning: a definition is an acronym written next to words whose initials
   * spell it, such as {@code Backend for Frontend (BFF)}, {@code JWKS (JSON Web Key Set)} or {@code
   * BFF stands for Backend for Frontend}. Nothing is guessed: an acronym no text spells out has no
   * definition.
   *
   * @param texts the facts' texts, by fact ID, in the order to prefer them
   */
  static SortedMap<String, Definition> defined(Map<String, String> texts) {
    SortedMap<String, Definition> result = new TreeMap<>();
    texts.forEach(
        (factId, text) -> {
          String plain = text.replaceAll("[*_`]", "");
          Matcher after = AFTER.matcher(plain);
          while (after.find()) {
            String acronym = after.group(1);
            String before = plain.substring(0, after.start());
            Matcher end = CLAUSE_END.matcher(before);
            int from = 0;
            while (end.find()) {
              from = end.end();
            }
            String clause = before.substring(from);
            List<int[]> words = words(clause);
            String meaning = null;
            int fewest = Math.max(0, words.size() - WORDS_PER_LETTER * letters(acronym).length());
            for (int start = words.size() - 1; start >= fewest && meaning == null; start--) {
              if (spells(acronym, clause, words.subList(start, words.size()))) {
                meaning = clause.substring(words.get(start)[0], words.getLast()[1]);
              }
            }
            put(result, acronym, meaning, factId);
          }
          for (Pattern pattern : List.of(BEFORE, STANDS_FOR)) {
            Matcher m = pattern.matcher(plain);
            while (m.find()) {
              String acronym = m.group(1);
              String rest = m.group(2);
              List<int[]> words = words(rest);
              String meaning = null;
              int most = Math.min(words.size(), WORDS_PER_LETTER * letters(acronym).length());
              for (int count = 1; count <= most && meaning == null; count++) {
                List<int[]> some = words.subList(0, count);
                // In parentheses, the words must be all there is, so "(see below)" defines nothing.
                if ((pattern == STANDS_FOR || count == words.size())
                    && spells(acronym, rest, some)) {
                  meaning = rest.substring(some.getFirst()[0], some.getLast()[1]);
                }
              }
              put(result, acronym, meaning, factId);
            }
          }
        });
    return result;
  }

  private static void put(
      SortedMap<String, Definition> result, String acronym, String meaning, String factId) {
    // No initial spells a digit, so W3C is left to Lantern's table rather than half-matched.
    if (meaning == null || acronym.chars().anyMatch(Character::isDigit)) {
      return;
    }
    Definition known = result.get(acronym);
    if (known == null) {
      result.put(acronym, new Definition(meaning.strip(), List.of(factId), null));
    } else if (known.meaning().equalsIgnoreCase(meaning.strip())
        && !known.facts().contains(factId)
        && known.facts().size() < MAX_CITED) {
      List<String> facts = new ArrayList<>(known.facts());
      facts.add(factId);
      result.put(acronym, new Definition(known.meaning(), facts, null));
    }
  }

  /** The start and end of each word of the text. */
  private static List<int[]> words(String text) {
    List<int[]> words = new ArrayList<>();
    Matcher m = WORD.matcher(text);
    while (m.find()) {
      words.add(new int[] {m.start(), m.end()});
    }
    return words;
  }

  /** The acronym's letters, lower case; its digits are left out, as no initial spells them. */
  private static String letters(String acronym) {
    return acronym.replaceAll("\\P{L}", "").toLowerCase(Locale.ROOT);
  }

  /**
   * Whether the words, in order, spell the acronym: each word gives its initial, or the initials of
   * its parts when written in camel case (WebSocket: W, S), and a minor word such as "for" may give
   * none, unless it is the first or the last.
   */
  private static boolean spells(String acronym, String text, List<int[]> words) {
    if (words.isEmpty()) {
      return false;
    }
    List<String> list = new ArrayList<>();
    for (int[] w : words) {
      list.add(text.substring(w[0], w[1]));
    }
    String target = letters(acronym);
    return !target.isEmpty()
        && target.length() <= MAX_LETTERS
        && spells(list, 0, target, 0, new Boolean[list.size() + 1][target.length() + 1]);
  }

  private static boolean spells(
      List<String> words, int i, String target, int j, Boolean[][] known) {
    if (i == words.size()) {
      return j == target.length();
    }
    if (known[i][j] != null) {
      return known[i][j];
    }
    boolean result = false;
    String word = words.get(i);
    boolean minor = MINOR_WORDS.contains(word.toLowerCase(Locale.ROOT));
    if (minor && i > 0 && i < words.size() - 1) {
      result = spells(words, i + 1, target, j, known);
    }
    String initials = initials(word);
    for (int k = 1; !result && k <= initials.length() && j + k <= target.length(); k++) {
      if (!target.startsWith(initials.substring(0, k), j)) {
        break;
      }
      result = spells(words, i + 1, target, j + k, known);
    }
    known[i][j] = result;
    return result;
  }

  /** A word's initial, then the capitals that start its later parts: JavaScript gives "js". */
  private static String initials(String word) {
    StringBuilder initials = new StringBuilder();
    initials.append(Character.toLowerCase(word.charAt(0)));
    for (int i = 1; i < word.length(); i++) {
      if (Character.isUpperCase(word.charAt(i)) && Character.isLowerCase(word.charAt(i - 1))) {
        initials.append(Character.toLowerCase(word.charAt(i)));
      }
    }
    return initials.toString();
  }

  private static Callout note(String id, List<Inline> content) {
    return new Callout(id, Callout.Variant.NOTE, null, null, content, List.of());
  }

  /**
   * Reads a table of lines {@code ACRONYM,meaning}, split at the first comma; blank lines and lines
   * starting with {@code #} are skipped.
   */
  static Glossary load(String resource) {
    InputStream in = Glossary.class.getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException("Missing resource " + resource);
    }
    SortedMap<String, String> meanings = new TreeMap<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      int number = 0;
      while ((line = reader.readLine()) != null) {
        number++;
        String text = line.strip();
        if (text.isEmpty() || text.startsWith("#")) {
          continue;
        }
        int comma = text.indexOf(',');
        if (comma < 1 || comma == text.length() - 1) {
          throw new IllegalStateException(resource + " line " + number + ": not ACRONYM,meaning");
        }
        String acronym = text.substring(0, comma).strip();
        if (meanings.put(acronym, text.substring(comma + 1).strip()) != null) {
          throw new IllegalStateException(resource + " line " + number + ": " + acronym + " twice");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
    return new Glossary(meanings);
  }
}
