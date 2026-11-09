package com.skillmasterai.modules.account.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.account.AccountDirectory;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code app_user} — M1's table.
 *
 * <p>Public but confined to {@code internal}; see
 * {@code ArchitectureTest.nothingOutsideAModuleMayReferenceAnotherModulesInternals}.
 *
 * <p>Registration and lookup both go through the phone's blind index rather than the number, so the
 * value this class is handed is already a keyed hash and the number itself never reaches SQL.
 */
public final class AccountRepository implements AccountDirectory {

    /**
     * The one status that means "may act". Spelled here as well as in {@code AccountService} because
     * M1 answers the question in two places — this module's own login, and, since v2, the request
     * path — and the column is the database's fact rather than a wire value, so there is nowhere
     * shared to put it. Two spellings inside one module is the amount of duplication the design
     * tolerates; a third is a sign it should move.
     */
    private static final String ACTIVE = "active";

    /** The third status (v56). Kept beside {@link #ACTIVE} for the same reason that one is here. */
    static final String DELETED = "deleted";

    /** What a listing says instead of a deleted account's handle. */
    static final String DELETED_DISPLAY_NAME = "已注销账号";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> handleOf(String userId) {
        return jdbc.sql("SELECT handle FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> userIdOf(String handle) {
        return jdbc.sql("SELECT id FROM app_user WHERE handle = :handle")
                .param("handle", handle)
                .query(String.class)
                .optional();
    }

    @Override
    public java.util.Map<String, String> handlesOf(java.util.Collection<String> userIds) {
        if (userIds.isEmpty()) {
            // No statement at all: `= ANY(ARRAY[])` is valid and matches nothing, but skipping it
            // keeps the empty case from being a query whose result a reader has to reason about.
            return java.util.Map.of();
        }
        // **A deleted account is shown as 「已注销账号」, not as its placeholder handle** (v56,
        // ADR 0175). This is a listing for humans — grants, histories, authors — so it answers what
        // to show them, not what to address internally. `handleOf` keeps returning the real handle
        // (and, after the purge, `deleted-<id>`) because callers use it to resolve namespaces.
        return jdbc.sql("SELECT id, handle, status FROM app_user WHERE id = ANY(:ids)")
                .param("ids", userIds.toArray(String[]::new))
                .query((rs, rowNum) -> java.util.Map.entry(rs.getString("id"),
                        DELETED.equals(rs.getString("status"))
                                ? DELETED_DISPLAY_NAME
                                : rs.getString("handle")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
    }

    /**
     * Whether this account may act — the one question about {@code status} that leaves this module.
     *
     * <p>A boolean rather than the value itself: {@link AccountRow} explains why the column does not
     * travel, and the shape of this answer is what keeps that true while still letting the request
     * path refuse a suspended account's credential (ADR 0038 §决定 5).
     *
     * <p>One query, and it is not joined to anything: the credential's own row has already been
     * found by its hash, so this asks about the account that row names.
     */
    @Override
    public boolean isActive(String userId) {
        return jdbc.sql("SELECT status FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .map(ACTIVE::equals)
                .orElse(false);
    }

    /**
     * Whether this username is taken.
     *
     * <p>A courtesy check so a registration can say "that name is taken" before spending anything,
     * not the guard. The guard is {@code UNIQUE(handle)} — and {@code UNIQUE(namespace.slug)},
     * which is what keeps a reserved name out of reach even if the user row were not there.
     */
    public boolean handleExists(String handle) {
        return jdbc.sql("SELECT 1 FROM app_user WHERE handle = :handle")
                .param("handle", handle)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * The account that owns this phone number, if one does.
     *
     * @param phoneHash the keyed blind index, never the number
     */
    public Optional<AccountRow> findByPhoneHash(String phoneHash) {
        return jdbc.sql("SELECT id, handle, status FROM app_user WHERE phone_hash = :hash")
                .param("hash", phoneHash)
                .query((rs, rowNum) -> new AccountRow(
                        rs.getString("id"), rs.getString("handle"), rs.getString("status")))
                .optional();
    }

    /**
     * Stops the account acting and opens its retention window (v56).
     *
     * <p>Conditional on {@code status = 'active'} so a second request — a double click, a retry —
     * leaves the original {@code deleted_at} alone. The window starts at the first deletion, which
     * is the truthful one.
     */
    boolean markDeleted(String userId, String at) {
        return jdbc.sql("UPDATE app_user SET status = :deleted, deleted_at = :at"
                        + " WHERE id = :id AND status = :active")
                .param("deleted", DELETED)
                .param("at", at)
                .param("id", userId)
                .param("active", ACTIVE)
                .update() > 0;
    }

    /**
     * Accounts whose window ended at or before {@code cutoff} and that have not been purged yet.
     *
     * <p><strong>The {@code purged_at IS NULL} is load-bearing.</strong> The purge leaves
     * {@code status = 'deleted'} and the original {@code deleted_at}, so without it every future
     * run would select the same rows again: an unbounded audit trail, a global blob-sweep lock taken
     * once per old account per day, and a log line claiming work that no longer exists.
     */
    java.util.List<String> dueForPurge(String cutoff) {
        return jdbc.sql("SELECT id FROM app_user"
                        + " WHERE status = :deleted AND deleted_at <= :cutoff"
                        + " AND purged_at IS NULL"
                        + " ORDER BY deleted_at")
                .param("deleted", DELETED)
                .param("cutoff", cutoff)
                .query(String.class)
                .list();
    }

    /**
     * Claims the account for purging: {@code purged_at} moves from null exactly once.
     *
     * <p>This is the cross-instance lock. The daily job runs on every instance, and two of them
     * could otherwise list the same due row together.
     */
    boolean claimForPurge(String userId, String at) {
        return jdbc.sql("UPDATE app_user SET purged_at = :at"
                        + " WHERE id = :id AND purged_at IS NULL AND status = :deleted")
                .param("at", at)
                .param("id", userId)
                .param("deleted", DELETED)
                .update() > 0;
    }

    /** Whether this account is in the deleted state — asked by the delete use case on a refusal. */
    boolean isDeleted(String userId) {
        return jdbc.sql("SELECT 1 FROM app_user WHERE id = :id AND status = :deleted")
                .param("id", userId)
                .param("deleted", DELETED)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** The phone's blind index, so the purge can delete the codes sent to it. */
    Optional<String> phoneHashOf(String userId) {
        return jdbc.sql("SELECT phone_hash FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    /**
     * Rewrites the row so nothing in it is about a person any more, while keeping its id.
     *
     * <p>The handle becomes {@code deleted-<id>} because the column is {@code UNIQUE NOT NULL} and
     * the original must be released for the 30-day window to end with a registrable name. The
     * placeholder can never collide: ids are ULIDs.
     */
    void deidentify(String userId) {
        jdbc.sql("UPDATE app_user SET phone_hash = NULL, phone_enc = NULL, email = NULL,"
                        + " handle = 'deleted-' || id, display_name = :displayName"
                        + " WHERE id = :id")
                .param("displayName", DELETED_DISPLAY_NAME)
                .param("id", userId)
                .update();
    }

    /**
     * Creates a user row, unless a UNIQUE constraint says it already exists.
     *
     * <p>Both phone columns or neither: the table's {@code app_user_phone_paired} check refuses
     * anything else, and the caller always has both because it derived them together.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than letting the constraint raise: a violation
     * aborts the whole PostgreSQL transaction, and the caller needs to run one more query to say
     * <em>which</em> column the request collided on. Answering that from a dead transaction is
     * impossible, so the conflict has to be absorbed rather than caught.
     *
     * @return the ULID it generated, or empty when a row with this handle or phone already existed
     */
    public Optional<String> insert(String handle, String phoneHash, String phoneEnc,
            String displayName) {
        String id = Ulid.generate();
        return jdbc.sql("INSERT INTO app_user (id, handle, phone_hash, phone_enc, display_name,"
                        + " status, created_at)"
                        + " VALUES (:id, :handle, :phoneHash, :phoneEnc, :displayName, 'active',"
                        + " :createdAt)"
                        + " ON CONFLICT DO NOTHING"
                        + " RETURNING id")
                .param("id", id)
                .param("handle", handle)
                .param("phoneHash", phoneHash)
                .param("phoneEnc", phoneEnc)
                .param("displayName", displayName)
                .param("createdAt", Timestamps.now())
                .query(String.class)
                .optional();
    }
}
