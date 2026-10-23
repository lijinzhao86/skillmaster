package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.LibraryRepository;
import java.util.List;

/**
 * M12's library seam: one person's entries and the reference they add or remove (v14, ADR 0082).
 *
 * <p><strong>A library entry is derived from three things, and this service is where the
 * derivation is spent.</strong> The caller's namespace says "mine", a grant says "shared", and
 * {@code skill_library_join} says "joined". The row's {@code source} is computed once by the shared
 * predicate; the service passes the repository's page, its three counters and its recommendation
 * batch through unchanged.
 *
 * <p><strong>The three counters are exposed separately because they answer three questions.</strong>
 * The product's empty states are told apart by them: no entries at all, entries that are all
 * dormant, and a filtered list with no matches are three different sentences (PRD §展示与计数口径).
 */
public final class SkillLibraryService {

    private final LibraryRepository repository;

    public SkillLibraryService(LibraryRepository repository) {
        this.repository = repository;
    }

    public List<SkillCatalogService.CatalogRow> page(Query query, Caller caller) {
        return repository.page(query, caller);
    }

    public long filteredTotal(Query query, Caller caller) {
        return repository.filteredTotal(query, caller);
    }

    public long totalEntries(Caller caller) {
        return repository.totalEntries(caller);
    }

    public long effectiveEntries(Caller caller) {
        return repository.effectiveEntries(caller);
    }

    public List<SkillCatalogService.CatalogRow> recommended(List<String> afterKey, int limit,
            Caller caller) {
        return repository.recommended(afterKey, limit, caller);
    }

    /** @return whether this call inserted the reference; a repeat answers false */
    public boolean join(String userId, String skillId, String at) {
        return repository.join(userId, skillId, at);
    }

    /** @return whether a reference was there — false is a 409 at the caller */
    public boolean leave(String userId, String skillId) {
        return repository.leave(userId, skillId);
    }

    /** Whether the caller holds a join reference, whatever source is displayed. */
    public boolean joined(String userId, String skillId) {
        return repository.joined(userId, skillId);
    }

    /** The skill id behind an address the caller holds a join reference to, visible or not. */
    public java.util.Optional<String> joinedSkillId(String userId, String namespaceId, String name) {
        return repository.joinedSkillId(userId, namespaceId, name);
    }

    /**
     * What the caller asked the library for.
     *
     * <p>Public because the use-case layer builds it: it is the request shape, not the SQL. The
     * repository's method signatures name this record, so leaving it in {@code internal} would make
     * every caller reach into the module's private package (which the architecture rule forbids).
     *
     * @param show   live / draft / offline / all / deleted. The default is {@code live}: the
     *               consumable library, which is the CLI's default (PRD §CLI 命令面)
     * @param byName false orders by the list's "更新于", true by name. Two orderings, two keysets
     * @param offset used by the browser's page-number listing; the API passes null and a cursor
     */
    public record Query(String q, List<String> namespaceIds, List<String> categoryKeys,
            List<String> myTagIds, List<String> publicTagKeys, List<String> sources, String show,
            boolean byName, List<String> afterKey, Integer offset, int limit) {
    }
}
