package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Timestamps;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code credential} — M1's table of how an account proves itself.
 *
 * <p>Separate from {@link AccountRepository} because they are separate things: one row per account
 * for the account itself, and one row per <em>kind</em> of credential, which is what the composite
 * primary key is for. Today only {@code password} is written; {@code totp} has its place held.
 */
public final class CredentialRepository {

    private static final String PASSWORD = "password";

    private final JdbcClient jdbc;

    public CredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sets or replaces the password. An upsert, because resetting one is not a different write. */
    public void putPassword(String userId, String secretHash) {
        jdbc.sql("INSERT INTO credential (user_id, type, secret_hash, updated_at)"
                        + " VALUES (:userId, :type, :hash, :now)"
                        + " ON CONFLICT (user_id, type)"
                        + " DO UPDATE SET secret_hash = EXCLUDED.secret_hash,"
                        + " updated_at = EXCLUDED.updated_at")
                .param("userId", userId)
                .param("type", PASSWORD)
                .param("hash", secretHash)
                .param("now", Timestamps.now())
                .update();
    }

    public Optional<String> passwordHash(String userId) {
        return jdbc.sql("SELECT secret_hash FROM credential WHERE user_id = :userId AND type = :type")
                .param("userId", userId)
                .param("type", PASSWORD)
                .query(String.class)
                .optional();
    }
}
