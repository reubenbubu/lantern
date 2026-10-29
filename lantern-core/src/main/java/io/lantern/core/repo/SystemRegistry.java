package io.lantern.core.repo;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Stores named systems in the {@code systems} table and which repositories belong to each, in the
 * {@code repositories} table's {@code system} column (ADR 0071). A repository belongs to at most
 * one system; one that belongs to none joins the first system that analyzes it.
 */
public class SystemRegistry {

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;

  public SystemRegistry(JdbcClient jdbc, TransactionOperations transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  /**
   * Checks a system name: letters, digits, {@code .}, {@code _} and {@code -}, starting with a
   * letter or digit, as repository names are.
   *
   * @throws IllegalArgumentException if the name is not valid
   */
  public static void checkName(String name) {
    Objects.requireNonNull(name, "name");
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException(
          "Invalid system name: '"
              + name
              + "' (letters, digits, '.', '_' and '-', starting with a letter or digit)");
    }
  }

  /**
   * Creates the system if it does not exist, and sets or clears its title.
   *
   * @param name the system name
   * @param title the display name people gave the system: {@code null} keeps the stored one, blank
   *     clears it, anything else replaces it
   */
  public void define(String name, String title) {
    checkName(name);
    jdbc.sql("INSERT INTO systems (name) VALUES (:name) ON CONFLICT (name) DO NOTHING")
        .param("name", name)
        .update();
    if (title != null) {
      jdbc.sql("UPDATE systems SET title = :title WHERE name = :name")
          .param("name", name)
          .param("title", title.isBlank() ? null : title.strip())
          .update();
    }
  }

  /** The title people gave the system, if any. */
  public Optional<String> title(String name) {
    return jdbc.sql("SELECT title FROM systems WHERE name = :name AND title IS NOT NULL")
        .param("name", name)
        .query(String.class)
        .optional();
  }

  /** The system the repository belongs to, if any. */
  public Optional<String> systemOf(String repo) {
    return jdbc.sql("SELECT system FROM repositories WHERE name = :repo AND system IS NOT NULL")
        .param("repo", repo)
        .query(String.class)
        .optional();
  }

  /** The names of the repositories that belong to the system, sorted. */
  public List<String> members(String system) {
    return jdbc.sql("SELECT name FROM repositories WHERE system = :system ORDER BY name")
        .param("system", system)
        .query(String.class)
        .list();
  }

  /**
   * Adds registered repositories to a defined system. A repository already in it stays.
   *
   * @throws IllegalArgumentException if a repository is not registered or belongs to another
   *     system; nothing is changed then
   */
  public void join(String system, Collection<String> repos) {
    checkName(system);
    transactions.executeWithoutResult(
        status -> {
          for (String repo : new TreeSet<>(repos)) {
            Optional<String> current =
                jdbc.sql("SELECT coalesce(system, '') FROM repositories WHERE name = :repo")
                    .param("repo", repo)
                    .query(String.class)
                    .optional();
            if (current.isEmpty()) {
              throw new IllegalArgumentException("Repository not registered: " + repo);
            }
            String other = current.get();
            if (!other.isEmpty() && !other.equals(system)) {
              throw new IllegalArgumentException(
                  "Repository "
                      + repo
                      + " belongs to the system "
                      + other
                      + ", and a repository belongs to one system only. Remove it from "
                      + other
                      + " first (remove --system="
                      + other
                      + " "
                      + repo
                      + "), or register it under another name ("
                      + "<name>=<path>)");
            }
            jdbc.sql("UPDATE repositories SET system = :system WHERE name = :repo")
                .param("system", system)
                .param("repo", repo)
                .update();
          }
        });
  }

  /**
   * Removes repositories from a system, so later snapshots of it do not carry them forward. Their
   * stored facts and builds stay.
   *
   * @throws IllegalArgumentException if a repository does not belong to the system; nothing is
   *     changed then
   */
  public void remove(String system, Collection<String> repos) {
    checkName(system);
    transactions.executeWithoutResult(
        status -> {
          for (String repo : new TreeSet<>(repos)) {
            int updated =
                jdbc.sql(
                        "UPDATE repositories SET system = NULL"
                            + " WHERE name = :repo AND system = :system")
                    .param("system", system)
                    .param("repo", repo)
                    .update();
            if (updated == 0) {
              throw new IllegalArgumentException(
                  "Repository " + repo + " does not belong to the system " + system);
            }
          }
        });
  }
}
