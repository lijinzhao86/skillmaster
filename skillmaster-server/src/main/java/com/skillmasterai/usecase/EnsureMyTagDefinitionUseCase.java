package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.skill.SkillTagService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way a private-tag definition comes into existence (v14, TD §并发与上限).
 *
 * <p><strong>Both the explicit create and the auto-create during an attach come through here.</strong>
 * The 100-definition ceiling is a cross-row count, so it is taken under the account's advisory lock.
 * Letting a tag attach create a definition on its own would be a second path that forgot the lock —
 * and the ceiling would hold for a person who used the form and fail for one who used the tag box.
 *
 * <p>The lock is transaction-scoped and taken before any read of the count. The use case's
 * transaction may already hold the skill lock when this is called from an attach; the order is
 * always user first, then skill, which is the order that cannot deadlock with itself.
 */
@Component
public class EnsureMyTagDefinitionUseCase {

    private final SkillTagService tags;
    private final AuditLog audit;

    public EnsureMyTagDefinitionUseCase(SkillTagService tags, AuditLog audit) {
        this.tags = tags;
        this.audit = audit;
    }

    /**
     * Creates a definition with an optional sentence.
     *
     * @throws com.skillmasterai.modules.skill.SkillRequestException for a taken name, a shelf's
     *         name, a description over 200 code points, or the 101st definition
     */
    @Transactional
    public SkillTagService.Definition create(String rawName, String rawDescription,
            AuthenticatedSubject subject) {
        return createLocked(subject.userId(), rawName, rawDescription);
    }

    /**
     * Returns the definition for this word, creating one with no description if there is none.
     *
     * <p>No description is invented: the CLI's {@code skill tag add} creates a bare definition, and
     * the product says so (PRD §CLI 命令面). An author who wants a sentence uses
     * {@code skills my-tags add --describe} or the console.
     */
    @Transactional
    public SkillTagService.Definition ensureForAttach(String userId, String rawName) {
        return ensureLocked(userId, rawName);
    }

    private SkillTagService.Definition createLocked(String userId, String rawName,
            String rawDescription) {
        com.skillmasterai.modules.skill.TagNames.Named name = tags.parseName(rawName);
        String description = tags.parseDescription(rawDescription);
        tags.lockDefinitionsOf(userId);
        tags.requireNameAvailable(userId, name.key(), null);
        tags.requireDefinitionUnderLimit(userId);

        String id = Ulid.generate();
        String at = Timestamps.now();
        tags.insertDefinition(id, userId, name.name(), name.key(), description, at);
        recordCreated(userId, id, name.name().codePointCount(0, name.name().length()),
                description);
        return new SkillTagService.Definition(id, name.name(), name.key(), description);
    }

    private SkillTagService.Definition ensureLocked(String userId, String rawName) {
        com.skillmasterai.modules.skill.TagNames.Named name = tags.parseName(rawName);
        tags.lockDefinitionsOf(userId);

        Optional<SkillTagService.Definition> existing =
                tags.findDefinitionByName(userId, name.key());
        if (existing.isPresent()) {
            return existing.get();
        }
        tags.requireNameAvailable(userId, name.key(), null);
        tags.requireDefinitionUnderLimit(userId);

        String id = Ulid.generate();
        tags.insertDefinition(id, userId, name.name(), name.key(), null, Timestamps.now());
        recordCreated(userId, id, name.name().codePointCount(0, name.name().length()), null);
        return new SkillTagService.Definition(id, name.name(), name.key(), null);
    }

    private void recordCreated(String userId, String id, int nameLength, String description) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        detail.put("name_length", nameLength);
        if (description != null) {
            detail.put("description_length", description.codePointCount(0, description.length()));
        }
        audit.record(new AuditEvent(userId, "tag_definition_create", "my_tag", id, detail));
    }
}
