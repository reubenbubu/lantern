package io.lantern.harness.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An item's references and how a request numbers them (knowledge-build epic, section 11.0). */
class RefsTest {

  @Test
  void anInputAddedTwiceKeepsItsFirstReference() {
    Refs refs = new Refs();

    String first = refs.add('F', "claim-1", "fact one", "a line");
    String again = refs.add('F', "claim-1", "fact one", "a line");
    String second = refs.add('F', "claim-2", "fact two", "another line");
    String code = refs.add('E', "claim-1", "code", "the code");

    assertThat(again).isEqualTo(first);
    assertThat(Refs.plain(first + " " + second + " " + code)).isEqualTo("F1 F2 E1");
    assertThat(refs.count('F')).isEqualTo(2);
    assertThat(refs.thing("F2")).contains("fact two");
    assertThat(refs.thing("F3")).isEmpty();
    assertThat(refs.lines()).containsEntry("E1", "the code").hasSize(3);
  }

  @Test
  void aRequestNumbersCitableReferencesAcrossItsItemsAndKeepsTheKeys() {
    Refs one = new Refs();
    String a = one.add('F', "x", "x", "line x");
    String s = one.add('S', "sx", "sx", "summary x");
    Refs two = new Refs();
    String b = two.add('F', "y", "y", "line y");
    String c = two.add('F', "x", "x", "line x");

    Refs.Resolved resolved =
        Refs.resolve(
            List.of(
                "[" + Refs.token("C1") + "] " + a + " " + s,
                "[" + Refs.token("C1") + "] " + b + " " + c),
            List.of(one, two));

    assertThat(resolved.texts()).containsExactly("[C1] F1 S1", "[C1] F2 F3");
    assertThat(resolved.toLocal().get(1)).isEqualTo(Map.of("F2", "F1", "F3", "F2"));
    assertThat(resolved.references())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("F1", "line x", "S1", "summary x", "F2", "line y", "F3", "line x"));
  }

  @Test
  void anItemRendersTheSameWhicheverItemsShareItsRequest() {
    Refs alone = new Refs();
    String rendering = "[" + alone.add('F', "x", "x", "line") + "]";

    assertThat(Refs.plain(rendering)).isEqualTo("[F1]");
    assertThat(Refs.resolve(List.of(rendering), List.of(alone)).texts()).containsExactly("[F1]");
  }

  @Test
  void cleaningRemovesTheCharactersOfTokens() {
    assertThat(Refs.clean("a" + Refs.token("F1") + "b")).isEqualTo("aF1b");
    assertThat(Refs.clean("plain")).isEqualTo("plain");
  }
}
