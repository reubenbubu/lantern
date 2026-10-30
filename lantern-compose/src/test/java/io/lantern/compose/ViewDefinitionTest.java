package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.core.document.TemplateRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewDefinitionTest {

  private static final TemplateRef TEMPLATE = new TemplateRef("executive-brief", "1");

  @Test
  void roundTripsThroughJson() {
    ViewDefinition view =
        new ViewDefinition(
            "shop.brief",
            "Shop",
            Scope.repo("shop"),
            "executive",
            TEMPLATE,
            List.of(SectionKind.OVERVIEW, SectionKind.CONTEXT));

    String json = ViewDefinitionJson.write(view);

    assertThat(json)
        .isEqualTo(
            "{\"id\":\"shop.brief\",\"title\":\"Shop\",\"scope\":{\"kind\":\"repo\",\"repo\":\"shop\"},"
                + "\"audience\":\"executive\",\"template\":{\"name\":\"executive-brief\",\"version\":\"1\"},"
                + "\"sections\":[\"overview\",\"context\"]}");
    assertThat(ViewDefinitionJson.read(json)).isEqualTo(view);
    ViewDefinition perRepo = ViewDefinition.perRepo("shop", "shop", TEMPLATE);
    assertThat(ViewDefinitionJson.read(ViewDefinitionJson.write(perRepo))).isEqualTo(perRepo);
    assertThat(perRepo.sections())
        .containsExactly(
            SectionKind.OVERVIEW,
            SectionKind.START_HERE,
            SectionKind.CONTAINERS,
            SectionKind.DEPLOYMENT,
            SectionKind.DECISIONS,
            SectionKind.CONTEXT,
            SectionKind.COMPONENTS,
            SectionKind.SEQUENCES,
            SectionKind.FINDINGS,
            SectionKind.CATALOG,
            SectionKind.CHANGE_GUIDANCE,
            SectionKind.DEPENDENCIES,
            SectionKind.STRUCTURE,
            SectionKind.GLOSSARY);
    assertThat(perRepo.audience()).isEqualTo("architects and senior engineers");
    ViewDefinition brief = ViewDefinition.executiveBrief("shop.brief", "shop", TEMPLATE);
    assertThat(ViewDefinitionJson.read(ViewDefinitionJson.write(brief))).isEqualTo(brief);
    assertThat(brief.sections())
        .containsExactly(
            SectionKind.OVERVIEW,
            SectionKind.HEADLINES,
            SectionKind.RISKS,
            SectionKind.BLAST_RADIUS,
            SectionKind.TECHNOLOGY_CURRENCY,
            SectionKind.QUALITY_EVIDENCE,
            SectionKind.CONTEXT,
            SectionKind.CONTAINERS,
            SectionKind.DEPLOYMENT,
            SectionKind.DECISIONS,
            SectionKind.GLOSSARY);
    assertThat(brief.audience()).isEqualTo("leadership");
    ViewDefinition system = ViewDefinition.c4System("system:c4", "System", TEMPLATE);
    assertThat(ViewDefinitionJson.write(system)).contains("\"scope\":{\"kind\":\"system\"}");
    assertThat(ViewDefinitionJson.read(ViewDefinitionJson.write(system))).isEqualTo(system);
    assertThat(system.scope().repo()).isNull();
    // The documentation pages follow the C4 sections (ADR 0086).
    assertThat(system.sections())
        .containsExactly(
            SectionKind.OVERVIEW,
            SectionKind.START_HERE,
            SectionKind.CONTEXT,
            SectionKind.CONTAINERS,
            SectionKind.COMPONENTS,
            SectionKind.SEQUENCES,
            SectionKind.FINDINGS,
            SectionKind.DECISIONS,
            SectionKind.DEPLOYMENT);
    assertThat(system.audience()).isEqualTo("architects and new engineers");
    assertThat(ViewDefinition.c4Report("shop.c4", "shop", TEMPLATE).audience())
        .isEqualTo("architects and new engineers");
  }

  @Test
  void refusesInvalidJson() {
    String valid =
        "{\"id\":\"v\",\"title\":\"T\",\"scope\":{\"kind\":\"repo\",\"repo\":\"shop\"},"
            + "\"template\":{\"name\":\"t\",\"version\":\"1\"},\"sections\":[\"overview\"]}";
    assertThat(ViewDefinitionJson.read(valid).audience()).isNull();
    for (String invalid :
        List.of(
            valid.replace("\"title\":\"T\",", "\"title\":\"T\",\"extra\":1,"),
            valid.replace("\"overview\"", "\"lexicon\""),
            valid.replace("\"repo\",\"repo\"", "\"system\",\"repo\""),
            valid.replace(",\"repo\":\"shop\"", ""),
            valid.replace("\"title\":\"T\",", ""),
            valid.replace("[\"overview\"]", "\"overview\""),
            valid.replace("\"1\"", "1"))) {
      assertThatThrownBy(() -> ViewDefinitionJson.read(invalid))
          .as(invalid)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void refusesInvalidDefinitions() {
    Scope scope = Scope.repo("shop");
    List<SectionKind> sections = List.of(SectionKind.OVERVIEW);
    assertThatThrownBy(() -> new ViewDefinition("bad id", "T", scope, null, TEMPLATE, sections))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ViewDefinition("v", " ", scope, null, TEMPLATE, sections))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ViewDefinition("v", "T", scope, " ", TEMPLATE, sections))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ViewDefinition("v", "T", scope, null, TEMPLATE, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ViewDefinition(
                    "v",
                    "T",
                    scope,
                    null,
                    TEMPLATE,
                    List.of(SectionKind.OVERVIEW, SectionKind.OVERVIEW)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Scope.repo("../etc")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inMemoryStoreKeepsOneDefinitionPerId() {
    InMemoryViewDefinitionStore store = new InMemoryViewDefinitionStore();
    ViewDefinition first = ViewDefinition.perRepo("b", "shop", TEMPLATE);
    ViewDefinition second = ViewDefinition.perRepo("a", "shop", TEMPLATE);
    ViewDefinition replaced =
        new ViewDefinition(
            "b", "New", Scope.repo("base"), null, TEMPLATE, List.of(SectionKind.STRUCTURE));

    store.save(first);
    store.save(second);
    store.save(replaced);

    assertThat(store.find("b")).contains(replaced);
    assertThat(store.find("c")).isEmpty();
    assertThat(store.list()).containsExactly(second, replaced);
  }
}
