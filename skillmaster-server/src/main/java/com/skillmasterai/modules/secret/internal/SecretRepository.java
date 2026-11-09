package com.skillmasterai.modules.secret.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * M16's only SQL. Every statement names one of the four tables the module owns (rule ①,
 * {@code TableOwnershipTest}).
 *
 * <p>The ciphertext is stored as Base64 in a TEXT column, matching the rest of the schema. The
 * M16 module never names {@code skill} or {@code app_user} — the use cases translate ids into
 * addresses and handles through M12/M1.
 */
public final class SecretRepository {

    private final JdbcClient jdbc;

    public SecretRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param ciphertext Base64 of the sealer's blob */
    public void insertCredential(String id, String ownerUserId, String name, String ciphertext,
            int byteLen, int perDayLimit, String at) {
        jdbc.sql("""
                INSERT INTO service_credential
                    (id, owner_user_id, name, ciphertext, byte_len, per_day_limit, created_at)
                VALUES (:id, :owner, :name, :ciphertext, :byteLen, :perDayLimit, :at)
                """)
                .param("id", id)
                .param("owner", ownerUserId)
                .param("name", name)
                .param("ciphertext", ciphertext)
                .param("byteLen", byteLen)
                .param("perDayLimit", perDayLimit)
                .param("at", at)
                .update();
    }

    public Optional<StoredCredential> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM service_credential WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> credential(rs))
                .optional();
    }

    public List<StoredCredential> listByOwner(String ownerUserId, String skillIdFilter) {
        String sql = "SELECT " + PREFIXED_COLUMNS + " FROM service_credential c";
        if (skillIdFilter != null) {
            // **`EXISTS`, not a join.** One credential may be bound to several variables of the same
            // skill; a join would return it once per binding and the listing would show the same
            // credential twice.
            sql += " WHERE c.owner_user_id = :owner AND EXISTS ("
                    + " SELECT 1 FROM credential_binding b"
                    + " WHERE b.credential_id = c.id AND b.skill_id = :skill)"
                    + " ORDER BY c.created_at DESC, c.id";
        } else {
            sql += " WHERE c.owner_user_id = :owner ORDER BY c.created_at DESC, c.id";
        }
        var spec = jdbc.sql(sql).param("owner", ownerUserId);
        if (skillIdFilter != null) {
            spec = spec.param("skill", skillIdFilter);
        }
        return spec.query((rs, rowNum) -> credential(rs)).list();
    }

    public boolean existsByName(String ownerUserId, String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM service_credential"
                        + " WHERE owner_user_id = :owner AND name = :name)")
                .param("owner", ownerUserId)
                .param("name", name)
                .query(Boolean.class)
                .single();
    }

    public int updateValue(String id, String ciphertext, int byteLen, String at) {
        return jdbc.sql("""
                UPDATE service_credential
                   SET ciphertext = :ciphertext, byte_len = :byteLen, rotated_at = :at
                 WHERE id = :id
                """)
                .param("id", id)
                .param("ciphertext", ciphertext)
                .param("byteLen", byteLen)
                .param("at", at)
                .update();
    }

    public int updateLimit(String id, int perDayLimit) {
        return jdbc.sql("UPDATE service_credential SET per_day_limit = :limit WHERE id = :id")
                .param("id", id)
                .param("limit", perDayLimit)
                .update();
    }

    public int delete(String id) {
        return jdbc.sql("DELETE FROM service_credential WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Removes every credential this person owned (v56's account purge).
     *
     * <p>Bindings, uses and shares follow through their foreign keys. The account row survives the
     * purge, so the {@code ON DELETE CASCADE} from {@code app_user} never fires — this is the
     * statement that stands in for it.
     */
    public int deleteAllFor(String ownerUserId) {
        return jdbc.sql("DELETE FROM service_credential WHERE owner_user_id = :id")
                .param("id", ownerUserId)
                .update();
    }

    /** Shares of other people's credentials addressed to this person (v56's account purge). */
    public int deleteSharesFor(String granteeUserId) {
        return jdbc.sql("DELETE FROM credential_share WHERE grantee_user_id = :id")
                .param("id", granteeUserId)
                .update();
    }

    public int usedToday(String credentialId, String day) {
        return jdbc.sql("SELECT count FROM credential_use"
                        + " WHERE credential_id = :id AND day = :day")
                .param("id", credentialId)
                .param("day", day)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /**
     * Adds one lease to today's row and returns the new count.
     *
     * <p>The quota check is in the {@code DO UPDATE}'s {@code WHERE}, so the read and the write are
     * one statement: two concurrent leases cannot both observe the last slot. Empty means the
     * limit has already been reached (or the credential vanished mid-transaction).
     */
    public Optional<Integer> incrementUse(String credentialId, String day, String at) {
        return jdbc.sql("""
                INSERT INTO credential_use (credential_id, day, count, last_used_at)
                VALUES (:id, :day, 1, :at)
                ON CONFLICT (credential_id, day) DO UPDATE
                   SET count = credential_use.count + 1, last_used_at = EXCLUDED.last_used_at
                 WHERE credential_use.count
                       < (SELECT per_day_limit FROM service_credential WHERE id = :id)
                RETURNING count
                """)
                .param("id", credentialId)
                .param("day", day)
                .param("at", at)
                .query(Integer.class)
                .optional();
    }

    public void touchLastUsed(String credentialId, String at) {
        jdbc.sql("UPDATE service_credential SET last_used_at = :at WHERE id = :id")
                .param("id", credentialId)
                .param("at", at)
                .update();
    }

    public void insertBinding(String id, String credentialId, String ownerUserId, String skillId,
            String envName, String at) {
        jdbc.sql("""
                INSERT INTO credential_binding
                    (id, credential_id, owner_user_id, skill_id, env_name, created_at)
                VALUES (:id, :credential, :owner, :skill, :env, :at)
                """)
                .param("id", id)
                .param("credential", credentialId)
                .param("owner", ownerUserId)
                .param("skill", skillId)
                .param("env", envName)
                .param("at", at)
                .update();
    }

    public List<StoredBinding> bindingsOf(String credentialId) {
        return jdbc.sql("SELECT " + BINDING_COLUMNS + " FROM credential_binding"
                        + " WHERE credential_id = :credential ORDER BY created_at, id")
                .param("credential", credentialId)
                .query((rs, rowNum) -> binding(rs))
                .list();
    }

    public List<StoredBinding> bindingsOfSkill(String credentialId, String skillId) {
        return jdbc.sql("SELECT " + BINDING_COLUMNS + " FROM credential_binding"
                        + " WHERE credential_id = :credential AND skill_id = :skill"
                        + " ORDER BY created_at, id")
                .param("credential", credentialId)
                .param("skill", skillId)
                .query((rs, rowNum) -> binding(rs))
                .list();
    }

    public int deleteBinding(String credentialId, String skillId, String envName) {
        return jdbc.sql("DELETE FROM credential_binding"
                        + " WHERE credential_id = :credential AND skill_id = :skill"
                        + " AND env_name = :env")
                .param("credential", credentialId)
                .param("skill", skillId)
                .param("env", envName)
                .update();
    }

    /** Whether this owner already exposes any credential through this skill and variable. */
    public boolean hasOwnBinding(String ownerUserId, String skillId, String envName) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                      FROM credential_binding b
                      JOIN service_credential c ON c.id = b.credential_id
                     WHERE c.owner_user_id = :owner
                       AND b.skill_id = :skill
                       AND b.env_name = :env)
                """)
                .param("owner", ownerUserId)
                .param("skill", skillId)
                .param("env", envName)
                .query(Boolean.class)
                .single();
    }

    /** The user's own bindings on this skill. */
    public List<StoredBinding> ownBindings(String userId, String skillId) {
        return jdbc.sql("""
                SELECT b.id, b.credential_id, b.skill_id, b.env_name, b.created_at
                  FROM credential_binding b
                  JOIN service_credential c ON c.id = b.credential_id
                 WHERE b.skill_id = :skill AND c.owner_user_id = :user
                 ORDER BY b.created_at, b.id
                """)
                .param("skill", skillId)
                .param("user", userId)
                .query((rs, rowNum) -> binding(rs))
                .list();
    }

    /**
     * Bindings this user receives through a live share on this skill.
     *
     * <p><strong>The unit is the (credential, skill) grant, not the row that was written.</strong>
     * A share is expressed as "this credential, on this skill, for this person"; a variable bound to
     * the same skill afterwards is part of the same grant. Resolving by the shared binding's
     * credential — not by its id — is what makes that true without a second share row.
     */
    public List<StoredBinding> sharedBindings(String userId, String skillId) {
        return jdbc.sql("""
                SELECT b.id, b.credential_id, b.skill_id, b.env_name, b.created_at
                  FROM credential_binding b
                 WHERE b.skill_id = :skill
                   AND EXISTS (
                       SELECT 1 FROM credential_share s
                        WHERE s.credential_id = b.credential_id
                          AND s.skill_id = :skill
                          AND s.grantee_user_id = :user
                          AND s.revoked_at IS NULL)
                 ORDER BY b.created_at, b.id
                """)
                .param("skill", skillId)
                .param("user", userId)
                .query((rs, rowNum) -> binding(rs))
                .list();
    }

    /**
     * The environment variables this user would receive for this skill.
     *
     * <p>**Names, not a boolean.** The metadata answer needs two things at once — "would a lease
     * return anything" and "which declared variables are still unbound" — and one statement can
     * answer both. It used to be {@code EXISTS}; the declaration prompt in v52's P2 is what made the
     * names worth carrying.
     *
     * @param includeShared whether a live share counts. The caller answers it from M12's access
     *                      answer, so the metadata and the lease cannot disagree
     */
    public List<String> boundEnvNames(String userId, String skillId, boolean includeShared) {
        // Own bindings always count; shared ones only when the grant is still worth honouring.
        // **The share rule is the lease's**: a grant is on (credential, skill), so a variable bound
        // after the share is part of it.
        String sql = "SELECT DISTINCT b.env_name FROM credential_binding b"
                + " JOIN service_credential c ON c.id = b.credential_id"
                + " WHERE b.skill_id = :skill AND c.owner_user_id = :user";
        if (includeShared) {
            sql += " UNION SELECT b.env_name FROM credential_binding b"
                    + " WHERE b.skill_id = :skill AND EXISTS ("
                    + " SELECT 1 FROM credential_share s"
                    + " WHERE s.credential_id = b.credential_id AND s.skill_id = :skill"
                    + " AND s.grantee_user_id = :user AND s.revoked_at IS NULL)";
        }
        return jdbc.sql(sql)
                .param("skill", skillId)
                .param("user", userId)
                .query(String.class)
                .list();
    }

    /**
     * The variables this user receives from other people's credentials on one skill.
     *
     * <p>The same rule as {@link #sharedBindings}: a grant is on (credential, skill), so a variable
     * bound after the share is part of it. This read exists for the console's skill pane, which has
     * to show 「共享自 somebody」 beside a variable — a list of only one's own credentials would leave
     * the pane silent about why a skill works.
     */
    public List<IncomingRow> incoming(String userId, String skillId, String day) {
        return jdbc.sql("""
                SELECT DISTINCT c.id AS credential_id, c.name, c.owner_user_id, b.skill_id,
                                b.env_name, c.per_day_limit AS per_day,
                                COALESCE(u.count, 0) AS used
                  FROM credential_binding b
                  JOIN service_credential c ON c.id = b.credential_id
                  LEFT JOIN credential_use u ON u.credential_id = c.id AND u.day = :day
                 WHERE b.skill_id = :skill
                   AND EXISTS (
                       SELECT 1 FROM credential_share s
                        WHERE s.credential_id = b.credential_id
                          AND s.skill_id = :skill
                          AND s.grantee_user_id = :user
                          AND s.revoked_at IS NULL)
                 ORDER BY b.env_name, c.name
                """)
                .param("skill", skillId)
                .param("user", userId)
                .param("day", day)
                .query((rs, rowNum) -> new IncomingRow(
                        rs.getString("credential_id"),
                        rs.getString("name"),
                        rs.getString("owner_user_id"),
                        rs.getString("skill_id"),
                        rs.getString("env_name"),
                        rs.getInt("per_day"),
                        rs.getInt("used")))
                .list();
    }

    public void insertShare(String id, String credentialId, String skillId, String granteeUserId,
            String grantedBy, String at) {
        jdbc.sql("""
                INSERT INTO credential_share
                    (id, credential_id, skill_id, grantee_user_id, granted_by, created_at)
                VALUES (:id, :credential, :skill, :grantee, :grantedBy, :at)
                """)
                .param("id", id)
                .param("credential", credentialId)
                .param("skill", skillId)
                .param("grantee", granteeUserId)
                .param("grantedBy", grantedBy)
                .param("at", at)
                .update();
    }

    public boolean activeShareExists(String credentialId, String skillId, String granteeUserId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM credential_share s
                     WHERE s.credential_id = :credential
                       AND s.skill_id = :skill
                       AND s.grantee_user_id = :grantee
                       AND s.revoked_at IS NULL)
                """)
                .param("credential", credentialId)
                .param("skill", skillId)
                .param("grantee", granteeUserId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Who receives this credential, and through which variables.
     *
     * <p><strong>It expands the grant to the variables that exist now</strong>, which is exactly what
     * the lease hands over — a listing that showed only the variable the share was created with
     * would under-report what the grantee can use.
     */
    public List<ShareRow> shares(String credentialId, String skillIdFilter) {
        String sql = "SELECT DISTINCT s.grantee_user_id, b.skill_id, b.env_name, s.created_at"
                + " FROM credential_share s"
                + " JOIN credential_binding b ON b.credential_id = s.credential_id"
                + "   AND b.skill_id = s.skill_id"
                + " WHERE s.credential_id = :credential AND s.revoked_at IS NULL";
        if (skillIdFilter != null) {
            sql += " AND s.skill_id = :skill";
        }
        sql += " ORDER BY s.created_at DESC, s.grantee_user_id, b.env_name";
        var spec = jdbc.sql(sql).param("credential", credentialId);
        if (skillIdFilter != null) {
            spec = spec.param("skill", skillIdFilter);
        }
        return spec.query((rs, rowNum) -> new ShareRow(
                        rs.getString("grantee_user_id"),
                        rs.getString("skill_id"),
                        rs.getString("env_name"),
                        rs.getString("created_at")))
                .list();
    }

    public int revokeShares(String credentialId, String skillId, String granteeUserId, String at) {
        return jdbc.sql("""
                UPDATE credential_share
                   SET revoked_at = :at
                 WHERE credential_id = :credential
                   AND skill_id = :skill
                   AND grantee_user_id = :grantee
                   AND revoked_at IS NULL
                """)
                .param("at", at)
                .param("credential", credentialId)
                .param("skill", skillId)
                .param("grantee", granteeUserId)
                .update();
    }

    private static final String COLUMNS =
            "id, owner_user_id, name, ciphertext, byte_len, per_day_limit,"
                    + " created_at, rotated_at, last_used_at";

    private static final String PREFIXED_COLUMNS =
            "c.id, c.owner_user_id, c.name, c.ciphertext, c.byte_len, c.per_day_limit,"
                    + " c.created_at, c.rotated_at, c.last_used_at";

    private static final String BINDING_COLUMNS =
            "id, credential_id, skill_id, env_name, created_at";

    private static StoredCredential credential(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredCredential(
                rs.getString("id"),
                rs.getString("owner_user_id"),
                rs.getString("name"),
                rs.getString("ciphertext"),
                rs.getInt("byte_len"),
                rs.getInt("per_day_limit"),
                rs.getString("created_at"),
                rs.getString("rotated_at"),
                rs.getString("last_used_at"));
    }

    private static StoredBinding binding(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredBinding(
                rs.getString("id"),
                rs.getString("credential_id"),
                rs.getString("skill_id"),
                rs.getString("env_name"),
                rs.getString("created_at"));
    }

    /** One {@code service_credential} row, ciphertext included — never outside this module. */
    public record StoredCredential(String id, String ownerUserId, String name, String ciphertext,
            int byteLen, int perDayLimit, String createdAt, String rotatedAt, String lastUsedAt) {
    }

    /** One {@code credential_binding} row. */
    public record StoredBinding(String id, String credentialId, String skillId, String envName,
            String createdAt) {
    }

    /** One live {@code credential_share} joined to its binding's skill and variable. */
    public record ShareRow(String granteeUserId, String skillId, String envName, String createdAt) {
    }

    /** One variable a grantee receives from another owner's credential. */
    public record IncomingRow(String credentialId, String name, String ownerUserId, String skillId,
            String envName, int perDay, int used) {
    }
}
