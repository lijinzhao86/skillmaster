package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialBinding;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes an existing credential to one more {@code (skill, env)}.
 *
 * <p>A collaborator cannot do this: the credential must be the caller's, and the rule that one
 * owner has at most one binding on a given {@code (skill, env)} is checked before the insert.
 */
@Component
public class BindCredentialUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public BindCredentialUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AuditLog audit) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.audit = audit;
    }

    /** @return empty when the skill address names nothing the caller may invoke */
    @Transactional
    public Optional<Result> bind(String credentialId, String skillAddress, String env,
            AuthenticatedSubject subject) {
        SecretSkills.owned(secrets, credentialId, subject.userId());
        SecretSkills.requireEnvName(env);
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
        if (skill.isEmpty()) {
            return Optional.empty();
        }
        SecretSkills.Resolved resolved = skill.get();
        SecretSkills.requireNoOwnBinding(
                secrets.hasOwnBinding(subject.userId(), resolved.skillId(), env), env);

        CredentialBinding binding = secrets.bind(credentialId, resolved.skillId(), env,
                Timestamps.now());
        audit.record(new AuditEvent(subject.userId(), "credential_bind", "credential",
                credentialId, Map.of("skillId", resolved.skillId(), "env", env)));
        return Optional.of(new Result(skillAddress, binding.envName()));
    }

    public record Result(String skill, String env) {
    }
}
