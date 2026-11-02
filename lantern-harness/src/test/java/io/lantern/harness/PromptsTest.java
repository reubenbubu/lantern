package io.lantern.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The prompts folder (ADR 0113). */
class PromptsTest {

  /** The repository's prompts folder, seen from this module. */
  private static final Path FOLDER = Path.of("..", "prompts");

  /**
   * The texts that were Java text blocks before #706, by their SHA-256 then: moving them into files
   * changed no byte a model is sent.
   */
  private static final Map<String, String> MOVED =
      Map.of(
          "common.system.txt",
          "fd8fba8ca1d123474566c10628bef000e3e17ab9c864cadd9098f8c963cd867e",
          "injection-screen.schema.json",
          "2f3c06cec40bfd7656b7b768acccc28822bdfb20e6995209135ff344694492a1",
          "injection-screen.task.txt",
          "ad4edad25e9cf56f6e3bfaec137da4e4289865d343917a72b5eeff1a0815081b",
          "support-judge.schema.json",
          "bc227cbc7da77489a0be299a8c1ade1d7f5e14168411004c1f0f8cc418876ddc",
          "support-judge.task.txt",
          "b34af0794b6c08e5654c69e876842869261dd2468b95fe6989e48de45c28c2d7",
          "wording-judge-role.task.txt",
          "fe89e62c79d8378d072a600158acc8e714bc87a3437664925b34f84857646820",
          "wording-judge.schema.json",
          "eb8288b6463d3c3fdfec383236198eb50b8e0be47b2e0cd6f1e9745446598b39",
          "wording-judge-specific.task.txt",
          "ca2adb8ae0753ffaf0b8870990ea918ea4171a9d4f8953cd3b64bae7dc912fed");

  @TempDir Path temp;

  @AfterEach
  void builtInOnly() {
    Prompts.useFolder(null);
  }

  @Test
  void theBuiltInCopiesAreTheFolderAsCommitted() throws IOException {
    List<Path> files = files(FOLDER);
    assertThat(files).hasSizeGreaterThanOrEqualTo(28);
    for (Path file : files) {
      String name = file.getFileName().toString();
      assertThat(Prompts.builtIn(name)).as(name).isEqualTo(Files.readString(file));
    }
  }

  @Test
  void everyCommittedPromptHasItsPin() throws IOException {
    Map<String, String> files = new java.util.TreeMap<>();
    for (Path file : files(FOLDER)) {
      files.put(file.getFileName().toString(), Fingerprints.sha256(Files.readString(file)));
    }
    assertThat(Prompts.pins())
        .as(
            "prompts.sha256 pins the committed prompts; after changing a prompt for good, raise"
                + " its role's version and update its pin")
        .isEqualTo(files);
  }

  @Test
  void anEditStaysEditedWhenTheBuiltInCopyIsRebuiltFromIt() throws IOException {
    String edited = Prompts.builtIn("support-judge.task.txt") + " Judge more strictly.";
    Files.writeString(temp.resolve("support-judge.task.txt"), edited);
    Prompts.useFolder(temp);
    // A rebuild makes the built-in copy equal the edit; the pin still tells them apart.
    assertThat(Fingerprints.sha256(Prompts.text("support-judge.task.txt")))
        .isNotEqualTo(Prompts.pins().get("support-judge.task.txt"));
    assertThat(Prompts.edited("support-judge.task.txt")).isTrue();
  }

  @Test
  void theTextsMovedOutOfTheCodeAreUnchanged() {
    MOVED.forEach(
        (name, sha) ->
            assertThat(Fingerprints.sha256(Prompts.builtIn(name))).as(name).isEqualTo(sha));
  }

  @Test
  void anUneditedFolderKeepsEveryVersion() {
    Prompts.useFolder(FOLDER);
    assertThat(Prompts.version("5", "common.system.txt", "support-judge.task.txt")).isEqualTo("5");
  }

  @Test
  void anEditedFileIsReadAndChangesOnlyTheVersionsOfTheRolesThatUseIt() throws IOException {
    Files.writeString(temp.resolve("support-judge.task.txt"), "Task: judge more strictly.");
    Prompts.useFolder(temp);

    assertThat(Prompts.text("support-judge.task.txt")).isEqualTo("Task: judge more strictly.");
    assertThat(Prompts.edited("support-judge.task.txt")).isTrue();
    String edited = Prompts.version("1", "common.system.txt", "support-judge.task.txt");
    assertThat(edited).startsWith("1+").hasSize(2 + 12);
    assertThat(Prompts.version("1", "common.system.txt", "injection-screen.task.txt"))
        .isEqualTo("1");

    Files.writeString(temp.resolve("support-judge.task.txt"), "Task: judge even more strictly.");
    Prompts.useFolder(temp);
    assertThat(Prompts.version("1", "common.system.txt", "support-judge.task.txt"))
        .startsWith("1+")
        .isNotEqualTo(edited);
  }

  @Test
  void aFileTheFolderLacksFallsBackToTheBuiltInCopy() {
    Prompts.useFolder(temp);
    assertThat(Prompts.text("resolve.task.txt")).isEqualTo(Prompts.builtIn("resolve.task.txt"));
    assertThat(Prompts.edited("resolve.task.txt")).isFalse();
  }

  @Test
  void aMissingFolderIsAnError() {
    assertThatThrownBy(() -> Prompts.useFolder(temp.resolve("none")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not exist");
  }

  private static List<Path> files(Path dir) throws IOException {
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".txt") || p.toString().endsWith(".json"))
          .sorted()
          .toList();
    }
  }
}
