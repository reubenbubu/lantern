package io.lantern.link;

import java.util.Objects;
import java.util.Optional;

/**
 * What the linker remembers of each rule's last run, per system (ADR 0095): the rule's version and
 * digests of the facts it read and of those it gave, so a rule whose input and version are the same
 * need not run again.
 */
public interface RuleRuns {

  /**
   * A rule's last run.
   *
   * @param version the rule's version
   * @param inputs the digest of the facts it read
   * @param outputs the digest of the facts it gave, as stored
   * @param snapshot the snapshot it ran for
   */
  record Run(String version, String inputs, String outputs, long snapshot) {

    public Run {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(inputs, "inputs");
      Objects.requireNonNull(outputs, "outputs");
    }
  }

  /**
   * The last run of a rule.
   *
   * @param scope the system's repositories, as {@link Linker} names them
   * @param rule the rule's name
   */
  Optional<Run> last(String scope, String rule);

  /** Records a rule's run, replacing the last one. */
  void record(String scope, String rule, Run run);

  /** Remembers nothing, so every rule runs every time. */
  RuleRuns NONE =
      new RuleRuns() {
        @Override
        public Optional<Run> last(String scope, String rule) {
          return Optional.empty();
        }

        @Override
        public void record(String scope, String rule, Run run) {}
      };
}
