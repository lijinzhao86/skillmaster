package com.skillmasterai.usecase;

import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the search's {@code exclude} addresses into skill ids (v30).
 *
 * <p><strong>Address parsing lives here rather than in the controller</strong>, because resolution
 * needs two modules — M4 answers the namespace slug, M12 the name — and a use case is the layer that
 * is allowed to name both. The controller keeps the HTTP shape.
 *
 * <p><strong>An address that resolves to nothing is dropped, not refused.</strong> Excluding a skill
 * that would not have appeared is a no-op, and refusing would turn a stale list from earlier in a
 * conversation into a failed search — the exact case this feature exists for. Malformed addresses
 * and too many of them are the caller's mistakes, so those are 400s.
 */
@Component
public class ResolveExcludedSkillsUseCase {

    /** The same ceiling the CLI enforces; here so a direct API caller gets one contract. */
    public static final int MAX_EXCLUDED = 50;

    private final NamespaceService namespaces;
    private final SkillMetadataService skills;

    public ResolveExcludedSkillsUseCase(NamespaceService namespaces, SkillMetadataService skills) {
        this.namespaces = namespaces;
        this.skills = skills;
    }

    /**
     * @param addresses {@code namespace/name}, no version; null or empty means "exclude nothing"
     * @return the ids that exist, de-duplicated, in the caller's order. Never null
     * @throws InvalidSearchRequestException for a malformed address or more than
     *         {@link #MAX_EXCLUDED} of them — the caller's to fix, so a 400
     */
    @Transactional(readOnly = true)
    public List<String> idsFor(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return List.of();
        }
        // **Deduplicated before the ceiling.** The ceiling bounds what the request means, not how
        // many times a client repeated itself: `?exclude=a/b` fifty-one times names one address.
        // **Blank values are skipped rather than refused** — an SDK that always emits `exclude=`
        // names nothing, and the documented rule for an address that names nothing is to ignore it.
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String address : addresses) {
            String trimmed = address == null ? "" : address.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int slash = trimmed.indexOf('/');
            // Exactly one slash, both sides non-empty, no version: an exclude is a skill, never one
            // of its files or one of its versions.
            if (slash <= 0 || slash == trimmed.length() - 1
                    || trimmed.indexOf('/', slash + 1) >= 0 || trimmed.indexOf('@') >= 0) {
                throw new InvalidSearchRequestException(
                        "exclude must be namespace/name without a version, was '" + address + "'");
            }
            distinct.add(trimmed);
        }
        if (distinct.size() > MAX_EXCLUDED) {
            throw new InvalidSearchRequestException(
                    "exclude accepts at most " + MAX_EXCLUDED + " addresses");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String address : distinct) {
            int slash = address.indexOf('/');
            String slug = address.substring(0, slash);
            String name = address.substring(slash + 1);
            namespaces.bySlug(slug)
                    .flatMap(namespace -> skills.idOf(namespace.id(), name))
                    .ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }
}
