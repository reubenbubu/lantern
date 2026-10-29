package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.document.Callout;
import io.lantern.core.document.Inline;
import io.lantern.core.document.Section;
import io.lantern.core.document.Table;
import io.lantern.core.document.TextRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GlossaryTest {

  /** The acronyms the Quality bar looks for, as {@code QualityBar} matches them. */
  private static final Pattern ACRONYM =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}_-])(\\p{Lu}[\\p{Lu}\\p{N}]*\\p{Lu})s?(?![\\p{L}\\p{N}_-])");

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
    assertThat(text(note.content())).isEqualTo("Lantern's glossary has no meaning for QQ and XYZ.");
    assertThat(note.content())
        .filteredOn(i -> ((TextRun) i).style() == TextRun.Style.CODE)
        .hasSize(2);
  }

  @Test
  void saysSoWhenNoAcronymIsUsed() {
    Section section = Glossary.BUNDLED.section("glossary", new TreeSet<>());

    assertThat(section.blocks()).hasSize(1);
    assertThat(text(((Callout) section.blocks().get(0)).content()))
        .isEqualTo("The document uses no acronyms.");
  }

  private static String text(List<Inline> content) {
    StringBuilder text = new StringBuilder();
    content.forEach(i -> text.append(((TextRun) i).text()));
    return text.toString();
  }
}
