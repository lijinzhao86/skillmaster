package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillTagService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Both tag layers and the private definitions (v14, ADR 0083).
 *
 * <p><strong>Public tags have no definition table; private tags do.</strong> A public tag's identity
 * is its normalized word, so one row is "this skill carries this word". A private tag can be renamed
 * once and seen everywhere, so it has a definition addressed by an opaque id and an attachment per
 * skill. The two shapes are not unified because their lifetimes are not: a public word changes only
 * by attaching or detaching, a private one changes everywhere at once.
 *
 * <p><strong>Every statement names only M12's tables.</strong> The "effective write" count for a
 * private tag needs the library predicate, so the statement starts from
 * {@link SkillPredicates#IDENTIFIED} and asks {@link SkillPredicates#ACTIVE} of it — the same two
 * definitions every listing uses. That is what keeps a tag count and a library page from disagreeing
 * about what is effective.
 *
 * <p><strong>The two advisory locks are here because they are the tables' invariants.</strong> A
 * per-account definition ceiling and a per-skill attachment ceiling are cross-row facts the database
 * cannot express as a constraint; the lock is taken by the use case's transaction, in the order
 * documented in the TD (user first, then skill), and released when that transaction ends.
 */
public final class TagRepository {

    private final JdbcClient jdbc;

    public TagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* ---- advisory locks ---- */

    /**
     * Serializes definition creation for one account, so the 100th and 101st cannot both see 99.
     *
     * <p>The key embeds the user id, so two people never contend. Transaction-scoped: the lock is
     * released by commit or rollback, not by this method.
     */
    public void lockDefinitionsOf(String userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('my-tags:' || :userId, 0))")
                .param("userId", userId)
                .query((rs, rowNum) -> Boolean.TRUE)
                .list();
    }

    /**
     * Serializes tag writes for one skill, so two concurrent attaches cannot both see 19.
     *
     * <p>A named advisory lock rather than a row lock on {@code skill}: publishing, going offline
     * and changing a grant have nothing to do with tag ceilings, and a row lock here would queue
     * them behind a tag write (TD §并发与上限).
     */
    public void lockTagsOf(String skillId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('skill-tags:' || :skillId, 0))")
                .param("skillId", skillId)
                .query((rs, rowNum) -> Boolean.TRUE)
                .list();
    }

    /* ---- public tags ---- */

    public List<SkillTagService.PublicTagView> publicTagsOfSkill(String skillId) {
        return jdbc.sql("""
                SELECT tag_key, tag_name FROM skill_public_tag
                 WHERE skill_id = :skillId
                 ORDER BY tag_key
                """)
                .param("skillId", skillId)
                .query((rs, rowNum) -> new SkillTagService.PublicTagView(
                        rs.getString("tag_key"), rs.getString("tag_name")))
                .list();
    }

    /** One query for a page's worth of cards, keyed by skill id. */
    public Map<String, List<SkillTagService.PublicTagView>> publicTagsOfSkills(
            Collection<String> skillIds) {
        Map<String, List<SkillTagService.PublicTagView>> bySkill = new LinkedHashMap<>();
        if (skillIds.isEmpty()) {
            return bySkill;
        }
        jdbc.sql("""
                SELECT skill_id, tag_key, tag_name FROM skill_public_tag
                 WHERE skill_id = ANY(:skillIds)
                 ORDER BY tag_key
                """)
                .param("skillIds", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> {
                    bySkill.computeIfAbsent(rs.getString("skill_id"), ignored -> new ArrayList<>())
                            .add(new SkillTagService.PublicTagView(
                                    rs.getString("tag_key"), rs.getString("tag_name")));
                    return null;
                })
                .list();
        return bySkill;
    }

    public boolean publicTagExists(String skillId, String key) {
        return !jdbc.sql("""
                SELECT 1 FROM skill_public_tag WHERE skill_id = :skillId AND tag_key = :key
                """)
                .param("skillId", skillId)
                .param("key", key)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    /** A repeat is a no-op: the word is already on this skill, in whichever spelling came first. */
    public void attachPublic(String skillId, String key, String name, String at) {
        jdbc.sql("""
                INSERT INTO skill_public_tag (skill_id, tag_key, tag_name, created_at)
                VALUES (:skillId, :key, :name, :at)
                ON CONFLICT (skill_id, tag_key) DO NOTHING
                """)
                .param("skillId", skillId)
                .param("key", key)
                .param("name", name)
                .param("at", at)
                .update();
    }

    public boolean detachPublic(String skillId, String key) {
        return jdbc.sql("""
                DELETE FROM skill_public_tag WHERE skill_id = :skillId AND tag_key = :key
                """)
                .param("skillId", skillId)
                .param("key", key)
                .update() > 0;
    }

    public long countPublicTags(String skillId) {
        return jdbc.sql("SELECT count(*) FROM skill_public_tag WHERE skill_id = :skillId")
                .param("skillId", skillId)
                .query(Long.class)
                .single();
    }

    /**
     * The public vocabulary, with the count the CLI shows as 公域.
     *
     * <p>The display spelling comes from the earliest attachment ({@code created_at}, then
     * {@code skill_id}) so one word does not change shape between two requests. The count is over
     * discoverable skills only, which is a different number from the management page's "used in"
     * (PRD §展示与计数口径).
     */
    public List<SkillTagService.PublicTagRow> publicVocabulary(String q, int limit,
            List<String> afterKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        // The vocabulary is the same for everyone: the CTE's two caller parameters are bound but
        // never select a row (the filter is DISCOVERABLE alone). Binding an empty id keeps the
        // statement caller-independent without asking M4 who the reader is.
        params.put("ownNamespaceId", "");
        params.put("callerId", "");
        params.put("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new));
        return vocabulary(q, limit, afterKey, params);
    }

    /**
     * The same vocabulary read as the management page's public tab: {@code used_in} counts only
     * the caller's own created, effective skills.
     */
    public List<SkillTagService.ManagedPublicTagRow> publicTagsUsedByCreatedSkills(Caller caller,
            String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ownNamespaceId", caller.ownNamespaceId());
        params.put("callerId", caller.userId());
        params.put("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new));
        String pattern = LikePattern.containing(q == null ? null : q.strip());
        params.put("pattern", pattern);

        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                , mine AS (
                    SELECT pt.tag_key, count(DISTINCT s.id) AS used_in
                    FROM skill_public_tag pt
                    JOIN identified s ON s.id = pt.skill_id
                    WHERE s.source = 'created' AND %s
                    GROUP BY pt.tag_key
                ),
                earliest AS (
                    SELECT DISTINCT ON (pt.tag_key) pt.tag_key, pt.tag_name
                    FROM skill_public_tag pt
                    JOIN identified s ON s.id = pt.skill_id
                    WHERE s.source = 'created' AND %s
                    ORDER BY pt.tag_key, pt.created_at, pt.skill_id
                )
                SELECT e.tag_key, e.tag_name, m.used_in
                FROM mine m
                JOIN earliest e ON e.tag_key = m.tag_key
                WHERE CAST(:pattern AS text) IS NULL
                   OR e.tag_name ILIKE :pattern ESCAPE '\\'
                ORDER BY m.used_in DESC, e.tag_name ASC, e.tag_key ASC
                """.formatted(SkillPredicates.ACTIVE, SkillPredicates.ACTIVE))
                .params(params)
                .query((rs, rowNum) -> new SkillTagService.ManagedPublicTagRow(
                        rs.getString("tag_key"), rs.getString("tag_name"),
                        rs.getLong("used_in")))
                .list();
    }

    private List<SkillTagService.PublicTagRow> vocabulary(String q, int limit, List<String> afterKey,
            Map<String, Object> params) {
        String pattern = LikePattern.containing(q == null ? null : q.strip());
        params.put("pattern", pattern);
        params.put("hasCursor", afterKey != null);
        params.put("limit", limit);
        List<String> key = afterKey == null ? List.of("0", "", "") : afterKey;
        for (int i = 0; i < key.size(); i++) {
            params.put("k" + i, key.get(i));
        }

        // **Both CTEs read only discoverable skills.** A word whose attachments are all private,
        // offline or deleted must not appear at all: the vocabulary is part of the public plane, and
        // a word with `public_total = 0` would tell every caller a private skill's tag.
        //
        // The keyset carries all three ordering parts (`total`, then display name, then key), not
        // just the first two: collation can order `tag_name` differently from `tag_key`, so a
        // cursor without the name could step over a row that ties on both count and position.
        String sql = SkillPredicates.IDENTIFIED + """
                , totals AS (
                    SELECT pt.tag_key, count(*) AS total
                    FROM skill_public_tag pt
                    JOIN identified s ON s.id = pt.skill_id
                    WHERE %s
                    GROUP BY pt.tag_key
                ),
                earliest AS (
                    SELECT DISTINCT ON (pt.tag_key) pt.tag_key, pt.tag_name
                    FROM skill_public_tag pt
                    JOIN identified s ON s.id = pt.skill_id
                    WHERE %s
                    ORDER BY pt.tag_key, pt.created_at, pt.skill_id
                ),
                vocab AS (
                    SELECT e.tag_key, e.tag_name, t.total
                    FROM earliest e
                    JOIN totals t ON t.tag_key = e.tag_key
                    WHERE CAST(:pattern AS text) IS NULL
                       OR e.tag_name ILIKE :pattern ESCAPE '\\'
                )
                SELECT * FROM vocab t
                WHERE :hasCursor = FALSE
                   OR (t.total < CAST(:k0 AS bigint)
                       OR (t.total = CAST(:k0 AS bigint)
                           AND (t.tag_name > :k1
                                OR (t.tag_name = :k1 AND t.tag_key > :k2))))
                ORDER BY t.total DESC, t.tag_name ASC, t.tag_key ASC
                LIMIT :limit
                """.formatted(SkillPredicates.DISCOVERABLE, SkillPredicates.DISCOVERABLE);
        return jdbc.sql(sql).params(params)
                .query((rs, rowNum) -> new SkillTagService.PublicTagRow(
                        rs.getString("tag_key"), rs.getString("tag_name"), rs.getLong("total")))
                .list();
    }

    /* ---- private definitions ---- */

    public Optional<SkillTagService.Definition> findDefinitionByName(String userId, String nameKey) {
        return jdbc.sql("""
                SELECT id, name, name_key, description FROM my_tag_definition
                 WHERE user_id = :userId AND name_key = :nameKey
                """)
                .param("userId", userId)
                .param("nameKey", nameKey)
                .query((rs, rowNum) -> new SkillTagService.Definition(
                        rs.getString("id"), rs.getString("name"), rs.getString("name_key"),
                        rs.getString("description")))
                .optional();
    }

    public Optional<SkillTagService.Definition> findDefinition(String userId, String id) {
        return jdbc.sql("""
                SELECT id, name, name_key, description FROM my_tag_definition
                 WHERE user_id = :userId AND id = :id
                """)
                .param("userId", userId)
                .param("id", id)
                .query((rs, rowNum) -> new SkillTagService.Definition(
                        rs.getString("id"), rs.getString("name"), rs.getString("name_key"),
                        rs.getString("description")))
                .optional();
    }

    public long countDefinitions(String userId) {
        return jdbc.sql("SELECT count(*) FROM my_tag_definition WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    public void insertDefinition(String id, String userId, String name, String nameKey,
            String description, String at) {
        jdbc.sql("""
                INSERT INTO my_tag_definition (id, user_id, name, name_key, description,
                                               created_at, updated_at)
                VALUES (:id, :userId, :name, :nameKey, :description, :at, :at)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("name", name)
                .param("nameKey", nameKey)
                .param("description", description)
                .param("at", at)
                .update();
    }

    public void updateName(String id, String name, String nameKey, String at) {
        jdbc.sql("""
                UPDATE my_tag_definition SET name = :name, name_key = :nameKey, updated_at = :at
                 WHERE id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("nameKey", nameKey)
                .param("at", at)
                .update();
    }

    public void updateDescription(String id, String description, String at) {
        jdbc.sql("""
                UPDATE my_tag_definition SET description = :description, updated_at = :at
                 WHERE id = :id
                """)
                .param("id", id)
                .param("description", description)
                .param("at", at)
                .update();
    }

    public boolean deleteDefinition(String userId, String id) {
        return jdbc.sql("DELETE FROM my_tag_definition WHERE user_id = :userId AND id = :id")
                .param("userId", userId)
                .param("id", id)
                .update() > 0;
    }

    /**
     * Every definition, with the count the CLI shows as 用在.
     *
     * <p>The count is over attachments whose library entry is effective for the definition's owner —
     * a dormant attachment is retained and simply not counted (PRD §展示与计数口径).
     */
    public List<SkillTagService.MyTagRow> definitionsWithUsage(Caller caller, String q) {
        return definitionsWithUsage(caller, q, null);
    }

    /**
     * @param nameKey the normalized word, when one definition is being resolved by name (the CLI's
     *                rename / describe). Null means "every definition"; a key that matches nothing
     *                yields an empty list, which the caller reports as "no such tag"
     */
    public List<SkillTagService.MyTagRow> definitionsWithUsage(Caller caller, String q,
            String nameKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ownNamespaceId", caller.ownNamespaceId());
        params.put("callerId", caller.userId());
        params.put("writeAndUp", SkillPredicates.WRITE_AND_UP.toArray(String[]::new));
        params.put("pattern", LikePattern.containing(q == null ? null : q.strip()));
        params.put("nameKey", nameKey);

        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT d.id, d.name, d.description,
                       (SELECT count(*)
                          FROM my_tag_attachment a
                          JOIN identified s ON s.id = a.skill_id
                         WHERE a.definition_id = d.id
                           AND s.source IS NOT NULL
                           AND %s) AS used_in,
                       (SELECT count(*)
                          FROM my_tag_attachment a
                          JOIN identified s ON s.id = a.skill_id
                         WHERE a.definition_id = d.id
                           AND (s.source IS NULL OR NOT (%s))) AS dormant_in
                FROM my_tag_definition d
                WHERE d.user_id = :callerId
                  AND (CAST(:nameKey AS text) IS NULL OR d.name_key = :nameKey)
                  AND (CAST(:pattern AS text) IS NULL
                       OR d.name ILIKE :pattern ESCAPE '\\'
                       OR d.description ILIKE :pattern ESCAPE '\\')
                ORDER BY used_in DESC, d.name ASC, d.id ASC
                """.formatted(SkillPredicates.ACTIVE, SkillPredicates.ACTIVE))
                .params(params)
                .query((rs, rowNum) -> new SkillTagService.MyTagRow(
                        rs.getString("id"), rs.getString("name"), rs.getString("description"),
                        rs.getLong("used_in"), rs.getLong("dormant_in")))
                .list();
    }

    /* ---- private attachments ---- */

    public List<SkillTagService.MyTagView> myTagsOnSkill(String userId, String skillId) {
        return jdbc.sql("""
                SELECT d.id, d.name, d.description
                FROM my_tag_attachment a
                JOIN my_tag_definition d ON d.id = a.definition_id
                WHERE a.skill_id = :skillId AND d.user_id = :userId
                ORDER BY d.name ASC, d.id ASC
                """)
                .param("skillId", skillId)
                .param("userId", userId)
                .query((rs, rowNum) -> new SkillTagService.MyTagView(
                        rs.getString("id"), rs.getString("name"), rs.getString("description")))
                .list();
    }

    public Map<String, List<SkillTagService.MyTagView>> myTagsOfSkills(String userId,
            Collection<String> skillIds) {
        Map<String, List<SkillTagService.MyTagView>> bySkill = new LinkedHashMap<>();
        if (skillIds.isEmpty()) {
            return bySkill;
        }
        jdbc.sql("""
                SELECT a.skill_id, d.id, d.name, d.description
                FROM my_tag_attachment a
                JOIN my_tag_definition d ON d.id = a.definition_id
                WHERE a.skill_id = ANY(:skillIds) AND d.user_id = :userId
                ORDER BY d.name ASC, d.id ASC
                """)
                .param("skillIds", skillIds.toArray(String[]::new))
                .param("userId", userId)
                .query((rs, rowNum) -> {
                    bySkill.computeIfAbsent(rs.getString("skill_id"), ignored -> new ArrayList<>())
                            .add(new SkillTagService.MyTagView(
                                    rs.getString("id"), rs.getString("name"),
                                    rs.getString("description")));
                    return null;
                })
                .list();
        return bySkill;
    }

    public boolean attachmentExists(String userId, String definitionId, String skillId) {
        return !jdbc.sql("""
                SELECT 1 FROM my_tag_attachment a
                JOIN my_tag_definition d ON d.id = a.definition_id
                WHERE a.definition_id = :definitionId AND a.skill_id = :skillId
                  AND d.user_id = :userId
                """)
                .param("definitionId", definitionId)
                .param("skillId", skillId)
                .param("userId", userId)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    public void attachMy(String definitionId, String skillId, String at) {
        jdbc.sql("""
                INSERT INTO my_tag_attachment (definition_id, skill_id, created_at)
                VALUES (:definitionId, :skillId, :at)
                ON CONFLICT (definition_id, skill_id) DO NOTHING
                """)
                .param("definitionId", definitionId)
                .param("skillId", skillId)
                .param("at", at)
                .update();
    }

    public boolean detachMy(String userId, String definitionId, String skillId) {
        return jdbc.sql("""
                DELETE FROM my_tag_attachment a
                 USING my_tag_definition d
                 WHERE a.definition_id = d.id AND a.definition_id = :definitionId
                   AND a.skill_id = :skillId AND d.user_id = :userId
                """)
                .param("definitionId", definitionId)
                .param("skillId", skillId)
                .param("userId", userId)
                .update() > 0;
    }

    /** "Effective now" is the same for every attachment on one skill, so the entry is not joined. */
    public long countAttachments(String userId, String skillId) {
        return jdbc.sql("""
                SELECT count(*) FROM my_tag_attachment a
                JOIN my_tag_definition d ON d.id = a.definition_id
                WHERE a.skill_id = :skillId AND d.user_id = :userId
                """)
                .param("skillId", skillId)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

}
