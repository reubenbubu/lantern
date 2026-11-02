package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.harness.checks.WordingJudge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The judge-labels command's reading and counting (ADR 0113). */
class JudgeLabelsCommandTest {

  @TempDir Path folder;

  @Test
  void readsOnlyLabelledTextsOfTheSplit() throws IOException {
    write();
    List<JudgeLabelsCommand.Labelled> tune =
        JudgeLabelsCommand.labelled(folder, WordingJudge.Dimension.ROLE, "tune");
    assertThat(tune).extracting(l -> l.text().key()).containsExactly("a", "b");
    assertThat(JudgeLabelsCommand.labelled(folder, WordingJudge.Dimension.ROLE, "all"))
        .extracting(l -> l.text().key())
        .containsExactly("a", "b", "c");
  }

  @Test
  void countsAgreementAndListsWhatTheJudgeGotWrong() throws IOException {
    write();
    List<JudgeLabelsCommand.Labelled> all =
        JudgeLabelsCommand.labelled(folder, WordingJudge.Dimension.ROLE, "all");
    JudgeLabelsCommand.Agreement agreement =
        JudgeLabelsCommand.agreement(
            all,
            Map.of(
                "a", new WordingJudge.Verdict("pass", "It says what it does."),
                "b", new WordingJudge.Verdict("pass", "Fine.")));

    assertThat(agreement.passLabels()).isEqualTo(1);
    assertThat(agreement.passed()).isEqualTo(1);
    assertThat(agreement.failLabels()).isEqualTo(2);
    assertThat(agreement.failed()).isZero();
    assertThat(agreement.tpr()).isEqualTo(1.0);
    assertThat(agreement.tnr()).isZero();
    assertThat(agreement.wrong())
        .containsExactly(
            "label fail, judge pass: \"Signs in.\" (responsibility of a person)\n      Fine.",
            "label fail, judge no verdict: \"Holds the role.\" (responsibility of a person)");
  }

  @Test
  void theCommandIsRequestedByItsName() {
    assertThat(JudgeLabelsCommand.requested("--split=all", "judge-labels")).isTrue();
    assertThat(JudgeLabelsCommand.requested("analyze", "judge-labels")).isFalse();
  }

  private void write() throws IOException {
    Files.writeString(
        folder.resolve("role.json"),
        """
        {"dimension": "role", "items": [
          {"id": "a", "kind": "responsibility of a database", "about": "db", \
        "text": "Holds orders.", "split": "tune", "label": "pass"},
          {"id": "b", "kind": "responsibility of a person", "about": "person:user", \
        "text": "Signs in.", "split": "tune", "label": "fail"},
          {"id": "c", "kind": "responsibility of a person", "about": "person:admin", \
        "text": "Holds the role.", "split": "test", "label": "fail"},
          {"id": "d", "kind": "responsibility of a person", "about": "person:x", \
        "text": "Unlabelled.", "split": "tune", "label": null}
        ]}
        """);
  }
}
