package io.lantern.extract.docs;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.GitFiles;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.docs.DecisionRecord.SectionText;
import io.lantern.extract.docs.DecisionRecord.Value;
import io.lantern.extract.docs.Markdown.Block;
import io.lantern.extract.docs.Markdown.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the Markdown and AsciiDoc documentation of repositories: READMEs and other documents (in a
 * {@code docs} folder, or an {@code ARCHITECTURE} file) as low-trust documented claims, and
 * architecture decision records (ADRs) as observed decisions with their title, number, status,
 * date, context, outcome and consequences. See {@code docs/adr/0033-docs-extractor.md}.
 *
 * <p>Entity IDs: {@code readme:<repo>:<path>}, {@code document:<repo>:<path>} and {@code
 * decision:<repo>:<path>}, the path being the file's in the repository, with whitespace and {@code
 * %} percent-encoded.
 *
 * <p>Facts have origin kind {@code readme}: what the documents say, not what the code does.
 */
public final class DocsExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "docs";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "4";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "docs-extractor";

  /** Entity type of a README file. */
  public static final String README = "readme";

  /** Entity type of a document that is neither a README nor a decision record. */
  public static final String DOCUMENT = "document";

  /** Entity type of a decision record. */
  public static final String DECISION = "decision";

  /** Attribute: one paragraph, list item, table row, quote or diagram of a README or document. */
  public static final String CLAIM = "claim";

  /** Attribute: a diagram in a README or document, its source as written. */
  public static final String DIAGRAM = "diagram";

  /** Attribute: a decision record's title. */
  public static final String TITLE = "title";

  /** Attribute: a decision record's number. */
  public static final String NUMBER = "decision-number";

  /** Attribute: a decision record's status, as written. */
  public static final String STATUS = "decision-status";

  /** Attribute: a decision record's date, as written. */
  public static final String DATE = "decision-date";

  /** Attribute: a decision record's context section. */
  public static final String CONTEXT = "decision-context";

  /** Attribute: a decision record's decision section. */
  public static final String OUTCOME = "decision-outcome";

  /** Attribute: one of a decision record's consequences sections. */
  public static final String CONSEQUENCES = "decision-consequences";

  /** Confidence of README claims: documentation the code may have left behind. */
  static final double README_CONFIDENCE = 0.5;

  /** Confidence of what a decision record says: a deliberate record, but it may be stale. */
  static final double DECISION_CONFIDENCE = 0.8;

  /** Files larger than this are not read. */
  static final long MAX_FILE_BYTES = 1024 * 1024;

  private static final String RESOURCE = "/vocabulary/docs-types.json";
  private static final Origin ORIGIN = new Origin(Origin.Kind.README, NAME, VERSION);

  private static final Pattern DOC_FILE = Pattern.compile("(?i).+\\.(md|markdown|adoc|asciidoc)");
  private static final Pattern ASCIIDOC = Pattern.compile("(?i).+\\.(adoc|asciidoc)");
  private static final Pattern README_NAME =
      Pattern.compile("(?i)readme(\\.(md|markdown|adoc|asciidoc))?");
  private static final Pattern ARCHITECTURE_NAME = Pattern.compile("(?i)architecture\\..*");

  /** Files about the project's process, not its architecture. */
  private static final Pattern NOT_ARCHITECTURE =
      Pattern.compile(
          "(?i)(contributing|changelog|changes|history|releases?|release[_-]notes"
              + "|code[_-]of[_-]conduct|licen[cs]e|notice|todo|authors|maintainers|support"
              + "|governance)\\..*|.*template.*|(?-i:SECURITY)\\..*");

  /** A file of OpenAPI Generator's metadata folder, and the folder it generated into. */
  private static final Pattern GENERATOR_MARKER =
      Pattern.compile("^((?:.*/)?)\\.openapi-generator(?:-ignore$|/)");

  /** Folders whose Markdown and AsciiDoc files are documents of the project. */
  private static final Set<String> DOCUMENT_DIRECTORIES =
      Set.of("doc", "docs", "documentation", "architecture");

  private static final Pattern ADR_NAME = Pattern.compile("(?i)adr[-_ ]?\\d+.*");
  private static final Pattern NOT_A_RECORD =
      Pattern.compile("(?i)(readme|index|toc|summary|_sidebar)\\..*|.*template.*");
  private static final Set<String> DECISION_DIRECTORIES =
      Set.of(
          "adr",
          "adrs",
          "decisions",
          "decision-records",
          "decision-log",
          "architecture-decisions",
          "architecture-decision-records");
  private static final Set<String> IGNORED_DIRECTORIES = Set.of("node_modules", "target");

  /** Images, and links, which a badge line is made of once its images are left out. */
  private static final Pattern IMAGES = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

  private static final Pattern LINKS = Pattern.compile("\\[[^\\]]*\\]\\([^)]*\\)");

  private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");

  /** A key, a colon or equals sign, and a value: quoted, or up to whitespace or punctuation. */
  private static final Pattern SECRET_PAIR =
      Pattern.compile(
          "([A-Za-z_][A-Za-z0-9_.\\-]*)([ \\t]*[:=][ \\t]*)(\"[^\"]*\"|'[^']*'|[^\\s,;|`'\"]+)");

  private static final Pattern PLACEHOLDER = Pattern.compile("[\"']?\\$\\{[^}]*}[\"']?");

  /** The vocabulary types this extractor's facts use besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = DocsExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories.
   *
   * @param repos the repositories of the snapshot, each at its commit; builds are not used
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate, object and ID
   * @throws IllegalStateException if git fails or times out
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(repos, snapshot, Set.of());
  }

  /**
   * As {@link #extract(List, long)}, masking the values written under config keys known to hold a
   * secret whatever they are called (ADR 0032, amendment for #338).
   *
   * @param secretKeys config keys known to hold a secret, such as {@link
   *     ConfigExtractor#jwtSecretKeys}
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot, Set<String> secretKeys) {
    Objects.requireNonNull(repos, "repos");
    Objects.requireNonNull(secretKeys, "secretKeys");
    Set<String> known = ConfigExtractor.normalizedKeys(secretKeys);
    Facts facts = new Facts(Validity.from(snapshot));
    for (RepoBuild repo : repos) {
      List<String> paths = GitFiles.list(repo, "");
      List<String> generated = generatedRoots(paths);
      for (String path : paths) {
        if (ignored(path)) {
          continue;
        }
        boolean decision = isDecision(path);
        boolean readme = !decision && isReadme(path);
        boolean document =
            !decision
                && !readme
                && isDocument(path)
                && generated.stream().noneMatch(path::startsWith);
        if (!decision && !readme && !document) {
          continue;
        }
        byte[] content = GitFiles.read(repo, path, MAX_FILE_BYTES);
        if (content == null) {
          continue;
        }
        String text = decode(content);
        Markdown doc =
            ASCIIDOC.matcher(name(path)).matches() ? AsciiDoc.parse(text) : Markdown.parse(text);
        Source source = new Source(repo, path, sha256(content));
        if (decision) {
          if (!decision(source, doc, facts) && DecisionRecord.numbered(name(path))) {
            // A numbered page in plain prose, with no status or sections, does not read as a
            // record; its text is read as a document's rather than lost.
            claims(source.id(DOCUMENT), source, doc, facts, known);
          }
        } else {
          claims(source.id(readme ? README : DOCUMENT), source, doc, facts, known);
        }
      }
    }
    return facts.result();
  }

  /**
   * The folders, each with a trailing {@code /} or empty for the repository's root, that OpenAPI
   * Generator wrote a client or server into: their {@code docs} hold a page per generated model and
   * API, not the project's documentation.
   */
  static List<String> generatedRoots(List<String> paths) {
    return paths.stream()
        .map(p -> GENERATOR_MARKER.matcher(p))
        .filter(Matcher::find)
        .map(m -> m.group(1))
        .distinct()
        .toList();
  }

  /** Whether the path is under a directory no documentation of the repository's own is in. */
  private static boolean ignored(String path) {
    List<String> dirs = directories(path);
    for (int i = 0; i < dirs.size(); i++) {
      if (IGNORED_DIRECTORIES.contains(dirs.get(i))
          || dirs.get(i).equals("src") && i + 1 < dirs.size() && dirs.get(i + 1).equals("test")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the path is a decision record: a Markdown file in a directory named like {@code adr} or
   * {@code decisions}, or named like {@code ADR-012...}, that is not an index or a template.
   */
  static boolean isDecision(String path) {
    String name = name(path);
    if (!DOC_FILE.matcher(name).matches() || NOT_A_RECORD.matcher(name).matches()) {
      return false;
    }
    if (ADR_NAME.matcher(name).matches()) {
      return true;
    }
    for (String dir : directories(path)) {
      if (DECISION_DIRECTORIES.contains(dir.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /** Whether the path is a README outside the decision record directories. */
  static boolean isReadme(String path) {
    if (!README_NAME.matcher(name(path)).matches()) {
      return false;
    }
    for (String dir : directories(path)) {
      if (DECISION_DIRECTORIES.contains(dir.toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the path is a document: a Markdown or AsciiDoc file named {@code ARCHITECTURE}, or in a
   * folder named like {@code docs} outside hidden and decision record folders, that is not a
   * README, a decision record or about the project's process (contributing, changelog, license and
   * the like).
   */
  static boolean isDocument(String path) {
    String name = name(path);
    if (!DOC_FILE.matcher(name).matches()
        || NOT_ARCHITECTURE.matcher(name).matches()
        || isReadme(path)
        || isDecision(path)) {
      return false;
    }
    List<String> dirs = directories(path);
    // Hidden folders hold tooling, and a decision folder's other pages index its records.
    if (dirs.stream()
        .anyMatch(
            d -> d.startsWith(".") || DECISION_DIRECTORIES.contains(d.toLowerCase(Locale.ROOT)))) {
      return false;
    }
    return ARCHITECTURE_NAME.matcher(name).matches()
        || dirs.stream().anyMatch(d -> DOCUMENT_DIRECTORIES.contains(d.toLowerCase(Locale.ROOT)));
  }

  private static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static List<String> directories(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? List.of() : List.of(path.substring(0, slash).split("/"));
  }

  /** Records a decision record's facts; whether the file reads as one. */
  private static boolean decision(Source source, Markdown doc, Facts facts) {
    DecisionRecord record = DecisionRecord.read(name(source.path()), doc);
    if (record == null) {
      return false;
    }
    EntityId id = source.id(DECISION);
    put(facts, id, TITLE, FactObject.string(record.title.value()), source, record.title);
    if (record.number != null) {
      put(facts, id, NUMBER, FactObject.integer(record.number.value()), source, record.number);
    }
    if (record.status != null) {
      put(facts, id, STATUS, FactObject.string(record.status.value()), source, record.status);
    }
    if (record.date != null) {
      put(facts, id, DATE, FactObject.string(record.date.value()), source, record.date);
    }
    for (SectionText s : record.sections) {
      String predicate =
          switch (s.section()) {
            case CONTEXT -> CONTEXT;
            case OUTCOME -> OUTCOME;
            case CONSEQUENCES -> CONSEQUENCES;
          };
      facts.put(
          id,
          predicate,
          FactObject.string(s.text()),
          FactJson.newObject(),
          source.evidence(s.start(), s.end()),
          DECISION_CONFIDENCE);
    }
    return true;
  }

  private static void put(
      Facts facts,
      EntityId id,
      String predicate,
      FactObject object,
      Source source,
      Value<?> where) {
    facts.put(
        id,
        predicate,
        object,
        FactJson.newObject(),
        source.evidence(where.start(), where.end()),
        DECISION_CONFIDENCE);
  }

  private static void claims(
      EntityId id, Source source, Markdown doc, Facts facts, Set<String> known) {
    String section = null;
    for (Block b : doc.blocks) {
      if (b.kind() == Kind.HEADING) {
        section = b.text().isEmpty() ? null : b.text();
        continue;
      }
      if (b.kind() != Kind.PARAGRAPH
          && b.kind() != Kind.LIST_ITEM
          && b.kind() != Kind.TABLE_ROW
          && b.kind() != Kind.QUOTE
          && b.kind() != Kind.DIAGRAM) {
        continue;
      }
      // Leave out lines made only of badges and links, and rows with no words.
      String words = LINKS.matcher(IMAGES.matcher(b.text()).replaceAll("")).replaceAll("");
      if (!WORD.matcher(words).find()) {
        continue;
      }
      ObjectNode attributes = FactJson.newObject();
      if (section != null) {
        attributes.put("section", section);
      }
      if (b.language() != null) {
        attributes.put("language", b.language());
      }
      facts.put(
          id,
          b.kind() == Kind.DIAGRAM ? DIAGRAM : CLAIM,
          FactObject.string(maskSecrets(b.text(), known)),
          attributes,
          source.evidence(b.start(), b.end()),
          README_CONFIDENCE);
    }
  }

  /**
   * The text with the value of each {@code key: value} or {@code KEY=value} pair whose key names a
   * secret, as the Config extractor judges keys ({@link ConfigExtractor#isSecret}), replaced by
   * {@link ConfigExtractor#MASK}. A value that is only a placeholder such as {@code ${DB_PASSWORD}}
   * says where the secret comes from, and is kept.
   */
  static String maskSecrets(String text) {
    return maskSecrets(text, Set.of());
  }

  /**
   * As {@link #maskSecrets(String)}, with keys known to hold a secret too (from {@link
   * ConfigExtractor#normalizedKeys}).
   */
  static String maskSecrets(String text, Set<String> known) {
    Matcher m = SECRET_PAIR.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = m.group(3);
      boolean secret =
          ConfigExtractor.isSecret(m.group(1), known) && !PLACEHOLDER.matcher(value).matches();
      m.appendReplacement(
          out,
          Matcher.quoteReplacement(
              m.group(1) + m.group(2) + (secret ? ConfigExtractor.MASK : value)));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The text of a file: UTF-8, with malformed bytes replaced and a byte order mark left out. */
  private static String decode(byte[] content) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
              .decode(ByteBuffer.wrap(content))
              .toString();
      return text.startsWith("﻿") ? text.substring(1) : text;
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IllegalStateException(e); // cannot happen with REPLACE
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A documentation file at a commit. */
  private record Source(RepoBuild repo, String path, String contentHash) {

    EntityId id(String type) {
      return new EntityId(type, repo.repo().name() + ":" + encode(path));
    }

    /** Evidence for lines start to end, or for the whole file when start is 0. */
    Evidence evidence(int start, int end) {
      return new Evidence(
          repo.repo().name(),
          repo.commit(),
          path,
          start > 0 ? new Evidence.LineRange(start, end) : null,
          contentHash);
    }

    /** The path with {@code %}, whitespace and control characters percent-encoded. */
    private static String encode(String path) {
      StringBuilder out = new StringBuilder();
      path.codePoints()
          .forEach(
              c -> {
                if (c == '%'
                    || Character.isWhitespace(c)
                    || Character.isSpaceChar(c)
                    || Character.isISOControl(c)
                    || c == 0xfeff) {
                  for (byte b : new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xff));
                  }
                } else {
                  out.appendCodePoint(c);
                }
              });
      return out.toString();
    }
  }

  /** The facts of one extraction, without duplicates. */
  private static final class Facts {

    private final Validity validity;
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    Facts(Validity validity) {
      this.validity = validity;
    }

    void put(
        EntityId subject,
        String predicate,
        FactObject object,
        ObjectNode attributes,
        Evidence evidence,
        double confidence) {
      Fact fact =
          Fact.create(
              subject, predicate, object, attributes, ORIGIN, evidence, confidence, validity);
      facts.putIfAbsent(fact.id(), fact);
    }

    List<Fact> result() {
      return facts.values().stream()
          .sorted(
              Comparator.comparing((Fact f) -> f.subject().toString())
                  .thenComparing(Fact::predicate)
                  .thenComparing(f -> f.object().value().toString())
                  .thenComparing(Fact::id))
          .toList();
    }
  }
}
