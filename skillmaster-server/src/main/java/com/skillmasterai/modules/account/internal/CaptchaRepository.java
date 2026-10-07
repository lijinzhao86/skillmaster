package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code captcha} — M1's table of challenges in flight.
 *
 * <p>Looked up by the id the client was handed, never by a value the caller supplies, so the primary
 * key is the only index this table needs.
 */
public final class CaptchaRepository {

    /** Longer than a challenge lives, so a sweep never removes one a caller could still answer. */
    private static final Duration KEEP_FOR = Duration.ofHours(1);

    private final JdbcClient jdbc;

    public CaptchaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String insert(String answerHash, String expiresAt) {
        String id = Ulid.generate();
        jdbc.sql("INSERT INTO captcha (id, answer_hash, attempts, expires_at, consumed_at, created_at)"
                        + " VALUES (:id, :answerHash, 0, :expiresAt, NULL, :now)")
                .param("id", id)
                .param("answerHash", answerHash)
                .param("expiresAt", expiresAt)
                .param("now", Timestamps.now())
                .update();
        sweep();
        return id;
    }

    public Optional<CaptchaRow> find(String id) {
        return jdbc.sql("SELECT id, answer_hash, expires_at FROM captcha"
                        + " WHERE id = :id AND consumed_at IS NULL")
                .param("id", id)
                .query((rs, rowNum) -> new CaptchaRow(
                        rs.getString("id"),
                        rs.getString("answer_hash"),
                        rs.getString("expires_at")))
                .optional();
    }

    /**
     * Claims one of the challenge's attempts, if it has any left.
     *
     * <p>The cap is in the {@code WHERE} rather than in a caller's {@code if}, so two requests
     * arriving together cannot both read {@code attempts = 2} and both get a third guess. The
     * answer is whether this call got one — the caller must not compare an answer it did not pay
     * for.
     *
     * <p>The column counts claims, not wrong answers: a correct one claims an attempt too, which
     * costs nothing because the row is consumed immediately afterwards. The migration's comment
     * describes what the column meant when it was added and cannot be reworded — its checksum has
     * already been applied — so the meaning lives here.
     *
     * @return true when an attempt was claimed, false when the challenge is spent or exhausted
     */
    public boolean claimAttempt(String id, int cap) {
        return jdbc.sql("UPDATE captcha SET attempts = attempts + 1"
                        + " WHERE id = :id AND consumed_at IS NULL AND attempts < :cap")
                .param("id", id)
                .param("cap", cap)
                .update() > 0;
    }

    /**
     * Marks a challenge used. Called on expiry too, so a dead row stops being retried.
     *
     * <p>The {@code consumed_at IS NULL} in the predicate is what makes "single use" hold: of two
     * requests that read the same unconsumed row, the one that gets here second updates nothing and
     * is told so.
     *
     * @return true when this call was the one that consumed it
     */
    public boolean consume(String id, String at) {
        return jdbc.sql("UPDATE captcha SET consumed_at = :at WHERE id = :id AND consumed_at IS NULL")
                .param("id", id)
                .param("at", at)
                .update() > 0;
    }

    /**
     * Deletes rows nobody can answer any more.
     *
     * <p>This table is the one an attacker can add to most cheaply — issuing a challenge costs
     * nothing and needs no account, so its rate limit is the only thing bounding the row count, and
     * a limit of any size still grows a table that is never trimmed. Opportunistic rather than
     * scheduled, like the throttle counters: a delete on the path these requests already take is
     * cheaper than a thread.
     */
    private void sweep() {
        jdbc.sql("DELETE FROM captcha WHERE expires_at < :cutoff")
                .param("cutoff", Timestamps.format(Instant.now().minus(KEEP_FOR)))
                .update();
    }
}
