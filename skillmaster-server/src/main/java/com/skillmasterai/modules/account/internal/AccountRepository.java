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
        return jdbc.sql("SELECT id, handle FROM app_user WHERE id = ANY(:ids)")
                .param("ids", userIds.toArray(String[]::new))
                .query((rs, rowNum) -> java.util.Map.entry(rs.getString("id"), rs.getString("handle")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
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
