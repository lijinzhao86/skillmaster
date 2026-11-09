package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialLease;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a value is decrypted, and the only answer that carries plaintext.
 *
 * <p>It is a write transaction even though the caller is reading: the daily use aggregate and
 * {@code last_used_at} move here. A quota refusal rolls the whole lease back, so a run that fails
 * on the second credential does not consume the first one's day.
 *
 * <p>Nothing in this class logs. The response is {@code no-store}; the plaintext lives only long
 * enough to be serialised into that one answer.
 */
@Component
public class LeaseCredentialsUseCase {

    /** One run is enough; nothing renews a lease, and an expiring one is re-requested by a re-run. */
    public static final Duration TTL = Duration.ofMinutes(5);

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;

    public LeaseCredentialsUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
    }

    /** @return empty when the skill names nothing the caller may invoke */
    @Transactional
    public Optional<CredentialLease> lease(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.bySlugAndName(namespaceSlug, name, subject, namespaces, metadata);
        if (skill.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        String at = Timestamps.format(now);
        String expiresAt = Timestamps.format(now.plus(TTL));
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        // **A share outlives the collaborator unless something re-checks it.** The share row is
        // written against a binding and stays until somebody revokes it; the person's grant can be
        // withdrawn independently. M12's own answer carries where the access came from, so the
        // question "is this person still a collaborator" is asked here, once, with the same
        // predicate that decided they could invoke at all.
        boolean sharesAreValid = "shared".equals(skill.get().access().source());
        return Optional.of(secrets.lease(subject.userId(), skill.get().skillId(), day, at,
                expiresAt, sharesAreValid));
    }
}
