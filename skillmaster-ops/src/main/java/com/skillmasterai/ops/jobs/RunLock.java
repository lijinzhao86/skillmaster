package com.skillmasterai.ops.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * One run per kind, enforced by a row lock rather than a status flag.
 *
 * <p>{@code FOR UPDATE NOWAIT} is the whole mechanism: a second trigger gets the database's
 * "lock not available" (55P03) and the caller answers 409. Nothing has to be cleaned up after a
 * crash — the lock belongs to a connection, and a dead process drops its connection.
 */
@Component
public class RunLock {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final DataSource dataSource;

    public RunLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Empty when the kind is already running. */
    public Optional<Locked> tryAcquire(String kind, String holder) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
        } catch (SQLException failure) {
            throw new IllegalStateException("could not open the lock connection", failure);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT kind FROM job_lock WHERE kind = ? FOR UPDATE NOWAIT")) {
            statement.setString(1, kind);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    connection.rollback();
                    connection.close();
                    throw new IllegalStateException("no job_lock row for kind " + kind);
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE job_lock SET holder = ?, acquired_at = ? WHERE kind = ?")) {
                update.setString(1, holder);
                update.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
                update.setString(3, kind);
                update.executeUpdate();
            }
            return Optional.of(new Locked(connection));
        } catch (SQLException failure) {
            closeQuietly(connection);
            if (LOCK_NOT_AVAILABLE.equals(failure.getSQLState())) {
                return Optional.empty();
            }
            throw new IllegalStateException("could not take the " + kind + " lock", failure);
        }
    }

    /** Rollback on close ends the transaction and releases the row lock. */
    public record Locked(Connection connection) implements AutoCloseable {

        @Override
        public void close() {
            closeQuietly(connection);
        }

        private static void closeQuietly(Connection connection) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The transaction is over either way; the close below releases the connection.
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing else to do: a leaked connection would show up as pool exhaustion.
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
