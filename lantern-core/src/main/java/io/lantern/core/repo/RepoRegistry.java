package io.lantern.core.repo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores registered repositories in the {@code repositories} table and resolves their commits with
 * the {@code git} command line tool.
 */
public class RepoRegistry {

  /** The revision that names the commit currently checked out. */
  public static final String HEAD = "HEAD";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

  private final JdbcClient jdbc;

  public RepoRegistry(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * Registers a repository, or updates the path and settings of one already registered under the
   * name.
   *
   * @param name the repository name, see {@link Repo}
   * @param path the top directory of a git working tree on this host
   * @param settings the per-repository build settings
   * @return the registered repository, with its path made absolute and real
   * @throws IllegalArgumentException if the name is invalid or the path is not the top of a git
   *     working tree; nothing is stored then
   */
  public Repo register(String name, Path path, RepoSettings settings) {
    Repo.checkName(name);
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(settings, "settings");
    Path real = workTreeRoot(path);
    Repo repo = new Repo(name, real, settings);
    jdbc.sql(
            """
            INSERT INTO repositories (name, path, settings)
            VALUES (:name, :path, CAST(:settings AS jsonb))
            ON CONFLICT (name) DO UPDATE SET path = excluded.path, settings = excluded.settings
            """)
        .param("name", repo.name())
        .param("path", repo.path().toString())
        .param("settings", writeSettings(repo.settings()))
        .update();
    return repo;
  }

  /** The repository registered under the name, if any. */
  public Optional<Repo> find(String name) {
    Objects.requireNonNull(name, "name");
    return jdbc.sql(
            "SELECT name, path, settings::text AS settings FROM repositories WHERE name = :n")
        .param("n", name)
        .query(RepoRegistry::mapRow)
        .optional();
  }

  /** All registered repositories, by name. */
  public List<Repo> all() {
    return jdbc.sql("SELECT name, path, settings::text AS settings FROM repositories ORDER BY name")
        .query(RepoRegistry::mapRow)
        .list();
  }

  /**
   * Resolves a revision of a registered repository to its full commit hash.
   *
   * @param name the repository name
   * @param revision {@link #HEAD}, a branch or tag name, or a full or abbreviated commit hash
   * @return the full commit hash, in lowercase hex
   * @throws IllegalArgumentException if the repository is not registered or the revision does not
   *     name a commit in it
   */
  public String resolve(String name, String revision) {
    Objects.requireNonNull(revision, "revision");
    Repo repo =
        find(name)
            .orElseThrow(() -> new IllegalArgumentException("Repository not registered: " + name));
    if (revision.isBlank() || revision.startsWith("-")) {
      throw new IllegalArgumentException("Invalid revision: '" + revision + "'");
    }
    Git.Result result =
        Git.run(repo.path(), "rev-parse", "--verify", "--quiet", revision + "^{commit}");
    if (!result.ok() || !COMMIT.matcher(result.out()).matches()) {
      throw new IllegalArgumentException(
          "Revision '" + revision + "' is not a commit in repository " + name);
    }
    return result.out();
  }

  /** The real path of the top of the working tree at the path, which must be that top. */
  private static Path workTreeRoot(Path path) {
    Path real;
    try {
      real = path.toAbsolutePath().toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException("Repository path does not exist: " + path, e);
    }
    if (!Files.isDirectory(real)) {
      throw new IllegalArgumentException("Repository path is not a directory: " + path);
    }
    Git.Result result = Git.run(real, "rev-parse", "--show-toplevel");
    if (!result.ok()) {
      throw new IllegalArgumentException(
          "Repository path is not a git working tree: " + path + " (" + result.err() + ")");
    }
    Path top;
    try {
      top = Path.of(result.out()).toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!top.equals(real)) {
      throw new IllegalArgumentException(
          "Repository path must be the top of its working tree: " + path + " is inside " + top);
    }
    return real;
  }

  private static Repo mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Repo(
        rs.getString("name"),
        Path.of(rs.getString("path")),
        readSettings(rs.getString("settings")));
  }

  static String writeSettings(RepoSettings settings) {
    ObjectNode json = MAPPER.createObjectNode();
    if (settings.jdk() != null) {
      json.put("jdk", settings.jdk());
    }
    if (!settings.mavenProfiles().isEmpty()) {
      ArrayNode profiles = json.putArray("mavenProfiles");
      settings.mavenProfiles().forEach(profiles::add);
    }
    if (!settings.skipModules().isEmpty()) {
      ArrayNode modules = json.putArray("skipModules");
      settings.skipModules().forEach(modules::add);
    }
    return MAPPER.writeValueAsString(json);
  }

  static RepoSettings readSettings(String text) {
    JsonNode json = MAPPER.readTree(text);
    JsonNode jdk = json.get("jdk");
    return new RepoSettings(
        jdk == null ? null : jdk.intValue(),
        strings(json.get("mavenProfiles")),
        strings(json.get("skipModules")));
  }

  private static List<String> strings(JsonNode array) {
    if (array == null) {
      return List.of();
    }
    return array.valueStream().map(JsonNode::stringValue).toList();
  }
}
