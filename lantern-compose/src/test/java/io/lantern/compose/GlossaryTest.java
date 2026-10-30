package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Citation;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GlossaryTest {

  private static final Pattern ACRONYM = QualityBar.ACRONYM;

  private static final String FACT = "a".repeat(64);

  @Test
  void everyMeaningUsesOnlyAcronymsTheTableExplains() {
    Set<String> terms = Glossary.BUNDLED.meanings().keySet();
    List<String> unexplained = new ArrayList<>();
    Glossary.BUNDLED
        .meanings()
        .forEach(
            (term, meaning) -> {
              Matcher m = ACRONYM.matcher(meaning);
              while (m.find()) {
                if (!terms.contains(m.group(1))) {
                  unexplained.add(term + ": " + m.group(1));
                }
              }
            });

    assertThat(unexplained).isEmpty();
  }

  @Test
  void everyTermIsAnAcronymTheQualityBarFinds() {
    for (String term : Glossary.BUNDLED.meanings().keySet()) {
      assertThat(ACRONYM.matcher(term).matches()).as(term).isTrue();
    }
  }

  @Test
  void listsTheAcronymsItExplainsAndNamesTheOthersAsCode() {
    Section section =
        Glossary.BUNDLED.section("glossary", new TreeSet<>(Set.of("JDBC", "HTTP", "XYZ", "QQ")));

    assertThat(section.title()).isEqualTo("Glossary");
    Table table = (Table) section.blocks().get(0);
    assertThat(table.rows())
        .extracting(row -> text(row.cells().get(0)))
        .containsExactly("HTTP", "JDBC");
    assertThat(text(table.rows().get(1).cells().get(1))).startsWith("Java Database Connectivity");
    Callout note = (Callout) section.blocks().get(1);
    assertThat(note.variant()).isEqualTo(Callout.Variant.NOTE);
    assertThat(text(note.content()))
        .isEqualTo(
            "Neither the repositories' documentation nor Lantern's glossary says what these stand"
                + " for: QQ and XYZ.");
    assertThat(note.content())
        .filteredOn(i -> ((TextRun) i).style() == TextRun.Style.CODE)
        .hasSize(2);
  }

  @Test
  void explainsTheAcronymsItsMeaningsUse() {
    Section section = Glossary.BUNDLED.section("glossary", new TreeSet<>(Set.of("HTTPS")));

    assertThat(((Table) section.blocks().get(0)).rows())
        .extracting(row -> text(row.cells().get(0)))
        .containsExactly("HTTP", "HTTPS", "TLS");
  }

  @Test
  void saysSoWhenNoAcronymIsUsed() {
    Section section = Glossary.BUNDLED.section("glossary", new TreeSet<>());

    assertThat(section.blocks()).hasSize(1);
    assertThat(text(((Callout) section.blocks().get(0)).content()))
        .isEqualTo("The document uses no acronyms.");
  }

  @Test
  void findsWhatTheDocumentationSaysAnAcronymStandsFor() {
    Map<String, String> texts = new LinkedHashMap<>();
    texts.put("f1", "The web app talks to a **Backend for Frontend (BFF)** that holds the tokens.");
    texts.put("f2", "Keys are published as JWKS (JSON Web Key Set) by the auth service.");
    texts.put("f3", "PKCE stands for Proof Key for Code Exchange, used by public clients.");
    texts.put(
        "f4", "Tokens use the WebSocket (WS) channel. Our ABC (see below) is not spelled out.");
    texts.put("f5", "It runs a backend for frontend (BFF) per client.");
    texts.put("f6", "Calls go over HTTP (the default) to the gateway (GW).");

    Map<String, Glossary.Definition> defined = Glossary.defined(texts);

    assertThat(defined).containsOnlyKeys("BFF", "JWKS", "PKCE", "WS");
    assertThat(defined.get("BFF"))
        .isEqualTo(new Glossary.Definition("Backend for Frontend", List.of("f1", "f5"), null));
    assertThat(defined.get("JWKS").meaning()).isEqualTo("JSON Web Key Set");
    assertThat(defined.get("PKCE").meaning()).isEqualTo("Proof Key for Code Exchange");
    assertThat(defined.get("WS").meaning()).isEqualTo("WebSocket");
  }

  @Test
  void prefersTheDocumentationAndCitesIt() {
    Section section =
        Glossary.BUNDLED.section(
            "glossary",
            new TreeSet<>(Set.of("JWKS", "BFF")),
            Map.of(
                "BFF",
                new Glossary.Definition("Backend for Frontend", List.of(FACT), "README.md")));

    Table table = (Table) section.blocks().get(0);
    assertThat(table.rows())
        .extracting(row -> text(row.cells().get(0)))
        .containsExactly("BFF", "JSON", "JWKS", "JWT");
    assertThat(table.rows().get(0).cells().get(1))
        .containsExactly(TextRun.of("Backend for Frontend"), new Citation(List.of(FACT)));
    assertThat(table.rows().get(0).cells().get(2))
        .containsExactly(new TextRun("README.md", TextRun.Style.CODE));
    assertThat(table.rows().get(0).facts()).containsExactly(FACT);
    assertThat(text(table.rows().get(2).cells().get(2))).isEqualTo("Lantern's glossary");
    assertThat(table.rows().get(2).facts()).isEmpty();
  }

  private static String text(List<Inline> content) {
    StringBuilder text = new StringBuilder();
    content.forEach(i -> text.append(i instanceof TextRun t ? t.text() : ""));
    return text.toString();
  }
}
