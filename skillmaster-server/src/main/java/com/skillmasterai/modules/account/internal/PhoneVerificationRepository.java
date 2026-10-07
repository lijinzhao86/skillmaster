package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code phone_verification} — M1's table of codes in flight.
 *
 * <p>Keyed by the phone's blind index rather than the number, and with no foreign key to
 * {@code app_user}: at registration the user row does not exist yet, which is the whole reason a
 * code is being sent.
 */
public final class PhoneVerificationRepository {

    private final JdbcClient jdbc;

    public PhoneVerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a newly issued code and returns the row's id.
     *
     * <p>Asking for a second code retires the first one: every unconsumed row for this phone and
     * purpose is marked consumed in the same statement pair. Without that, "newest wins" would only
     * hold while the newest was unconsumed — once it was used, the older one would be picked up
     * again, and a code its owner believed had been superseded would still let somebody in.
     */
    public String insert(String phoneHash, String purpose, String codeHash, String expiresAt) {
        String id = Ulid.generate();
        String now = Timestamps.now();
        jdbc.sql("UPDATE phone_verification SET consumed_at = :now"
                        + " WHERE phone_hash = :phoneHash AND purpose = :purpose"
                        + " AND consumed_at IS NULL")
                .param("phoneHash", phoneHash)
                .param("purpose", purpose)
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO phone_verification (id, phone_hash, purpose, code_hash, attempts,"
                        + " expires_at, consumed_at, created_at)"
                        + " VALUES (:id, :phoneHash, :purpose, :codeHash, 0, :expiresAt, NULL, :now)")
                .param("id", id)
                .param("phoneHash", phoneHash)
                .param("purpose", purpose)
                .param("codeHash", codeHash)
                .param("expiresAt", expiresAt)
                .param("now", now)
                .update();
        return id;
    }

    /** The newest code for this phone and purpose that has not been accepted yet. */
    public Optional<VerificationRow> findLatestUnconsumed(String phoneHash, String purpose) {
        return jdbc.sql("SELECT id, code_hash, expires_at FROM phone_verification"
                        + " WHERE phone_hash = :phoneHash AND purpose = :purpose"
                        + " AND consumed_at IS NULL"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("phoneHash", phoneHash)
                .param("purpose", purpose)
                .query((rs, rowNum) -> new VerificationRow(
                        rs.getString("id"),
                        rs.getString("code_hash"),
                        rs.getString("expires_at")))
                .optional();
    }

    /**
     * Claims one of the code's attempts, if it has any left.
     *
     * <p>The cap is in the {@code WHERE} rather than in a caller's {@code if}, so two guesses
     * arriving together cannot both read {@code attempts = 2} and both get a third. The answer is
     * whether this call got one — the caller must not compare a code it did not pay for.
     *
     * <p>The column counts claims, not wrong guesses: a correct one claims an attempt too, which
     * costs nothing because the row is consumed immediately afterwards. The migration's comment
     * describes what the column meant when it was added and cannot be reworded — its checksum has
     * already been applied — so the meaning lives here.
     *
     * @return true when an attempt was claimed, false when the code is spent or exhausted
     */
    public boolean claimAttempt(String id, int cap) {
        return jdbc.sql("UPDATE phone_verification SET attempts = attempts + 1"
                        + " WHERE id = :id AND consumed_at IS NULL AND attempts < :cap")
                .param("id", id)
                .param("cap", cap)
                .update() > 0;
    }

    /**
     * Marks a code used. Called on expiry too, so a dead row stops being retried.
     *
     * <p>The {@code consumed_at IS NULL} in the predicate is what makes "single use" hold: of two
     * requests that read the same unconsumed row, the one that gets here second updates nothing and
     * is told so.
     *
     * @return true when this call was the one that consumed it
     */
    public boolean consume(String id, String at) {
        return jdbc.sql("UPDATE phone_verification SET consumed_at = :at"
                        + " WHERE id = :id AND consumed_at IS NULL")
                .param("id", id)
                .param("at", at)
                .update() > 0;
    }
}
