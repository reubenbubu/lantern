package io.lantern.compose;

import io.lantern.core.document.Block;
import io.lantern.core.document.Callout;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * Explains the acronyms a document uses, from the table bundled with Lantern ({@code glossary.csv},
 * ADR 0063), so no LLM call is needed. A document's glossary section lists the acronyms it uses
 * that the table explains, and names those it does not as code, so the Quality bar still reports
 * them.
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
    List<Block> blocks = new ArrayList<>();
    List<Table.Row> rows = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String acronym : used) {
      Optional<String> meaning = meaning(acronym);
      if (meaning.isPresent()) {
        rows.add(
            new Table.Row(
                null,
                List.of(List.of(TextRun.of(acronym)), List.of(TextRun.of(meaning.get()))),
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
              List.of(new Table.Column("Term"), new Table.Column("Meaning")),
              rows,
              List.of()));
    }
    if (!unknown.isEmpty()) {
      List<Inline> content = new ArrayList<>();
      content.add(TextRun.of("Lantern's glossary has no meaning for "));
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
