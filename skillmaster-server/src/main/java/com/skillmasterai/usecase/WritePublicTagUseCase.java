package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillRequestException;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.TagNames;
import com.skillmasterai.modules.skill.TagNotAttachedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaching and detaching an author's public tag (v14, ADR 0083).
 *
 * <p><strong>Owner or {@code admin} only.</strong> A public tag is the author's description of
 * their own skill; every other rung reads it and nothing else. The refusal is a 403 rather than a
 * 404 because the caller can already see the skill — the same rule the sharing routes follow.
 *
 * <p>A repeat is a no-op on the normalized key, and the stored spelling is the one that was there
 * first. The ceiling is ten per skill, under the skill's advisory lock.
 */
@Component
public class WritePublicTagUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillTagService tags;
    private final AuditLog audit;

    public WritePublicTagUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillTagService tags, AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.tags = tags;
        this.audit = audit;
    }

    /**
     * @return empty for an address the caller may not see; otherwise whether the word was added —
     *         false means it was already there, which the client reports as "已经贴过"
     */
    @Transactional
    public Optional<Boolean> attach(String namespaceSlug, String name, String rawTag,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var standing = resolved.get().standing();
        requireAuthor(standing);

        TagNames.Named word = tags.parseName(rawTag);
        if (tags.categoryNameTaken(word.key())) {
            throw new SkillRequestException("name", "category_name_taken");
        }

        tags.lockTagsOf(standing.skillId());
        if (tags.publicTagExists(standing.skillId(), word.key())) {
            return Optional.of(false);
        }
        if (tags.countPublicTags(standing.skillId())
                >= SkillTagService.MAX_PUBLIC_TAGS_PER_SKILL) {
            throw new SkillRequestException("name", "tag_limit_reached");
        }
        tags.attachPublic(standing.skillId(), word.key(), word.name(), Timestamps.now());
        audit.record(new AuditEvent(subject.userId(), "tag_public_add", "skill", standing.skillId(),
                detail(word.key())));
        return Optional.of(true);
    }

    /** @return false when the address names nothing the caller may see (the controller's 404) */
    @Transactional
    public boolean detach(String namespaceSlug, String name, String rawTag,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return false;
        }
        var standing = resolved.get().standing();
        requireAuthor(standing);

        TagNames.Named word = tags.parseName(rawTag);
        if (!tags.detachPublic(standing.skillId(), word.key())) {
            throw new TagNotAttachedException();
        }
        audit.record(new AuditEvent(subject.userId(), "tag_public_remove", "skill",
                standing.skillId(), detail(word.key())));
        return true;
    }

    private static void requireAuthor(com.skillmasterai.modules.skill.SkillStanding standing) {
        if (!standing.mayOrganize()) {
            throw new NotPermittedException(
                    "public tags on '" + standing.name() + "' are its owner's or an admin's to set");
        }
    }

    private static Map<String, Object> detail(String key) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tag_key", key);
        return detail;
    }
}
