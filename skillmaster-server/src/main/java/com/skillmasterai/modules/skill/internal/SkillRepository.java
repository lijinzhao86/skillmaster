package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.VersionProjection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes {@code skill} — one of the three tables M12 owns (ADR 0053). */
public final class SkillRepository {

    private final JdbcClient jdbc;
    private final NeverPublishedDelete neverPublishedDelete;

    public SkillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.neverPublishedDelete = new NeverPublishedDelete(jdbc);
    }

    /**
     * Creates the skill if the name is new, and returns its id either way. Never changes an
     * existing skill.
     *
     * <p><strong>It returns the existing row without touching it, and that is the point.</strong>
     * The metadata a submission carries belongs to the version it created; the skill row's copy is
     * written only when a version is published (ADR 0031). Doing otherwise would make a submission
     * change the title and description that search shows on the consumption plane while the content
     * behind them stayed the old version — which is the thing the split exists to prevent.
     *
     * <p>The no-op {@code DO UPDATE} is not decoration. It is what makes this statement take the
     * {@code skill} row's lock even when nothing needs updating, and that lock is what serialises
     * number allocation across concurrent submissions — see
     * {@code VersionRepository#insertIfAbsent}, which depends on it and says so.
     *
     * <p>Two deliberate omissions, both kept from the statement this replaces:
     * <ul>
     *   <li><strong>{@code visibility} is never touched.</strong> §4.3 gives metadata its own
     *       endpoint; a submission that reset visibility would be a way to make a private skill
     *       public by accident.</li>
     *   <li><strong>{@code deleted_at} is not cleared</strong>, enforced by the {@code WHERE}. A
     *       soft-deleted row is not a match for the conflict target (v61's partial index covers
     *       live rows only), so the insert creates a new live skill and the tombstone stays where
     *       it is (ADR 0183).</li>
     * </ul>
     *
     * @return the skill's id, whether it was created or already live
     */
    public Optional<String> findOrCreate(String namespaceId, SkillMetadata metadata,
            String createdBy, String at) {
        lockName(namespaceId, metadata.name());
        return jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, :namespaceId, :name, :title, :description, :frontmatter,
                        'private', :createdBy, :at, :at)
                ON CONFLICT (namespace_id, name) WHERE deleted_at IS NULL DO UPDATE
                   SET name = skill.name
                 WHERE skill.deleted_at IS NULL
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("namespaceId", namespaceId)
                .param("name", metadata.name())
                .param("title", metadata.title())
                .param("description", metadata.description())
                .param("frontmatter", metadata.frontmatterJson())
                .param("createdBy", createdBy)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * Creates the skill and reports its id, or nothing when a live skill already holds the name
     * (ADR 0070). **A soft-deleted row does not block it** (ADR 0183): the partial unique index
     * only covers live rows.
     *
     * <p><strong>{@code DO NOTHING}, not {@code DO UPDATE}.</strong> The upsert in
     * {@link #findOrCreate} exists to take the row's lock and return the existing id; a create
     * that reused it could never say "the name is taken", which is the answer this path exists to
     * give. The partial unique index — live rows only since v61 — is the arbiter, so the
     * {@code ON CONFLICT} target has to name the same predicate.
     */
    public Optional<String> insertNew(String namespaceId, SkillMetadata metadata,
            String createdBy, String at) {
        lockName(namespaceId, metadata.name());
        return jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, :namespaceId, :name, :title, :description, :frontmatter,
                        'private', :createdBy, :at, :at)
                ON CONFLICT (namespace_id, name) WHERE deleted_at IS NULL DO NOTHING
                RETURNING id
                """)
                .param("id", Ulid.generate())
                .param("namespaceId", namespaceId)
                .param("name", metadata.name())
                .param("title", metadata.title())
                .param("description", metadata.description())
                .param("frontmatter", metadata.frontmatterJson())
                .param("createdBy", createdBy)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /**
     * The row with this name whether or not it is soft-deleted — enough for {@code idOf}, whose
     * caller only needs identity. A live row wins over a tombstone when both exist (v61 allows
     * that); otherwise the newest tombstone is the answer.
     */
    public Optional<AnyRow> findAnyByName(String namespaceId, String name) {
        return jdbc.sql("""
                SELECT id, deleted_at FROM skill
                 WHERE namespace_id = :namespaceId AND name = :name
                 ORDER BY (deleted_at IS NULL) DESC, deleted_at DESC
                 LIMIT 1
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query((rs, rowNum) -> new AnyRow(rs.getString("id"), rs.getString("deleted_at")))
                .optional();
    }

    /** @param deletedAt null for a live row */
    public record AnyRow(String id, String deletedAt) {
    }

    /**
     * Makes a version the one consumers get, and writes every fact the lists read from the row.
     *
     * <p><strong>This is M12's write side of the seam (ADR 0053).</strong> The version's own values
     * arrive in a {@link VersionProjection}; the draft-side facts arrive in a
     * {@link DraftProjection} that M7 computed from {@code skill_version} — M12 may name only its
     * own tables, so neither is read here. This is the one statement that moves the pointer, which
     * makes it the only writer of {@code current_version_id} and of the current-version projection.
     *
     * <p><strong>{@code skill.title/description/frontmatter} are a projection of whichever version
     * is current</strong> (ADR 0031), so they move when the pointer moves and at no other time. The
     * draft side is recomputed in the same breath because the version being published has just left
     * the draft set.
     *
     * <p><strong>The pointer may move backwards.</strong> Publishing a version that is already
     * published is rollback: an author who wants an earlier version back re-publishes it, because
     * reverting the source re-submits the same digest (ADR 0005 makes that a no-op) and so cannot
     * move what is served. A rollback also moves
     * {@code current_version_submitted_at} back with the pointer, which is what the list's
     * "更新于" means.
     *
     * <p><strong>{@code IS DISTINCT FROM} is what makes the return value mean something.</strong>
     * Without it an update that sets the value the row already holds still counts as one row, so a
     * request that lost a race to publish the same version would report that it moved the pointer
     * and write an audit row for a change it did not make. With it, zero rows is "the pointer
     * already named this version", and nothing is written — not the timestamp, not the projected
     * metadata, and not the caller's trail.
     *
     * <p><strong>Since v37 zero rows has a second cause: the skill row is soft-deleted.</strong>
     * The {@code WHERE} carries {@code deleted_at IS NULL}, so a publish that lost the row to a
     * delete matches nothing. The caller must tell the two apart with {@code skillDeleted} and roll
     * the publish back (ADR 0127) — treating it as "unchanged" would commit a live version on a
     * skill nobody can reach.
     *
     * <p>{@code when_to_use} is extracted from the frontmatter here rather than passed in because
     * the key lives in a document M12 stores whole. The fallback to the top-level key keeps rows
     * written before V21 readable under the same rule the backfill used.
     *
     * @return whether the pointer actually moved
     */
    public boolean recordPublish(String skillId, VersionProjection version, DraftProjection drafts,
            String at) {
        return jdbc.sql("""
                UPDATE skill
                   SET ever_published_at = COALESCE(ever_published_at, :at),
                       current_version_id = :versionId, updated_at = :at,
                       title = :title, description = :description, frontmatter = :frontmatter,
                       when_to_use = COALESCE(
                           CAST(:frontmatter AS jsonb) #>> '{metadata,when_to_use}',
                           CAST(:frontmatter AS jsonb) ->> 'when_to_use'),
                       current_version_name = CAST(:versionName AS text),
                       current_digest = :digest,
                       current_version_submitted_at = :submittedAt,
                       draft_count = :draftCount,
                       newest_draft_submitted_at = CAST(:newestDraftSubmittedAt AS text),
                       newest_draft_version = CAST(:newestDraftVersion AS text),
                       newest_draft_digest = CAST(:newestDraftDigest AS text),
                       newest_title = CAST(:newestTitle AS text),
                       newest_description = CAST(:newestDescription AS text)
                 WHERE id = :skillId AND current_version_id IS DISTINCT FROM :versionId
                   AND deleted_at IS NULL
                """)
                .param("skillId", skillId)
                .param("versionId", version.versionId())
                .param("at", at)
                .param("versionName", version.version())
                .param("digest", version.digest())
                .param("submittedAt", version.submittedAt())
                .param("title", version.title())
                .param("description", version.description())
                .param("frontmatter", version.frontmatterJson())
                .param("draftCount", drafts.count())
                .param("newestDraftSubmittedAt", drafts.newestSubmittedAt())
                .param("newestDraftVersion", drafts.newestVersion())
                .param("newestDraftDigest", drafts.newestDigest())
                .param("newestTitle", drafts.newestTitle())
                .param("newestDescription", drafts.newestDescription())
                .update() > 0;
    }

    /**
     * Rewrites the draft-side projection after a submission or a discard.
     *
     * <p><strong>Not {@code updated_at}.</strong> That column record the publish moment and nothing
     * else (V21); a submission changes what the draft side of the card says, not when the skill was
     * last published. The list's "更新于" reads the projected timestamps instead.
     */
    public void recordDraftState(String skillId, DraftProjection drafts) {
        jdbc.sql("""
                UPDATE skill
                   SET draft_count = :draftCount,
                       newest_draft_submitted_at = CAST(:newestDraftSubmittedAt AS text),
                       newest_draft_version = CAST(:newestDraftVersion AS text),
                       newest_draft_digest = CAST(:newestDraftDigest AS text),
                       newest_title = CAST(:newestTitle AS text),
                       newest_description = CAST(:newestDescription AS text)
                 WHERE id = :skillId
                """)
                .param("skillId", skillId)
                .param("draftCount", drafts.count())
                .param("newestDraftSubmittedAt", drafts.newestSubmittedAt())
                .param("newestDraftVersion", drafts.newestVersion())
                .param("newestDraftDigest", drafts.newestDigest())
                .param("newestTitle", drafts.newestTitle())
                .param("newestDescription", drafts.newestDescription())
                .update();
    }

    /**
     * The skill with this name in this namespace, together with what this caller may do to it.
     *
     * <p><strong>"Live" here means only "not soft-deleted", and since ADR 0031 that is a weaker
     * statement than it used to be.</strong> A skill can now exist with nothing published from it,
     * so this row may have no current version at all; whether anything is readable is a question
     * about its versions, and it is asked separately and per plane. What this method answers is
     * just "does a skill of this name exist here, and on what standing".
     *
     * <p>{@code UNIQUE(namespace_id, name)} makes this at most one row. Every read resolves its skill
     * this way, because the address is a name rather than an id (§4.1).
     *
     * <p><strong>The access predicate is the whole authorization check, and that is the point of
     * putting it here.</strong> §4.2 requires an unreadable private skill to be a 404 rather than a
     * 403, because a 403 confirms it exists. Filtering in the same statement that finds the row makes
     * "not yours" and "not there" the same empty result <em>structurally</em>, rather than two
     * branches a later edit could drift apart — nothing is disclosed by the absence, and nothing
     * here is rendered as a 403.
     *
     * <p><strong>It is a disjunction, and it used to be a single comparison.</strong> Before sharing,
     * the predicate was {@code namespace_id = :namespaceId}, where the namespace was the caller's
     * own — so a skill in somebody else's namespace was not expressible rather than merely refused
     * (§3.4). Now the caller may also act on a skill granted to them, and the addressed namespace is
     * no longer necessarily theirs.
     *
     * <p><strong>One statement for three answers, and the third is why.</strong> Reading the row,
     * reading whether the caller owns its namespace, and reading the rung they hold all come from
     * the same join. Splitting them into a "may read" query and a "may write" query would make each
     * write path ask twice — and the second question is exactly the one a later edit forgets, which
     * after ADR 0034 means a viewer who can delete. The grant is a {@code LEFT JOIN} rather than the
     * correlated {@code EXISTS} it could be, because the role is wanted, not just its presence.
     *
     * <p><strong>The rung is the higher of two sources</strong> (ADR 0048): what was granted to
     * this person, and what the skill's {@code public_rung} hands to everybody. Neither overrides
     * the other; a person's effective standing is the higher of the two. That is why the public
     * branch is in the <em>predicate</em> as well as in the answer — a public skill the caller was
     * never granted still has to produce a row, or it would be visible to search and invisible to
     * every read.
     *
     * <p>Both tables named here are M12's, so rule 1 is untouched and the lookup stays a comparison
     * of values this layer already holds rather than a join across a module boundary.
     *
     * @return empty when there is no such live skill, <em>or</em> when there is one this caller may
     *         not see — one answer for both, deliberately; see the class note on
     *         {@code SkillVersionService}'s 404s
     */
    public Optional<Permitted> permitted(String namespaceId, String name, Caller caller) {
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT s.id, s.namespace_id, s.name, s.title, s.description, s.frontmatter,
                       s.visibility, s.public_rung, s.source, s.current_version_id, s.offline_at,
                       s.offline_pending_at, s.offline_due_at, s.ever_published_at,
                       (s.namespace_id = :ownNamespaceId) AS owns,
                       s.grant_role AS granted_role
                FROM identified s
                WHERE s.namespace_id = :namespaceId AND s.name = :name
                  AND %s
                """.formatted(SkillPredicates.VISIBLE))
                .param("namespaceId", namespaceId)
                .param("name", name)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .query((rs, rowNum) -> new Permitted(
                        new SkillRow(
                                rs.getString("id"),
                                rs.getString("namespace_id"),
                                rs.getString("name"),
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("frontmatter"),
                                rs.getString("visibility"),
                                rs.getString("public_rung"),
                                rs.getString("current_version_id"),
                                rs.getString("offline_at"),
                                rs.getString("offline_pending_at"),
                                rs.getString("offline_due_at"),
                                rs.getString("ever_published_at")),
                        rs.getBoolean("owns"),
                        // ADR 0048: the two sources never overwrite each other, and what the person
                        // can actually do has one answer — the higher one.
                        GrantRole.higher(rs.getString("granted_role"),
                                rs.getString("public_rung")),
                        rs.getString("source")))
                .optional();
    }

    /**
     * Where a visible skill stands relative to the caller's library and its tag writes (v14).
     *
     * <p>One statement because three questions in the tag and library use cases are the same row
     * read at the same moment: the displayed {@code source} (created / shared / joined / null), the
     * entry's effectiveness ({@link SkillPredicates#ACTIVE}), and whether the skill may be joined
     * ({@link SkillPredicates#DISCOVERABLE}). Asking them separately would let a grant or a publish
     * land between the two reads and make the refusal name the wrong reason.
     *
     * <p>Reaching this type means the skill is visible ({@link SkillPredicates#VISIBLE}); an
     * invisible or deleted skill is the same empty result as an address that names nothing.
     */
    public Optional<com.skillmasterai.modules.skill.SkillStanding> standing(String namespaceId,
            String name, Caller caller) {
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT s.id, s.namespace_id, s.name, s.visibility, s.public_rung,
                       s.grant_role, s.source, s.offline_at, s.offline_pending_at, s.offline_due_at,
                       s.current_version_id,
                       (s.namespace_id = :ownNamespaceId) AS owns,
                       (s.source IS NOT NULL AND %s) AS active,
                       (%s) AS discoverable
                FROM identified s
                WHERE s.namespace_id = :namespaceId AND s.name = :name
                  AND %s
                """.formatted(SkillPredicates.ACTIVE, SkillPredicates.DISCOVERABLE,
                        SkillPredicates.VISIBLE))
                .param("namespaceId", namespaceId)
                .param("name", name)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .param("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new))
                .query((rs, rowNum) -> new com.skillmasterai.modules.skill.SkillStanding(
                        rs.getString("id"),
                        rs.getString("namespace_id"),
                        rs.getString("name"),
                        rs.getString("visibility"),
                        rs.getString("public_rung"),
                        rs.getString("grant_role"),
                        rs.getString("source"),
                        rs.getBoolean("owns"),
                        rs.getBoolean("active"),
                        rs.getBoolean("discoverable"),
                        rs.getString("offline_at"),
                        rs.getString("offline_pending_at"),
                        rs.getString("offline_due_at"),
                        rs.getString("current_version_id")))
                .optional();
    }

    /**
     * Soft-deletes the live skill with this id.
     *
     * <p><strong>By id, and the ownership check is the caller's.</strong> Since ADR 0053 the delete
     * path resolves the address through {@code accessFor} first — that is where "reachable" and
     * "the owner" are decided, with different answers — so repeating a namespace predicate here
     * would be a second, silently divergent copy of the rule. What this statement guarantees is the
     * other half: a skill that is already deleted stays deleted, and the row it touched is named by
     * its identity (ADR 0004) for the audit trail.
     *
     * @return the deleted skill's id, or empty when it was already deleted
     */
    public Optional<String> softDelete(String skillId, String at) {
        return jdbc.sql("""
                UPDATE skill SET deleted_at = :at, updated_at = :at
                WHERE id = :skillId AND deleted_at IS NULL
                RETURNING id
                """)
                .param("skillId", skillId)
                .param("at", at)
                .query(String.class)
                .optional();
    }

    /** Whether the row still exists and is not soft-deleted — the invitation accept's last guard. */
    public boolean isLive(String skillId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM skill"
                        + " WHERE id = :skillId AND deleted_at IS NULL)")
                .param("skillId", skillId)
                .query(Boolean.class)
                .single();
    }

    /** The narrow delete's two statements live in their own class (v37, ADR 0127). */
    public Optional<String> softDeleteNeverPublished(String skillId, String at) {
        return neverPublishedDelete.delete(skillId, at);
    }

    /** The facts that explain a refused narrow delete; read only after the delete returned none. */
    public NeverPublishedDelete.State deleteState(String skillId) {
        return neverPublishedDelete.state(skillId);
    }

    /**
     * Clears {@code deleted_at} on the skill this address names — <strong>the owner's only, and the
     * only by-address path that resolves a deleted row</strong> (ADR 0050).
     *
     * <p>It cannot use {@link #permitted}: that predicate carries {@code deleted_at IS NULL}, so
     * reusing it would make restore answer 404 for ever. It is deliberately narrow in the other
     * direction too — the address segment is part of the {@code WHERE}, and ownership is checked in
     * the same statement rather than by a caller afterwards, so a non-owner's attempt is the same
     * empty result as an address that never existed. That is the answer the 404 page needs: a
     * deleted skill must stay indistinguishable from one that was never there.
     *
     * <p>Since v61 the name is not held by a deleted row, so there may be several tombstones with
     * the same name. <strong>A caller that names one restores that one</strong> ({@code id} — the
     * console's deleted list sends the row's id); without one this restores the newest tombstone,
     * which is the row that list shows first (it is ordered by {@code deleted_at}). The tombstone
     * is locked for the update; if a live skill has since taken the name, the restored row gets
     * {@code 原名-restored-<id>} instead of colliding with it.
     *
     * @return the restored row's id and its address name, or empty when no deleted skill of this
     *         name the caller owns
     */
    public Optional<SkillMetadataService.Restored> restore(String namespaceId, String name,
            String id, Caller caller, String at) {
        if (!namespaceId.equals(caller.ownNamespaceId())) {
            return Optional.empty();
        }
        lockName(namespaceId, name);
        Optional<String> deletedId = (id == null || id.isBlank())
                ? jdbc.sql("""
                        SELECT id FROM skill
                         WHERE namespace_id = :namespaceId AND name = :name
                           AND deleted_at IS NOT NULL
                         ORDER BY deleted_at DESC,
                                 COALESCE(current_version_submitted_at,
                                          newest_draft_submitted_at, created_at) DESC,
                                 id ASC
                         LIMIT 1
                        FOR UPDATE
                        """)
                        .param("namespaceId", namespaceId)
                        .param("name", name)
                        .query(String.class)
                        .optional()
                : jdbc.sql("""
                        SELECT id FROM skill
                         WHERE id = :id AND namespace_id = :namespaceId AND name = :name
                           AND deleted_at IS NOT NULL
                        FOR UPDATE
                        """)
                        .param("id", id)
                        .param("namespaceId", namespaceId)
                        .param("name", name)
                        .query(String.class)
                        .optional();
        if (deletedId.isEmpty()) {
            return Optional.empty();
        }
        // The name is now ours for the rest of the transaction: a create or a second restore of
        // the same address waits (see {@link #lockName}), so the live test below cannot be raced.
        boolean live = liveNameExists(namespaceId, name);
        String restoredName = live ? freedName(name, deletedId.get()) : name;
        return jdbc.sql("""
                UPDATE skill SET deleted_at = NULL, name = :restoredName, updated_at = :at
                 WHERE id = :id AND deleted_at IS NOT NULL
                 RETURNING id
                """)
                .param("restoredName", restoredName)
                .param("at", at)
                .param("id", deletedId.get())
                .query(String.class)
                .optional()
                .map(restoredId ->
                        new SkillMetadataService.Restored(restoredId, restoredName, live));
    }

    private boolean liveNameExists(String namespaceId, String name) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM skill
                                WHERE namespace_id = :namespaceId AND name = :name
                                  AND deleted_at IS NULL)
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query(Boolean.class)
                .single();
    }

    /**
     * Serialises create / submit / restore on one address for the rest of the transaction (v61).
     *
     * <p><strong>Row locks cannot do this</strong>: the conflicting row does not exist yet for a
     * create, and the unique index is only consulted when the write happens. A transaction-scoped
     * advisory lock on {@code namespace + name} is; the hash collision it may have with an
     * unrelated name only costs a wait. Every write that can insert or rename a live row takes it
     * before looking, so the two orderings cannot interleave.
     */
    private void lockName(String namespaceId, String name) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", namespaceId + "/" + name)
                .query((rs, rowNum) -> 0)
                .single();
    }

    /**
     * The one case the consumption plane answers 410 instead of 404 (v61, ADR 0184).
     *
     * <p><strong>Only while the name has no live skill.</strong> Since ADR 0183 the same name can
     * be a tombstone and a live skill at once; the address then names the live one, and answering
     * 410 about the old one would describe a state the address no longer has.
     */
    public boolean deletedPublicEverPublished(String namespaceId, String name) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM skill
                     WHERE namespace_id = :namespaceId AND name = :name
                       AND deleted_at IS NOT NULL
                       AND visibility = 'public'
                       AND ever_published_at IS NOT NULL)
                   AND NOT EXISTS (
                    SELECT 1 FROM skill
                     WHERE namespace_id = :namespaceId AND name = :name
                       AND deleted_at IS NULL)
                """)
                .param("namespaceId", namespaceId)
                .param("name", name)
                .query(Boolean.class)
                .single();
    }

    /**
     * The name a restoring tombstone gets when a live skill has taken the original one. The suffix
     * carries the row's id, so it is unique within the namespace; the prefix is shortened only when
     * the whole name would exceed the 64-code-point rule the content side states (M5).
     */
    private static String freedName(String name, String id) {
        // **Lowercased, because a name is lowercase-only** (ADR 0160) and an address that the
        // reader's own grammar refuses is not an address. A ULID is Crockford base32, so the
        // lowercase spelling is the same identity.
        String suffix = "-restored-" + id.toLowerCase(java.util.Locale.ROOT);
        String candidate = name + suffix;
        int max = 64;
        if (candidate.codePointCount(0, candidate.length()) <= max) {
            return candidate;
        }
        int keep = Math.max(0, max - suffix.codePointCount(0, suffix.length()));
        int end = name.offsetByCodePoints(0, Math.min(keep, name.codePointCount(0, name.length())));
        // **A cut can land right after a hyphen.** Name grammar has no `--`, so the suffix would
        // make the address its own reader refuses; step back to the last segment boundary.
        String prefix = name.substring(0, end);
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + suffix;
    }

    /**
     * Writes the two columns that are one fact: whether the skill is public, and which rung
     * strangers get (ADR 0048).
     *
     * <p>The pair travels together because V19's CHECK refuses any other combination; writing one
     * without the other is not a state the database will hold. The caller has already decided that
     * the caller may administer this skill.
     */
    /**
     * Switches the skill off or back on. A service switch, not a state: no version row moves.
     *
     * @param at the moment it went offline, or null to serve again
     */
    /**
     * The version the pointer names right now, read fresh inside the caller's transaction.
     *
     * <p>Exists for M7's revoke: its "not the current version" decision is made on an {@code Access}
     * snapshot taken earlier in the request, and a rollback committing in between would make that
     * snapshot stale. The module boundary keeps the {@code skill} table's SQL here.
     */
    public Optional<String> currentVersionId(String skillId) {
        List<String> rows = jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId)
                .query((rs, n) -> rs.getString("current_version_id"))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /**
     * Switches the skill off and clears any ramp. False when there was nothing to switch: already
     * off, already in a ramp, or the row is gone or deleted. The caller notifies only on a real
     * transition, so two overlapping requests cannot send two announcements (ADR 0184).
     */
    public boolean setOffline(String skillId, String at) {
        return jdbc.sql("""
                UPDATE skill
                   SET offline_at = :at, offline_pending_at = NULL, offline_due_at = NULL
                 WHERE id = :skillId AND deleted_at IS NULL
                   AND offline_at IS NULL AND offline_due_at IS NULL
                """)
                .param("skillId", skillId)
                .param("at", at)
                .update() > 0;
    }

    /**
     * Withdraws a ramp that has not completed: false when the ramp was already finished (the daily
     * job wrote {@code offline_at}) or never existed. Separate from {@link #resume} so the caller
     * can announce a cancellation only for the transition it actually made.
     */
    public boolean cancelRamp(String skillId) {
        return jdbc.sql("""
                UPDATE skill
                   SET offline_at = NULL, offline_pending_at = NULL, offline_due_at = NULL
                 WHERE id = :skillId AND deleted_at IS NULL
                   AND offline_at IS NULL AND offline_due_at IS NOT NULL
                """)
                .param("skillId", skillId)
                .update() > 0;
    }

    /** Back to serving from 已下线. False when it was already serving or the row is gone. */
    public boolean resume(String skillId) {
        return jdbc.sql("""
                UPDATE skill
                   SET offline_at = NULL, offline_pending_at = NULL, offline_due_at = NULL
                 WHERE id = :skillId AND deleted_at IS NULL AND offline_at IS NOT NULL
                """)
                .param("skillId", skillId)
                .update() > 0;
    }

    /** Starts the 30-day ramp; false when another request already changed the state. */
    public boolean setPendingOffline(String skillId, String pendingAt, String dueAt) {
        return jdbc.sql("""
                UPDATE skill
                   SET offline_pending_at = :pendingAt, offline_due_at = :dueAt, offline_at = NULL
                 WHERE id = :skillId AND deleted_at IS NULL
                   AND offline_at IS NULL AND offline_due_at IS NULL
                """)
                .param("skillId", skillId)
                .param("pendingAt", pendingAt)
                .param("dueAt", dueAt)
                .update() > 0;
    }

    /** The ids whose ramp is due. Read-time predicates decide the same thing; this drives the job. */
    public List<String> dueOfflineIds(String now) {
        return jdbc.sql("""
                SELECT id FROM skill
                 WHERE deleted_at IS NULL AND offline_at IS NULL
                   AND offline_due_at IS NOT NULL AND offline_due_at <= :now
                 ORDER BY offline_due_at
                """)
                .param("now", now)
                .query(String.class)
                .list();
    }

    /**
     * Claims one due row; only the caller that gets a row emits the completion notification.
     *
     * <p>The address travels back with the claim so the notice can name the skill without a
     * second read — and {@code offline_at} is now the date the ramp ended, which is what the
     * announcement says. Two instances running the job at once: exactly one gets the row.
     */
    public Optional<ClaimedOffline> claimDueOffline(String skillId, String now) {
        return jdbc.sql("""
                UPDATE skill
                   SET offline_at = offline_due_at,
                       offline_pending_at = NULL,
                       offline_due_at = NULL
                 WHERE id = :skillId AND deleted_at IS NULL AND offline_at IS NULL
                   AND offline_due_at IS NOT NULL AND offline_due_at <= :now
                RETURNING id, namespace_id, name, offline_at
                """)
                .param("skillId", skillId)
                .param("now", now)
                .query((rs, rowNum) -> new ClaimedOffline(rs.getString("id"),
                        rs.getString("namespace_id"), rs.getString("name"),
                        rs.getString("offline_at")))
                .optional();
    }

    /** @param offlineAt the ramp's due date, now the moment the skill went offline */
    public record ClaimedOffline(String id, String namespaceId, String name, String offlineAt) {
    }

    /** How many people hold a library reference; the delete gate and the ramp ask this. */
    public int libraryJoins(String skillId) {
        return jdbc.sql("SELECT COUNT(*) FROM skill_library_join WHERE skill_id = :skillId")
                .param("skillId", skillId)
                .query(Integer.class)
                .single();
    }

    /**
     * How many people outside the owner hold a grant. The delete confirmation names it beside the
     * join count (v61, ADR 0184); the owner is never a grant row, so nothing is subtracted.
     */
    public int grantees(String skillId) {
        return jdbc.sql("SELECT COUNT(*) FROM skill_grant WHERE skill_id = :skillId")
                .param("skillId", skillId)
                .query(Integer.class)
                .single();
    }

    /** Grantees and library joiners, without the owner — the recipients of a lifecycle notice. */
    public List<String> lifecycleRecipients(String skillId, String ownerUserId) {
        return jdbc.sql("""
                SELECT grantee_id AS user_id FROM skill_grant WHERE skill_id = :skillId
                UNION
                SELECT user_id FROM skill_library_join WHERE skill_id = :skillId
                """)
                .param("skillId", skillId)
                .query(String.class)
                .list()
                .stream()
                .filter(userId -> !userId.equals(ownerUserId))
                .toList();
    }

    public void setVisibility(String skillId, String visibility, String publicRung, String at) {
        jdbc.sql("""
                UPDATE skill
                   SET visibility = :visibility, public_rung = CAST(:publicRung AS text),
                       updated_at = :at
                 WHERE id = :skillId
                """)
                .param("skillId", skillId)
                .param("visibility", visibility)
                .param("publicRung", publicRung)
                .param("at", at)
                .update();
    }

    /**
     * One page of what the discovery plane may serve, for the operations service (v72).
     *
     * <p>The predicate is {@link SkillPredicates#DISCOVERABLE}, not a copy: "public, published,
     * online, not deleted" is one statement (ADR 0082), and a label refresh that used a wider one
     * would label skills no reader can reach. The official-namespace filter is the caller's, because
     * the namespace table belongs to M4.
     */
    public List<PublicRow> publicPage(String cursor, int limit) {
        // No join to `namespace`: that table belongs to M4, and the official-namespace filter is
        // applied one layer up, where the slug and the flag are readable. The discovery predicate
        // already means "public", and only an official namespace can hold a public skill (ADR 0146).
        String sql = "SELECT s.id, s.namespace_id, s.name, s.current_version_id"
                + "  FROM skill s WHERE " + SkillPredicates.DISCOVERABLE
                + (cursor == null ? "" : " AND s.id > :cursor")
                + " ORDER BY s.id LIMIT :limit";
        var statement = jdbc.sql(sql).param("limit", limit);
        if (cursor != null) {
            statement = statement.param("cursor", cursor);
        }
        return statement.query((rs, rowNum) -> new PublicRow(rs.getString("id"),
                rs.getString("namespace_id"), rs.getString("name"),
                rs.getString("current_version_id"))).list();
    }

    /** One row of a public page: enough to address the skill and its current version. */
    public record PublicRow(String id, String namespaceId, String name, String currentVersionId) {
    }

    /**
     * @param currentVersionId the version consumers get, or null when nothing has been published
     *                         yet. Since ADR 0031 that is an ordinary state — a skill whose versions
     *                         are all drafts — and no longer the signature of a broken invariant.
     *                         What is still broken is a pointer naming a version that is not
     *                         published; {@link VersionRepository#findCurrent} refuses that
     */
    public record SkillRow(String id, String namespaceId, String name, String title,
            String description, String frontmatter, String visibility, String publicRung,
            String currentVersionId, String offlineAt, String offlinePendingAt,
            String offlineDueAt, String everPublishedAt) {
    }
}
