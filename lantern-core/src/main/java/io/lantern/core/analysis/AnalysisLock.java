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
 * <p>{@link #acquire} takes the lock without waiting for it and keeps the connection it took the
 * lock on, borrowed from the data source, until the returned {@link Held} is closed. Taking that
 * connection can still wait if the data source is a pool with no free connection. If the process
 * dies, PostgreSQL drops the connection and with it the lock, so a crashed analysis never blocks
 * the next one.
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
      boolean unlocked = false;
      try {
        unlocked = call(held, "SELECT pg_advisory_unlock(?)");
      } catch (SQLException e) {
        // The session may still hold the lock. Abort rather than close, so a pool discards the
        // connection instead of lending it out with the lock; ending the session releases it.
        abortQuietly(held, e);
        throw new DataAccessResourceFailureException("Cannot release the analysis lock", e);
      }
      try {
        held.close();
      } catch (SQLException e) {
        throw new DataAccessResourceFailureException(
            "Cannot close the analysis lock connection", e);
      }
      if (!unlocked) {
        throw new IllegalStateException("The analysis lock was not held by this connection");
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

  private static void abortQuietly(Connection connection, SQLException cause) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException e) {
      cause.addSuppressed(e);
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
