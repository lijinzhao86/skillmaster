package com.skillmasterai.modules.namespace.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.namespace.Namespace;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and writes {@code namespace} and {@code namespace_member} — the only tables M4 owns.
 *
 * <p>Public but confined to this module's {@code internal} package; see the architecture test.
 * P0 reads only. Creating a namespace belongs with registration, which is P1 (M1): the seeded
 * identities in {@code V2__seed_owner_and_namespaces.sql} are the only ones that exist.
 */
public final class NamespaceRepository {

    private static final String COLUMNS = "id, slug, title, owner_user_id, visibility";

    private final JdbcClient jdbc;

    public NamespaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the namespace a user's skills live in, and the membership row that says they own it.
     *
     * <p>Slug is the handle (§3.2), which is what makes {@code UNIQUE(namespace_id, name)} cover
     * both "no duplicates among my own skills" and, later, "no duplicates in a team's" without a
     * migration.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a raising insert, for the reason the account
     * registry gives: a constraint violation aborts the transaction, and the caller may still need
     * to run a query to say which field the request collided on.
     *
     * @return false when the slug is already taken. What that means for the request is the caller's
     *         to decide — the slug is the username, so it is a fact about the username
     */
    public boolean createPersonalNamespace(String ownerUserId, String handle) {
        String id = Ulid.generate();
        String now = Timestamps.now();
        int inserted = jdbc.sql("INSERT INTO namespace (id, slug, title, owner_user_id, visibility,"
                        + " created_at) VALUES (:id, :slug, :slug, :owner, 'private', :now)"
                        + " ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("slug", handle)
                .param("owner", ownerUserId)
                .param("now", now)
                .update();
        if (inserted == 0) {
            return false;
        }
        // Only when the namespace was created: a membership row pointing at a namespace that
        // already belonged to somebody else would be worse than the conflict it came from.
        jdbc.sql("INSERT INTO namespace_member (namespace_id, user_id, role, added_at)"
                        + " VALUES (:id, :user, 'owner', :now)")
                .param("id", id)
                .param("user", ownerUserId)
                .param("now", now)
                .update();
        return true;
    }

    /**
     * The namespace a user owns under a given slug — for the personal namespace, the slug is the
     * user's handle.
     *
     * <p>The handle is a parameter rather than a join against {@code app_user}: that table belongs
     * to M1, and §2.5 rule 1 says a module reads only its own. The caller resolves the handle
     * through {@link com.skillmasterai.modules.account.AccountDirectory} and passes it in, which is
     * also what keeps "personal namespace means slug equals handle" a rule of one place rather
     * than a SQL join that has to be repeated wherever a namespace is looked up.
     */
    public Optional<Namespace> findByOwnerAndSlug(String userId, String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM namespace"
                        + " WHERE owner_user_id = :userId AND slug = :slug")
                .param("userId", userId)
                .param("slug", slug)
                .query((rs, rowNum) -> new Namespace(
                        rs.getString("id"),
                        rs.getString("slug"),
                        rs.getString("title"),
                        rs.getString("owner_user_id"),
                        rs.getString("visibility")))
                .optional();
    }

    /** A namespace by its slug. Unique by constraint, so at most one row. */
    /**
     * Slugs for a batch of ids.
     *
     * <p>One statement rather than a loop: a listing asks for the distinct namespaces on the page
     * it just fetched, and looping would make the round trips grow with the number of people who
     * have shared something with the caller.
     */
    public java.util.Map<String, String> slugsOf(java.util.Collection<String> namespaceIds) {
        return jdbc.sql("SELECT id, slug FROM namespace WHERE id = ANY(:ids)")
                .param("ids", namespaceIds.toArray(String[]::new))
                .query((rs, rowNum) -> java.util.Map.entry(rs.getString("id"), rs.getString("slug")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
    }

    public Optional<Namespace> findBySlug(String slug) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM namespace WHERE slug = :slug")
                .param("slug", slug)
                .query((rs, rowNum) -> new Namespace(
                        rs.getString("id"), rs.getString("slug"), rs.getString("title"),
                        rs.getString("owner_user_id"), rs.getString("visibility")))
                .optional();
    }

    public Optional<Namespace> findById(String namespaceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM namespace WHERE id = :id")
                .param("id", namespaceId)
                .query((rs, rowNum) -> new Namespace(
                        rs.getString("id"), rs.getString("slug"), rs.getString("title"),
                        rs.getString("owner_user_id"), rs.getString("visibility")))
                .optional();
    }
}
