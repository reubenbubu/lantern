package io.lantern.core.analysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Makes sure only one analysis runs at a time, across every Lantern process that uses the same
 * database, with a PostgreSQL session-level advisory lock.
 *
 * <p>{@link #acquire} takes the lock without waiting and holds a database connection of its own
 * until the returned {@link Held} is closed. If the process dies, PostgreSQL drops the connection
 * and with it the lock, so a crashed analysis never blocks the next one.
 */
public class AnalysisLock {

  /** The advisory lock key: the ASCII bytes of "LANTERN" followed by 'A' for analysis. */
  static final long KEY = 0x4c414e5445524e41L;

  private final DataSource dataSource;

  public AnalysisLock(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Takes the analysis lock, failing at once if another analysis holds it.
   *
   * @return the held lock; close it when the analysis ends
   * @throws AnalysisAlreadyRunningException if another analysis holds the lock
   */
  public Held acquire() {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      connection.setAutoCommit(true);
      if (!call(connection, "SELECT pg_try_advisory_lock(?)")) {
        connection.close();
        throw new AnalysisAlreadyRunningException();
      }
      return new Held(connection);
    } catch (SQLException e) {
      closeQuietly(connection, e);
      throw new DataAccessResourceFailureException("Cannot take the analysis lock", e);
    }
  }

  /** The analysis lock while held. Closing it releases the lock; closing it again does nothing. */
  public static final class Held implements AutoCloseable {

    private Connection connection;

    private Held(Connection connection) {
      this.connection = connection;
    }

    @Override
    public synchronized void close() {
      if (connection == null) {
        return;
      }
      Connection held = connection;
      connection = null;
      try (held) {
        if (!call(held, "SELECT pg_advisory_unlock(?)")) {
          throw new IllegalStateException("The analysis lock was not held by this connection");
        }
      } catch (SQLException e) {
        throw new DataAccessResourceFailureException("Cannot release the analysis lock", e);
      }
    }
  }

  private static boolean call(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, KEY);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static void closeQuietly(Connection connection, SQLException cause) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }
}
