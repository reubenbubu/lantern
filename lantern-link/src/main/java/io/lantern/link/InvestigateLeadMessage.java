package io.lantern.link;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.core.types.Types;
import io.lantern.harness.Prompts;
import io.lantern.harness.Toml;
import io.lantern.harness.render.Refs;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The first message of a lead's conversation (#860): TOML with the question in plain words, naming
 * every side the lead involves ({@code [task]}); only the evidence the lead points at, each piece
 * once, its code the lines of the method or setting numbered as in the file, its facts one short
 * sentence each with short names ({@code [[evidence]]}); and where to start, when the kind says
 * ({@code [start]}). No list of every unit, no system map, no facts unrelated to the lead.
 */
final class InvestigateLeadMessage {

  /** The most a first message may hold; a test fails on any fake-model run's message above it. */
  static final int MAX_CHARS = 6_000;

  /** The most pieces of evidence a first message gives. */
  static final int MAX_EVIDENCE = 6;

  /** The most lines of a method shown; a longer method is shown around the line pointed at. */
  static final int METHOD_LINES = 24;

  /** The lines shown either side of a setting or a line outside any method. */
  static final int AROUND = 2;

  /** What the lead's own evidence shows, until a fact or its code says more. */
  private static final String FOUND = "Where static analysis found it";

  private static final String POINTS_AT = "The code it points at: ";

  /** The longest a piece's label grows by joining the facts on its lines. */
  static final int MAX_WHAT = 400;

  /** A code line longer than this is cut, ending with "…". */
  static final int LINE_WIDTH = 160;

  private static final String MORE = "; and more facts on these lines.";

  /** The predicates of a send, whose objects name what a lead's code sends. */
  private static final Set<String> SENDS =
      Set.of(
          Types.MESSAGE_SEND,
          Types.HTTP_CALL,
          Types.PUBLISHES,
          Types.SENDS_MESSAGES_TO,
          Types.CALLS_HTTP);

  /** The prompt file with each kind's question. */
  static final String QUESTIONS = "investigate-questions.toml";

  /** A kind's question, what its verdicts mean, and where to start, if anywhere. */
  record Question(String question, String verdicts, String hint) {}

  /** A piece of evidence: what it shows, and its code or, without code, the fact it states. */
  private record Piece(String what, String repo, String path, int from, int to, String fact) {

    boolean code() {
      return path != null;
    }
  }

  private InvestigateLeadMessage() {}

  private static volatile Map<String, Question> questions;

  /** The first message of a lead, the questions as the prompt folder gives them. */
  static String of(
      Investigator.Lead lead,
      Investigator.Model model,
      BiFunction<String, String, Optional<List<String>>> lines) {
    Map<String, Question> q = questions;
    if (q == null) {
      q = questions = questions();
    }
    return of(lead, q, model.repos, entity -> declared(model, entity), lines, model.about.keySet());
  }

  /** Where an entity is declared: the first of its facts with lines. */
  private static Optional<Evidence.Location> declared(Investigator.Model model, String entity) {
    for (Fact fact : model.about.getOrDefault(entity, List.of())) {
      for (Evidence.Location at : fact.evidence().locations()) {
        if (at.lines() != null) {
          return Optional.of(at);
        }
      }
    }
    return Optional.empty();
  }

  /** Each kind's question, as {@value #QUESTIONS} gives it. */
  static Map<String, Question> questions() {
    JsonNode root = Toml.read(Prompts.text(QUESTIONS)).root();
    Map<String, Question> questions = new LinkedHashMap<>();
    for (var kind : root.properties()) {
      JsonNode q = kind.getValue();
      questions.put(
          kind.getKey(),
          new Question(
              q.path("question").asString(""),
              q.path("verdicts").asString(""),
              q.path("hint").isString() ? q.get("hint").asString() : null));
    }
    return Map.copyOf(questions);
  }

  /**
   * The first message of a lead.
   *
   * @param repos the repositories the lead's units live in, by unit
   * @param declared where an entity the lead points at is declared, if known
   * @param lines a file's lines as the tools show them, masked
   */
  static String of(
      Investigator.Lead lead,
      Map<String, Question> questions,
      Map<String, Set<String>> repos,
      Function<String, Optional<Evidence.Location>> declared,
      BiFunction<String, String, Optional<List<String>>> lines,
      Set<String> known) {
    List<Piece> pieces = pieces(lead, declared);
    Set<String> repositories = new TreeSet<>();
    lead.units().forEach(u -> repositories.addAll(repos.getOrDefault(u, Set.of())));
    pieces.stream().filter(Piece::code).forEach(p -> repositories.add(p.repo()));
    Set<String> unitRepos = new TreeSet<>();
    lead.units().forEach(u -> unitRepos.addAll(repos.getOrDefault(u, Set.of())));
    Question question = question(lead, questions, known, unitRepos);
    // Fewer pieces until the message fits; the first ones are the lead's own.
    for (int keep = Math.min(pieces.size(), MAX_EVIDENCE); ; keep--) {
      String text = write(lead, question, repositories, pieces.subList(0, keep), lines);
      if (text.length() <= MAX_CHARS || keep <= 1) {
        return text;
      }
    }
  }

  private static Question question(
      Investigator.Lead lead,
      Map<String, Question> questions,
      Set<String> known,
      Set<String> unitRepos) {
    String kind = lead.kind();
    String scope = scope(lead, known);
    if (kind.equals("contract-mismatch") && scope.startsWith(Types.FRONTEND + ":")) {
      kind = "contract-mismatch-frontend";
    }
    Question q = questions.get(kind);
    if (q == null) {
      return new Question(
          sentence(lead.trigger()) + " Is this a real risk?",
          "confirmed: the code shows the risk on some path. refuted: the code shows it cannot"
              + " happen, and you can show where. unclear: the code does not settle it.",
          null);
    }
    return new Question(
        fill(q.question(), lead, scope, unitRepos),
        fill(q.verdicts(), lead, scope, unitRepos),
        fill(q.hint(), lead, scope, unitRepos));
  }

  private static String fill(
      String template, Investigator.Lead lead, String scope, Set<String> unitRepos) {
    if (template == null) {
      return null;
    }
    List<String> code =
        lead.code().stream().map(InvestigateLeadMessage::shortName).distinct().toList();
    String units =
        lead.units().isEmpty()
            ? "the system"
            : words(
                lead.units().stream().map(InvestigateLeadMessage::shortName).distinct().toList());
    String detail = detail(lead, scope);
    List<String> sent = new ArrayList<>();
    for (Fact fact : lead.facts()) {
      if (SENDS.contains(fact.predicate())
          && fact.object() != null
          && fact.object().value() != null) {
        String to = shortNames(fact.object().value());
        sent.add(
            fact.predicate().equals(Types.HTTP_CALL) || fact.predicate().equals(Types.CALLS_HTTP)
                ? "a request to " + to
                : "a message to " + to);
      }
    }
    return template
        .replace("{units}", units)
        .replace("{detail}", detail.isEmpty() ? "it" : detail)
        .replace(
            "{code}",
            code.isEmpty() ? "the code" : words(code.subList(0, Math.min(3, code.size()))))
        .replace(
            "{sends}",
            sent.isEmpty()
                ? "something to another system"
                : String.join(", ", new LinkedHashSet<>(sent)))
        .replace(
            "{unitRepos}",
            unitRepos.isEmpty() ? "the repositories above" : String.join(", ", unitRepos))
        .replace("{caller}", shortName(scope))
        .replace("{trigger}", stripPeriod(shortNames(lead.trigger())));
  }

  /** The pieces of evidence: where the lead was found, the code it points at, then its facts. */
  private static List<Piece> pieces(
      Investigator.Lead lead, Function<String, Optional<Evidence.Location>> declared) {
    List<Piece> pieces = new ArrayList<>();
    for (Evidence.Location at : lead.fact().evidence().locations()) {
      add(pieces, FOUND, at);
    }
    for (String entity : lead.code()) {
      if (!(entity.startsWith(Types.METHOD + ":")
          || entity.startsWith(Types.TYPE + ":")
          || entity.startsWith(Types.FIELD + ":"))) {
        continue; // a whole unit or module: its lines say nothing of the lead
      }
      declared.apply(entity).ifPresent(at -> add(pieces, POINTS_AT + shortName(entity), at));
    }
    for (Fact fact : lead.facts()) {
      String sentence = sentence(fact);
      Evidence.Location at =
          fact.evidence().locations().isEmpty() ? null : fact.evidence().declared();
      if (at != null && at.lines() != null) {
        add(pieces, sentence, at);
      } else if (pieces.stream().noneMatch(p -> sentence.equals(p.fact()))) {
        pieces.add(new Piece(sentence, null, null, 0, 0, sentence));
      }
    }
    return pieces;
  }

  /** Adds a code piece, or joins it to one of the same file whose lines it overlaps. */
  private static void add(List<Piece> pieces, String what, Evidence.Location at) {
    if (at == null || at.repo() == null || at.path() == null) {
      return;
    }
    int from = at.lines() == null ? 1 : at.lines().start();
    int to = at.lines() == null ? 1 : at.lines().end();
    for (int i = 0; i < pieces.size(); i++) {
      Piece p = pieces.get(i);
      if (p.code()
          && p.repo().equals(at.repo())
          && p.path().equals(at.path())
          && from <= p.to() + METHOD_LINES
          && to >= p.from() - METHOD_LINES) {
        if (p.what().endsWith(MORE)) {
          return;
        }
        if (p.what().length() + what.length() > MAX_WHAT) {
          pieces.set(
              i,
              new Piece(
                  stripPeriod(p.what()) + MORE,
                  p.repo(),
                  p.path(),
                  Math.min(p.from(), from),
                  Math.max(p.to(), to),
                  null));
          return;
        }
        String joined =
            p.what().contains(stripPeriod(what))
                ? p.what()
                : p.what().equals(FOUND) || namedBy(p.what(), what)
                    ? what
                    : stripPeriod(p.what()) + "; " + what;
        pieces.set(
            i,
            new Piece(
                joined, p.repo(), p.path(), Math.min(p.from(), from), Math.max(p.to(), to), null));
        return;
      }
    }
    pieces.add(new Piece(what, at.repo(), at.path(), from, to, null));
  }

  /** Whether a label that only names the code is said again, more fully, by a fact's sentence. */
  private static boolean namedBy(String label, String sentence) {
    return label.startsWith(POINTS_AT)
        && sentence.startsWith(label.substring(POINTS_AT.length()) + " ");
  }

  private static String write(
      Investigator.Lead lead,
      Question question,
      Set<String> repositories,
      List<Piece> pieces,
      BiFunction<String, String, Optional<List<String>>> lines) {
    StringBuilder out = new StringBuilder("[task]\n");
    out.append("question = ").append(Toml.string(question.question())).append('\n');
    out.append("verdicts = ").append(Toml.string(question.verdicts())).append('\n');
    out.append("repositories = [")
        .append(String.join(", ", repositories.stream().map(Toml::string).toList()))
        .append("]\n");
    for (Piece piece : pieces) {
      out.append("\n[[evidence]]\n");
      if (!piece.code()) {
        out.append("fact = ").append(Toml.string(piece.fact())).append('\n');
        continue;
      }
      out.append("what = ").append(Toml.string(piece.what())).append('\n');
      out.append("where = ").append(Toml.string(piece.repo() + ":" + piece.path())).append('\n');
      String code = code(piece, lines);
      if (!code.isEmpty()) {
        out.append("code = \"\"\"\n").append(multiline(code)).append("\"\"\"\n");
      }
    }
    if (question.hint() != null) {
      out.append("\n[start]\nhint = ").append(Toml.string(question.hint())).append('\n');
    }
    return out.toString();
  }

  /**
   * Code as the body of a TOML multi-line basic string: backslashes and control characters escaped,
   * a carriage return dropped, and a quote escaped where it would make a third in a row or touch
   * the closing delimiter, so a Java string such as {@code ""} reads as written.
   */
  static String multiline(String code) {
    StringBuilder out = new StringBuilder(code.length() + 16);
    int quotes = 0;
    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if (c == '"') {
        boolean last = i == code.length() - 1;
        if (quotes == 2 || last) {
          out.append("\\\"");
          quotes = 0;
        } else {
          out.append(c);
          quotes++;
        }
        continue;
      }
      quotes = 0;
      if (c == '\\') {
        out.append("\\\\");
      } else if (c == '\r') {
        // dropped: a line ends at its newline
      } else if (c != '\n' && c != '\t' && (c < 0x20 || c == 0x7f)) {
        out.append(String.format("\\u%04X", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * The lines of the method around a Java piece, or the piece's lines and {@value #AROUND} either
   * side, numbered as in the file.
   */
  static String code(Piece piece, BiFunction<String, String, Optional<List<String>>> lines) {
    Optional<List<String>> file = lines.apply(piece.repo(), piece.path());
    if (file.isEmpty() || file.get().isEmpty()) {
      return "";
    }
    List<String> text = file.get();
    int from = Math.max(1, piece.from() - AROUND);
    int to = Math.min(text.size(), piece.to() + AROUND);
    if (piece.path().endsWith(".java")) {
      JavaOutline.Declaration around = JavaOutline.enclosing(JavaOutline.of(text), piece.from());
      if (around != null && around.to() - around.from() < 200) {
        from = around.from();
        to = Math.max(around.to(), Math.min(text.size(), piece.to()));
      }
    }
    if (to - from + 1 > METHOD_LINES) {
      // A long method: its first lines, then the lines pointed at, as far as the cap allows.
      int start = Math.max(from, Math.min(piece.from() - 4, to - METHOD_LINES + 1));
      from = piece.from() - from < METHOD_LINES - 4 ? from : start;
      to = Math.min(to, from + METHOD_LINES - 1);
    }
    StringBuilder out = new StringBuilder();
    for (int n = from; n <= to; n++) {
      String line = text.get(n - 1);
      out.append(n)
          .append("  ")
          .append(line.length() > LINE_WIDTH ? line.substring(0, LINE_WIDTH - 1) + "…" : line)
          .append('\n');
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Names and sentences

  private static final Pattern ENTITY =
      Pattern.compile(
          "\\b(?:service|module|library|frontend|method|type|field|database|queue|exchange|topic|"
              + "external-system|message-broker|infrastructure|deployment-instance|header|component|"
              + "actor|address|schema):[^\\s,;\"'\\]]+");

  private static final Pattern METHOD =
      Pattern.compile("(?:[\\w$]+\\.)*([\\w$]+)#([\\w$<>]+)\\([^)]*\\)");

  private static final Pattern CLASS =
      Pattern.compile("(?<![\\w.$])(?:[a-z_][\\w]*\\.)+([A-Z][\\w$]*)");

  /** A Maven group and artifact, as {@code com.example:orders}: written as the artifact. */
  private static final Pattern COORDINATE =
      Pattern.compile("(?<![\\w.:/-])[a-z][\\w-]*(?:\\.[\\w-]+)+:([A-Za-z][\\w.-]*)");

  /**
   * The lead's scope: the entity its ID names after its kind, the longest start of the rest that is
   * a subject of the facts, else by its form.
   */
  static String scope(Investigator.Lead lead, Set<String> known) {
    String rest = lead.id().replaceFirst("^lead:[^/]+/", "");
    for (int slash = rest.lastIndexOf('/'); slash > 0; slash = rest.lastIndexOf('/', slash - 1)) {
      if (known.contains(rest.substring(0, slash))) {
        return rest.substring(0, slash);
      }
    }
    if (known.contains(rest)) {
      return rest;
    }
    Matcher frontend = Pattern.compile("^(frontend:[^/]+/[^/]+)(?:/|$)").matcher(rest);
    if (frontend.find()) {
      return frontend.group(1);
    }
    int slash = rest.indexOf('/');
    return slash < 0 || rest.startsWith(Types.DATABASE + ":") ? rest : rest.substring(0, slash);
  }

  /** What the lead's ID names after its scope: a header, a request, a setting, a type. */
  static String detail(Investigator.Lead lead, String scope) {
    String rest = lead.id().replaceFirst("^lead:[^/]+/", "");
    if (rest.length() <= scope.length() + 1) {
      return "";
    }
    String detail =
        URLDecoder.decode(
            rest.substring(scope.length() + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
    detail = detail.replaceFirst("^header:", "");
    int at = detail.lastIndexOf('@');
    if (at > 0 && !detail.contains(" ") && !detail.contains("/")) {
      detail = detail.substring(0, at) + " (in its " + detail.substring(at + 1) + " profile)";
    }
    return shortNames(detail).replaceFirst("^//", "/");
  }

  /** An entity as a reader names it: {@code workorder-service}, {@code OrderController.get}. */
  static String shortName(String entity) {
    if (entity == null || entity.isBlank()) {
      return "";
    }
    int colon = entity.indexOf(':');
    String kind = colon < 0 ? "" : entity.substring(0, colon);
    String rest = colon < 0 ? entity : entity.substring(colon + 1);
    return switch (kind) {
      case Types.SERVICE, Types.MODULE, Types.LIBRARY -> rest.substring(rest.lastIndexOf(':') + 1);
      case Types.FRONTEND -> rest.substring(rest.lastIndexOf('/') + 1);
      case Types.METHOD, Types.FIELD -> member(rest);
      case Types.TYPE -> rest.substring(rest.lastIndexOf('.') + 1);
      case "header" -> rest;
      case Types.DEPLOYMENT_INSTANCE -> deployment(rest);
      default -> rest;
    };
  }

  /**
   * A deployment's instance, as {@code compose:default/redis}: "the compose deployment (default)",
   * since what it runs is the sentence's object.
   */
  private static String deployment(String rest) {
    int slash = rest.lastIndexOf('/');
    String where = slash < 0 ? rest : rest.substring(0, slash);
    int colon = where.indexOf(':');
    return colon < 0
        ? "the " + where + " deployment"
        : "the " + where.substring(0, colon) + " deployment (" + where.substring(colon + 1) + ")";
  }

  private static String member(String rest) {
    String type = rest.contains("#") ? rest.substring(0, rest.indexOf('#')) : rest;
    String name = rest.contains("#") ? rest.substring(rest.indexOf('#') + 1) : "";
    int paren = name.indexOf('(');
    if (paren >= 0) {
      name = name.substring(0, paren);
    }
    String simple = type.substring(type.lastIndexOf('.') + 1);
    return name.isEmpty() ? simple : simple + "." + name;
  }

  /** A text with every entity ID, qualified method and class written by its short name. */
  static String shortNames(String text) {
    if (text == null) {
      return "";
    }
    String out =
        ENTITY.matcher(text).replaceAll(m -> Matcher.quoteReplacement(shortName(m.group())));
    out =
        METHOD
            .matcher(out)
            .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "." + m.group(2)));
    out = CLASS.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
    return COORDINATE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
  }

  private static final Map<String, String> VERBS =
      Map.ofEntries(
          Map.entry(Types.HTTP_CALL, "calls"),
          Map.entry(Types.MESSAGE_SEND, "sends to"),
          Map.entry(Types.CALLS_HTTP, "calls over HTTP"),
          Map.entry(Types.SENDS_MESSAGES_TO, "sends messages to"),
          Map.entry(Types.PUBLISHES, "publishes to"),
          Map.entry(Types.CONSUMES, "consumes from"),
          Map.entry(Types.ENDPOINT, "answers"),
          Map.entry(Types.VERSION_SKEW, "is bundled at different versions; the latest is"),
          Map.entry(Types.RUNS, "runs"),
          Map.entry(Types.USES_DATABASE, "uses the database"),
          Map.entry(Types.USES_IDENTITY_PROVIDER, "uses the identity provider"),
          Map.entry(Types.USES_BROKER, "uses the broker"),
          Map.entry(Types.SHARED_SECRET, "shares the secret"),
          Map.entry(Types.UNROUTED_ENDPOINT, "has no route for"),
          Map.entry(Types.READS_REQUEST_VALUE, "reads the request value"),
          Map.entry(Types.CONFIG_VALUE, "sets"),
          Map.entry(Types.DEPLOYED_IN, "is deployed in"),
          Map.entry(Types.RESOLVES_TO, "resolves to"),
          Map.entry(Types.TRANSACTIONAL, "runs in a transaction, propagation"));

  /** A fact as one short sentence: who, what, the details that tell it apart, short names. */
  static String sentence(Fact fact) {
    String subject = shortName(fact.subject().toString());
    String verb =
        VERBS.getOrDefault(
            fact.predicate(),
            fact.predicate().replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT));
    String object = object(fact.object());
    StringBuilder out = new StringBuilder(subject).append(' ').append(verb);
    if (!object.isEmpty()) {
      out.append(' ').append(object);
    }
    List<String> details = details(fact, object);
    if (!details.isEmpty()) {
      out.append(" (").append(String.join("; ", details)).append(')');
    }
    return sentence(Refs.clean(out.toString()));
  }

  private static String object(FactObject object) {
    if (object == null) {
      return "";
    }
    return object.value() == null ? "" : shortNames(object.value());
  }

  /** The details of a fact a reader needs to tell it apart, in words; no internal fields. */
  private static List<String> details(Fact fact, String object) {
    // Shown as a prompt line shows a fact's attributes, in words; nothing is decided by them.
    JsonNode attributes = Claims.json(fact);
    List<String> details = new ArrayList<>();
    if (attributes == null || !attributes.isObject()) {
      return details;
    }
    String method = attributes.path("httpMethod").asString("");
    String path = attributes.path("path").asString("");
    if (!path.isEmpty() && !object.contains(path)) {
      details.add((method + " " + path).strip());
    }
    for (String key : List.of("profile", "view")) {
      if (attributes.path(key).isString()) {
        details.add("in the " + attributes.get(key).asString() + " profile");
      }
    }
    if (attributes.path("environment").isString()) {
      details.add("in " + attributes.get("environment").asString());
    }
    if (attributes.path("routingKey").isString()) {
      details.add("routing key " + attributes.get("routingKey").asString());
    }
    for (String key : List.of("destination", "queue", "exchange")) {
      if (attributes.path(key).isString()
          && !object.contains(shortName(attributes.get(key).asString()))) {
        details.add("to " + shortName(attributes.get(key).asString()));
      }
    }
    if (attributes.path("pins").isObject()) {
      List<String> pins = new ArrayList<>();
      for (var pin : attributes.get("pins").properties()) {
        pins.add(shortName(pin.getKey()) + " " + pin.getValue().asString(""));
      }
      details.add("versions in use: " + String.join(", ", pins));
    }
    if (attributes.path("target").isString()) {
      details.add("it would go to " + shortName(attributes.get("target").asString()));
    }
    if (attributes.path(Types.ENDPOINT).isString()) {
      details.add("answered by " + shortName(attributes.get(Types.ENDPOINT).asString()));
    }
    return List.copyOf(new LinkedHashSet<>(details));
  }

  private static String sentence(String text) {
    String t = text.strip();
    if (t.isEmpty()) {
      return t;
    }
    return t.endsWith(".") || t.endsWith("?") ? t : t + ".";
  }

  /** Names as a reader lists them: "a", "a and b", "a, b and c". */
  static String words(List<String> names) {
    return names.size() <= 1
        ? String.join("", names)
        : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }

  private static String stripPeriod(String text) {
    String t = text.strip();
    return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
  }
}
