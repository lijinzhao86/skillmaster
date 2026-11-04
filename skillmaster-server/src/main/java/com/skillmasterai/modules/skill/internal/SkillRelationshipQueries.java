package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillAddress;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * M12's relationship reads, split from {@link SkillRepository} when v45 pushed that file past its
 * size limit.
 *
 * <p>They answer two questions the notification use cases need and the address-shaped repository
 * cannot: "what does this skill id point at" and "which of these ids may this person read or
 * administer". They use the same {@link SkillPredicates} every other access read uses, so a
 * notification's 「查看」 and the page it opens cannot disagree.
 */
public final class SkillRelationshipQueries {

    private final JdbcClient jdbc;

    public SkillRelationshipQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The same authorization question as {@link SkillRepository#permitted}, asked by identity.
     *
     * <p>An {@code access_request} notification carries only the skill's id: the CLI approves from
     * the notification, and re-resolving an address it does not have would be a second lookup that
     * could disagree with the notification's subject.
     */
    public Optional<Permitted> permittedById(String skillId, Caller caller) {
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT s.id, s.namespace_id, s.name, s.title, s.description, s.frontmatter,
                       s.visibility, s.public_rung, s.current_version_id, s.offline_at,
                       s.ever_published_at,
                       (s.namespace_id = :ownNamespaceId) AS owns,
                       s.grant_role AS granted_role
                FROM identified s
                WHERE s.id = :skillId AND %s
                """.formatted(SkillPredicates.VISIBLE))
                .param("skillId", skillId)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .query((rs, rowNum) -> new Permitted(
                        new SkillRepository.SkillRow(
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
                                rs.getString("ever_published_at")),
                        rs.getBoolean("owns"),
                        GrantRole.higher(rs.getString("granted_role"),
                                rs.getString("public_rung"))))
                .optional();
    }

    /** The address behind each id that still has a row. Deleted and missing ids are absent. */
    public Map<String, SkillAddress> addressesOf(Collection<String> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT id, namespace_id, name FROM skill WHERE id = ANY(:ids)")
                .param("ids", skillIds.toArray(String[]::new))
                .query((rs, rowNum) -> Map.entry(rs.getString("id"),
                        new SkillAddress(rs.getString("namespace_id"), rs.getString("name"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Which of these skills the caller may read — one statement for a page of notifications. */
    public Set<String> readableAmong(Caller caller, Collection<String> skillIds) {
        if (skillIds.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT s.id FROM identified s
                 WHERE s.id = ANY(:ids) AND %s
                """.formatted(SkillPredicates.VISIBLE))
                .param("ids", skillIds.toArray(String[]::new))
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The skills this person may administer: their own, plus every skill at the {@code admin} rung. */
    public Set<String> administeredSkillIds(Caller caller) {
        return jdbc.sql(SkillPredicates.IDENTIFIED + """
                SELECT s.id FROM identified s
                 WHERE s.deleted_at IS NULL
                   AND (s.namespace_id = :ownNamespaceId OR s.grant_role = 'admin')
                """)
                .param("ownNamespaceId", caller.ownNamespaceId())
                .param("callerId", caller.userId())
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }
}
