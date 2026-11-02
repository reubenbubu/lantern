package io.lantern.harness.verify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks of the verification pipeline (knowledge-build epic, section 10): Schema, References,
 * Ranges, Names, Coverage, Form, Generic, Conflict and Secrets, in that order.
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
          new Generic(),
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
  public static final List<String> PROTOCOL_WORDS =
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

  /**
   * Words that say nothing about what a part does, so a responsibility made of its name and these
   * restates the name (#685).
   */
  static final Set<String> EMPTY_WORDS =
      Set.of(
          "handles",
          "handle",
          "manages",
          "manage",
          "provides",
          "provide",
          "responsible",
          "offers",
          "supports",
          "contains",
          "implements",
          "logic",
          "operations",
          "functionality",
          "related",
          "various",
          "service",
          "services",
          "class",
          "component",
          "it",
          "its",
          "this",
          "that",
          "which",
          "are",
          "be",
          "as",
          "all");

  /** A responsibility that adds fewer content words than this beyond its name restates it. */
  static final int ADDED_WORDS = 3;

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
      return JsonSchema.violationsAt(inputs.schema(), answer.raw()).stream()
          .map(
              v ->
                  reject(
                      answer,
                      field(v.path()),
                      this,
                      "Does not match the schema: " + v.message() + "."))
          .toList();
    }

    /**
     * The field a violation at the path rejects: the text a {@code text} or {@code cites} is part
     * of, so a repair turn redoes that text and keeps the item's others.
     */
    static String field(String path) {
      // Within a list, the whole list: its entries have no names a repair could keep apart.
      String field = path.contains("[") ? path.substring(0, path.indexOf('[')) : path;
      if (field.endsWith(".text") || field.endsWith(".cites")) {
        field = field.substring(0, field.lastIndexOf('.'));
      } else if (field.equals("text") || field.equals("cites")) {
        field = "";
      }
      return field;
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
        support.addAll(citedFiles(text));
        if (!text.subjectName().isEmpty()) {
          support.add(text.subjectName());
        }
        support.addAll(inputs.shown());
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

    /**
     * Whether more than 60% of the subject's name tokens are among the first 6 tokens, and the
     * sentence adds fewer than {@link #ADDED_WORDS} content words beyond the name: a sentence that
     * opens with the name's words but says what the part does is not a restatement (#685).
     */
    static boolean restates(CheckedText text) {
      List<String> name = tokens(text.subjectName()).stream().map(Form::singular).toList();
      if (name.isEmpty()) {
        return false;
      }
      List<String> all = tokens(text.text()).stream().map(Form::singular).toList();
      List<String> first = all.subList(0, Math.min(6, all.size()));
      long shared = name.stream().filter(first::contains).count();
      if (shared * 10 <= name.size() * 6L) {
        return false;
      }
      long added =
          all.stream()
              .filter(token -> !name.contains(token))
              .filter(token -> !EMPTY_WORDS.contains(token) && !EMPTY_WORDS.contains(token + "s"))
              .count();
      return added < ADDED_WORDS;
    }

    /** A token without a plural "s", so "orders" matches the name token "order". */
    private static String singular(String token) {
      return token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")
          ? token.substring(0, token.length() - 1)
          : token;
    }

    public static List<String> tokens(String text) {
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

  /**
   * A description sentence must say something about the system (#275): no text may be a sentence
   * true of any Spring application ({@code checks/generic-sentences.txt}), and a sentence shown
   * alone as a description, a responsibility or the lead of a summary, may not open with a pronoun
   * or restate what kind of part it is ({@code checks/weak-openings.txt}).
   */
  public static final class Generic implements Check {

    /** The sentences true of any Spring application. */
    static final List<Pattern> GENERIC = patterns("checks/generic-sentences.txt");

    /** The openings a sentence shown alone may not have. */
    static final List<Pattern> WEAK_OPENINGS = patterns("checks/weak-openings.txt");

    @Override
    public String name() {
      return "Generic";
    }

    @Override
    public List<Rejection> check(ItemAnswer answer, CheckInputs inputs) {
      List<Rejection> found = new ArrayList<>();
      for (CheckedText text : answer.texts()) {
        if (text.kind() == CheckedText.Kind.INTENT) {
          continue;
        }
        String sentence = text.text().strip();
        if (GENERIC.stream().anyMatch(p -> p.matcher(sentence).find())) {
          found.add(
              reject(
                  answer,
                  text.field(),
                  this,
                  "\""
                      + sentence
                      + "\" would be true of any Spring application; say what this part decides,"
                      + " owns or protects."));
        } else if (text.kind() != CheckedText.Kind.PROSE && weakOpening(sentence)) {
          found.add(
              reject(
                  answer,
                  text.field(),
                  this,
                  "\""
                      + sentence
                      + "\" opens with a pronoun or restates what kind of part it is; it is shown"
                      + " alone, so open with what the part does."));
        }
      }
      return found;
    }

    /** Whether a sentence opens with a pronoun or with the kind of part it is about. */
    public static boolean weakOpening(String sentence) {
      return WEAK_OPENINGS.stream().anyMatch(p -> p.matcher(sentence).lookingAt());
    }

    /** The patterns of a resource, one a line, case-insensitive; {@code #} starts a comment. */
    private static List<Pattern> patterns(String resource) {
      List<Pattern> patterns = new ArrayList<>();
      try (InputStream in = Checks.class.getClassLoader().getResourceAsStream(resource)) {
        if (in == null) {
          throw new IllegalStateException("Missing resource " + resource);
        }
        BufferedReader lines =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
          String pattern = line.strip();
          if (!pattern.isEmpty() && !pattern.startsWith("#")) {
            patterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read " + resource, e);
      }
      return List.copyOf(patterns);
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

  /**
   * The file name of each range a text cites: a text may name the file whose lines it cites, but
   * not the folders above it (#685). Only the Names check reads these; Coverage still needs a
   * declared name inside the cited lines.
   */
  static List<String> citedFiles(CheckedText text) {
    List<String> files = new ArrayList<>();
    for (String cite : text.cites()) {
      Matcher range = RANGE.matcher(cite);
      if (range.matches()) {
        String path = range.group(2);
        files.add(path.substring(path.lastIndexOf('/') + 1));
      }
    }
    return files;
  }

  private static String normal(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }
}
