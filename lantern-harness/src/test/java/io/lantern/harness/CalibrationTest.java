package io.lantern.harness;

import static io.lantern.harness.TestHarness.client;
import static io.lantern.harness.TestHarness.harness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lantern.harness.checks.WordingJudge;
import io.lantern.harness.checks.WordingJudge.Dimension;
import io.lantern.llm.FakeLlmProvider;
import io.lantern.llm.LlmRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Packet-size calibration (#529): a role asked at another size, its answers kept apart. */
class CalibrationTest {

  private static final Pattern ITEM = Pattern.compile("<item id=\"(I[0-9]+)\">");

  private final InMemoryAnswerStore store = new InMemoryAnswerStore();

  private static String passing(LlmRequest request) {
    Matcher ids = ITEM.matcher(request.messages().getFirst().text());
    List<String> entries = new ArrayList<>();
    while (ids.find()) {
      entries.add("\"" + ids.group(1) + "\": {\"reasoning\": \"r\", \"verdict\": \"pass\"}");
    }
    return "{\"items\": {" + String.join(", ", entries) + "}}";
  }

  private static List<WordingJudge.Text> texts() {
    return IntStream.range(0, 6)
        .mapToObj(
            i ->
                new WordingJudge.Text(
                    "t" + i, "responsibility of a service", "service:s" + i, "Does job " + i + "."))
        .toList();
  }

  private int judge(FakeLlmProvider fake, Harness.Calibration calibration) {
    int before = fake.sent().size();
    Harness harness = harness(client(fake), store);
    if (calibration != null) {
      harness.calibrating(Dimension.ROLE.role().name(), calibration);
    }
    assertThat(new WordingJudge(harness).judge(Dimension.ROLE, texts())).hasSize(6);
    RoleRecord record = harness.records().getFirst();
    assertThat(record.toJson().path("packetSize").asInt())
        .isEqualTo(calibration == null ? 10 : calibration.packetSize());
    return fake.sent().size() - before;
  }

  @Test
  void aCalibratedRoleIsAskedAgainAtItsSizeAndItsUsualAnswersStay() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(CalibrationTest::passing);

    // Its usual size: six items in one request.
    assertThat(judge(fake, null)).isEqualTo(1);
    // At size 1, trial 1: asked again, one request an item.
    assertThat(judge(fake, new Harness.Calibration(1, "1"))).isEqualTo(6);
    // The same size and trial again: from the cache.
    assertThat(judge(fake, new Harness.Calibration(1, "1"))).isZero();
    // A second trial at size 1, for the noise between two recordings: asked again.
    assertThat(judge(fake, new Harness.Calibration(1, "2"))).isEqualTo(6);
    // At size 5: two requests.
    assertThat(judge(fake, new Harness.Calibration(5, "1"))).isEqualTo(2);
    // The usual size still reads its own answers.
    assertThat(judge(fake, null)).isZero();
  }

  @Test
  void aCalibrationIsSetBeforeItsRoleStartsAndHoldsAtLeastOneItem() {
    Harness harness = harness(client(new FakeLlmProvider()), store);
    harness.session(Dimension.ROLE.role());
    assertThatThrownBy(
            () ->
                harness.calibrating(Dimension.ROLE.role().name(), new Harness.Calibration(1, "1")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new Harness.Calibration(0, "1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void itemsPerRequestChangeTheRequestsButNotTheAnswersCache() {
    FakeLlmProvider fake = new FakeLlmProvider().answering(CalibrationTest::passing);
    Harness harness = harness(client(fake), store).itemsPerRequest(Dimension.ROLE.role().name(), 2);
    assertThat(new WordingJudge(harness).judge(Dimension.ROLE, texts())).hasSize(6);
    // Six items, two a request.
    assertThat(fake.sent()).hasSize(3);
    assertThat(harness.records().getFirst().toJson().path("packetSize").asInt()).isEqualTo(2);
    // At the usual size the same items read the cache: a size is not part of a fingerprint.
    assertThat(judge(fake, null)).isZero();
  }

  @Test
  void itemsPerRequestAreSetBeforeTheRoleStartsAndHoldAtLeastOne() {
    Harness harness = harness(client(new FakeLlmProvider()), store);
    assertThatThrownBy(() -> harness.itemsPerRequest(Dimension.ROLE.role().name(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    harness.session(Dimension.ROLE.role());
    assertThatThrownBy(() -> harness.itemsPerRequest(Dimension.ROLE.role().name(), 3))
        .isInstanceOf(IllegalStateException.class);
  }
}
