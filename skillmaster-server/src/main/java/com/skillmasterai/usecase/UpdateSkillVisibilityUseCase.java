package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillRequestException;
import com.skillmasterai.modules.skill.SkillSharingService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes a skill's visibility — private, or public at one of the two lowest rungs (ADR 0048).
 *
 * <p><strong>Two states plus a rung, not three values, and this is the place the pair is
 * validated.</strong> The database refuses any other combination (V19's CHECK); saying so here in
 * the caller's terms is what turns a constraint violation into a 400 naming the field. The rung
 * vocabulary is {@link GrantRole}'s, restricted to the two a stranger may be handed: handing out
 * {@code write} to everybody would be a different product decision, not a visibility setting.
 *
 * <p>The gate is {@code admin}, and it is checked in {@link SkillSharingService#setVisibility}
 * through the same predicate every other operation uses.
 */
@Component
public class UpdateSkillVisibilityUseCase {

    private final NamespaceService namespaces;
    private final SkillSharingService sharing;
    private final AuditLog audit;

    public UpdateSkillVisibilityUseCase(NamespaceService namespaces, SkillSharingService sharing,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.sharing = sharing;
        this.audit = audit;
    }

    /**
     * @param visibility {@code private} or {@code public}
     * @param publicRung {@code invoke} or {@code browse} when public; null when private
     * @return the skill's id, or empty when the caller cannot see it — §4.1's one 404
     * @throws SkillRequestException for a value outside the two states or the two rungs
     */
    @Transactional
    public Optional<String> update(String namespaceSlug, String name, String visibility,
            String publicRung, AuthenticatedSubject subject) {
        String normalisedRung = validateAndNormalise(visibility, publicRung);

        Optional<String> updated = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> sharing.setVisibility(namespace.id(), name, visibility,
                        normalisedRung, Callers.of(namespaces, subject), Timestamps.now()));

        updated.ifPresent(skillId -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("visibility", visibility);
            detail.put("public_rung", normalisedRung == null ? "" : normalisedRung);
            audit.record(new AuditEvent(subject.userId(), "visibility", "skill", skillId, detail));
        });
        return updated;
    }

    /**
     * @return the rung to store: the value when public, null when private
     */
    private static String validateAndNormalise(String visibility, String publicRung) {
        if ("private".equals(visibility)) {
            if (publicRung != null) {
                throw new SkillRequestException("public_rung", "must_be_absent_when_private");
            }
            return null;
        }
        if ("public".equals(visibility)) {
            if (!GrantRole.INVOKE.equals(publicRung) && !GrantRole.BROWSE.equals(publicRung)) {
                throw new SkillRequestException("public_rung", "must_be_invoke_or_browse");
            }
            return publicRung;
        }
        throw new SkillRequestException("visibility", "must_be_private_or_public");
    }
}
