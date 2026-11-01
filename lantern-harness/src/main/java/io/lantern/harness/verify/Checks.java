package io.lantern.harness.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The eight checks of the verification pipeline (knowledge-build epic, section 10): Schema,
 * References, Ranges, Names, Coverage, Form, Conflict and Secrets, in that order.
 */
public final class Checks {

  private Checks() {}

  /** Every check, in the order the pipeline runs them. */
  public static final List<Check> STANDARD =
      List.of(
          new Schema(),
          new References(),
          new Ranges(),
          new Names(),
          new Coverage(),
          new Form(),
          new Conflict(),
          new Secrets());

  /** The longest file range a model may cite. */
  public static final int MAX_RANGE_LINES = 40;

  /** A file range the model wrote: {@code repo:path:from-to}. */
  static final Pattern RANGE = Pattern.compile("^([^:\\s]+):([^:\\s]+):(\\d+)-(\\d+)$");

  /** A local reference: a letter and a number, such as {@code F12}. */
  static final Pattern LOCAL = Pattern.compile("^([A-Z])(\\d+)$");

  /** The openings an intent phrase may not start with. */
  static final List<String> BANNED_OPENINGS =
      List.of(
          "uses",
          "use",
          "calls",
          "call",
          "depends on",
          "connects to",
          "talks to",
          "communicates with",
          "interacts with");

  /** The protocol words an intent phrase may not hold. */
  static final List<String> PROTOCOL_WORDS =
      List.of(
          "HTTP",
          "HTTPS",
          "REST",
          "JSON",
          "AMQP",
          "Kafka",
          "JMS",
          "JDBC",
          "SQL",
          "gRPC",
          "WebSocket",
          "STOMP",
          "SMTP",
          "RESP");

  /** Words left out when comparing a responsibility with its subject's name. */
  static final Set<String> STOP_WORDS =
      Set.of("a", "an", "the", "of", "and", "or", "for", "to", "in", "on", "with", "by", "is");

  private static Rejection reject(ItemAnswer answer, String field, Check check, String reason) {
    return new Rejection(answer.item(), field, check.name(), reason);
  }

  /** The answer must match the role's schema. */
  public static final class Schema implements Check {
    @Override
    public String name() {
      return "Schema";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      if (answer.omitted()) {
        return List.of();
      }
      return JsonSchema.violations(inputs.schema(), answer.raw()).stream()
          .map(v -> reject(answer, "", this, "Does not match the schema: " + v + "."))
          .toList();
    }
  }

  /** Every citation must be shown to the item and of a kind the role may cite. */
  public static final class References implements Check {
    @Override
    public String name() {
      return "References";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        for (String cite : text.cites()) {
          Matcher local = LOCAL.matcher(cite);
          if (local.matches()) {
            if (!inputs.citable().contains(local.group(1).charAt(0))) {
              found.add(
                  reject(
                      answer,
                      text.field(),
                      this,
                      "Cites " + cite + ", a kind of reference this task does not cite."));
            } else if (!inputs.references().containsKey(cite)) {
              found.add(
                  reject(
                      answer,
                      text.field(),
                      this,
                      "Cites " + cite + ", which is not in your input."));
            }
          } else if (RANGE.matcher(cite).matches()) {
            if (!inputs.ranges()) {
              found.add(
                  reject(
                      answer,
                      text.field(),
                      this,
                      "Cites the file range "
                          + cite
                          + "; this task cites only references of its input."));
            }
          } else {
            found.add(
                reject(
                    answer, text.field(), this, "Cites " + cite + ", which is not a reference."));
          }
        }
      }
      return found;
    }
  }

  /** A file range the model wrote must be in a file of the snapshot and at most 40 lines. */
  public static final class Ranges implements Check {
    @Override
    public String name() {
      return "Ranges";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        for (String cite : text.cites()) {
          Matcher range = RANGE.matcher(cite);
          if (!range.matches() || !inputs.ranges()) {
            continue;
          }
          int from = Integer.parseInt(range.group(3));
          int to = Integer.parseInt(range.group(4));
          Optional<List<String>> lines = inputs.files().lines(range.group(1), range.group(2));
          if (lines.isEmpty()) {
            found.add(
                reject(
                    answer,
                    text.field(),
                    this,
                    "Cites " + cite + ", a file not of this snapshot."));
          } else if (from < 1 || to < from || to > lines.get().size()) {
            found.add(
                reject(
                    answer,
                    text.field(),
                    this,
                    "Cites " + cite + ", outside the file's " + lines.get().size() + " lines."));
          } else if (to - from + 1 > MAX_RANGE_LINES) {
            found.add(
                reject(
                    answer,
                    text.field(),
                    this,
                    "Cites " + cite + ", longer than " + MAX_RANGE_LINES + " lines."));
          }
        }
      }
      return found;
    }
  }

  /** Every code-like name or technology in a text must appear in what it cites (ADR 0068). */
  public static final class Names implements Check {
    @Override
    public String name() {
      return "Names";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        List<String> support = new ArrayList<>(cited(text, inputs));
        if (!text.subjectName().isEmpty()) {
          support.add(text.subjectName());
        }
        List<String> missing = Grounding.unsupported(text.text(), support);
        if (!missing.isEmpty()) {
          found.add(
              reject(
                  answer,
                  text.field(),
                  this,
                  "Names " + String.join(", ", missing) + ", which none of its citations show."));
        }
      }
      return found;
    }
  }

  /** A proposal's declared names must all appear in its cited lines, and each range hold one. */
  public static final class Coverage implements Check {
    @Override
    public String name() {
      return "Coverage";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        if (text.names().isEmpty()) {
          continue;
        }
        List<String> cited = cited(text, inputs);
        String all = normal(String.join("\n", cited));
        for (String name : text.names()) {
          if (!all.contains(normal(name))) {
            found.add(
                reject(
                    answer,
                    text.field(),
                    this,
                    "Declares the name " + name + ", which its cited lines do not hold."));
          }
        }
        for (int i = 0; i < cited.size(); i++) {
          String lines = normal(cited.get(i));
          if (text.names().stream().noneMatch(name -> lines.contains(normal(name)))) {
            found.add(
                reject(
                    answer,
                    text.field(),
                    this,
                    "Cites " + text.cites().get(i) + ", which holds none of its declared names."));
          }
        }
      }
      return found;
    }
  }

  /** Word limits, intent openings and protocol words, and responsibilities that restate a name. */
  public static final class Form implements Check {
    @Override
    public String name() {
      return "Form";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        String[] words = text.text().trim().split("\\s+");
        if (words.length > text.wordLimit()) {
          found.add(
              reject(
                  answer,
                  text.field(),
                  this,
                  "Has " + words.length + " words; the limit is " + text.wordLimit() + "."));
        }
        if (text.kind() == CheckedText.Kind.INTENT) {
          String lower = text.text().trim().toLowerCase(Locale.ROOT);
          for (String opening : BANNED_OPENINGS) {
            if (lower.equals(opening) || lower.startsWith(opening + " ")) {
              found.add(
                  reject(
                      answer,
                      text.field(),
                      this,
                      "\""
                          + text.text()
                          + "\" starts with an opening that says nothing about intent."));
              break;
            }
          }
          for (String word : PROTOCOL_WORDS) {
            if (Pattern.compile(
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE)
                .matcher(text.text())
                .find()) {
              found.add(
                  reject(
                      answer,
                      text.field(),
                      this,
                      "\""
                          + text.text()
                          + "\" names the protocol "
                          + word
                          + "; say what the link is for."));
              break;
            }
          }
        }
        if (text.kind() == CheckedText.Kind.RESPONSIBILITY && restates(text)) {
          found.add(
              reject(
                  answer,
                  text.field(),
                  this,
                  "Restates its subject's name; say what it is responsible for."));
        }
      }
      return found;
    }

    /** Whether more than 60% of the subject's name tokens are among the first 6 tokens. */
    static boolean restates(CheckedText text) {
      List<String> name = tokens(text.subjectName());
      if (name.isEmpty()) {
        return false;
      }
      List<String> first = tokens(text.text());
      first = first.subList(0, Math.min(6, first.size()));
      long shared = name.stream().filter(first::contains).count();
      return shared * 10 > name.size() * 6L;
    }

    static List<String> tokens(String text) {
      String split =
          text.replaceAll("([\\p{Ll}\\p{N}])(\\p{Lu})", "$1 $2").replaceAll("[._\\-/:#]", " ");
      List<String> tokens = new ArrayList<>();
      for (String token : split.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
        if (!token.isEmpty() && !STOP_WORDS.contains(token)) {
          tokens.add(token);
        }
      }
      return tokens;
    }
  }

  /** A proposed claim must not repeat or contradict a static claim; static wins. */
  public static final class Conflict implements Check {
    @Override
    public String name() {
      return "Conflict";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (ProposedClaim claim : answer.claims()) {
        Set<String> objects =
            inputs.staticClaims().get(CheckInputs.claimKey(claim.subject(), claim.predicate()));
        if (objects == null || objects.isEmpty()) {
          continue;
        }
        found.add(
            reject(
                answer,
                claim.field(),
                this,
                objects.contains(claim.object())
                    ? "Repeats a claim the static analysis already made."
                    : "Contradicts a claim the static analysis made: "
                        + claim.subject()
                        + " "
                        + claim.predicate()
                        + " "
                        + String.join(", ", objects)
                        + "."));
      }
      return found;
    }
  }

  /** No text may hold a value the masking marked as secret. */
  public static final class Secrets implements Check {
    @Override
    public String name() {
      return "Secrets";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        for (String secret : inputs.secrets()) {
          if (!secret.isEmpty() && text.text().contains(secret)) {
            found.add(
                reject(
                    answer, text.field(), this, "Holds a value marked as secret; leave it out."));
            break;
          }
        }
      }
      return found;
    }
  }

  /** The text of each reference a text cites, the lines of a file range included. */
  static List<String> cited(CheckedText text, CheckInputs inputs) {
    List<String> cited = new ArrayList<>();
    for (String cite : text.cites()) {
      Matcher range = RANGE.matcher(cite);
      if (range.matches()) {
        List<String> lines = inputs.files().lines(range.group(1), range.group(2)).orElse(List.of());
        int from = Math.max(1, Integer.parseInt(range.group(3)));
        int to = Math.min(lines.size(), Integer.parseInt(range.group(4)));
        cited.add(from > to ? "" : String.join("\n", lines.subList(from - 1, to)));
      } else {
        cited.add(inputs.references().getOrDefault(cite, ""));
      }
    }
    return cited;
  }

  private static String normal(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }
}
