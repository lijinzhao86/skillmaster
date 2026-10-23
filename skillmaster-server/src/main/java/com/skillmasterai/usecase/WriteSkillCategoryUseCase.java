package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillCategoryService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Setting or clearing one skill's shelf (v14, ADR 0078).
 *
 * <p><strong>One shelf, and setting replaces rather than appends.</strong> The column says so; this
 * use case does not add a second row anywhere.
 *
 * <p><strong>Stopped words keep working.</strong> A stopped word cannot be newly assigned, but an
 * assignment already on a skill stays readable, filterable and removable — and re-setting it is a
 * no-op rather than a refusal, because nothing would change. Retired words are refused and do not
 * appear in the vocabulary at all.
 *
 * <p>Owner or {@code admin}, like the public tags. Filing a skill is not a content change: no
 * version is made and {@code updated_at} does not move.
 */
@Component
public class WriteSkillCategoryUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillCategoryService categories;
    private final AuditLog audit;

    public WriteSkillCategoryUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillCategoryService categories, AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.categories = categories;
        this.audit = audit;
    }

    /**
     * @return empty for an address the caller may not see; otherwise whether the shelf moved —
     *         false means it was already this one
     */
    @Transactional
    public Optional<Boolean> set(String namespaceSlug, String name, String key,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var standing = resolved.get().standing();
        requireAuthor(standing);

        if (key == null || key.isBlank()) {
            throw new SkillRequestException("key", "missing");
        }
        SkillCategoryService.CategoryRow category = categories.byKey(key)
                .orElseThrow(() -> new SkillRequestException("key", "unknown_category"));
        if ("retired".equals(category.state())) {
            throw new SkillRequestException("key", "category_retired");
        }

        String before = categories.categoryOf(standing.skillId());
        // **A stopped word that is already assigned is a no-op, not a refusal** (PRD #42), so this
        // early return is about the word's state, not about racing: an enabled word's repeat falls
        // through to the conditional UPDATE, whose answer is correct even if another request
        // changed the column between this read and the write.
        if ("stopped".equals(category.state())) {
            if (key.equals(before)) {
                return Optional.of(false);
            }
            throw new SkillRequestException("key", "category_stopped");
        }

        // The conditional write is the answer: a concurrent request that set the same key first
        // wins, and this one reports the no-op it turned into.
        if (!categories.set(standing.skillId(), key)) {
            return Optional.of(false);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if (before != null) {
            detail.put("before", before);
        }
        detail.put("after", key);
        audit.record(new AuditEvent(subject.userId(), "category_set", "skill", standing.skillId(),
                detail));
        return Optional.of(true);
    }

    /**
     * @return empty for an address the caller may not see; otherwise whether a shelf was removed —
     *         false means there was none
     */
    @Transactional
    public Optional<Boolean> clear(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var standing = resolved.get().standing();
        requireAuthor(standing);

        String before = categories.categoryOf(standing.skillId());
        // The conditional UPDATE answers "was there a shelf", including when another request
        // cleared or set it after this read.
        if (!categories.clear(standing.skillId())) {
            return Optional.of(false);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if (before != null) {
            detail.put("before", before);
        }
        audit.record(new AuditEvent(subject.userId(), "category_clear", "skill",
                standing.skillId(), detail));
        return Optional.of(true);
    }

    private static void requireAuthor(com.skillmasterai.modules.skill.SkillStanding standing) {
        if (!standing.mayOrganize()) {
            throw new NotPermittedException(
                    "the category of '" + standing.name() + "' is its owner's or an admin's to set");
        }
    }
}
