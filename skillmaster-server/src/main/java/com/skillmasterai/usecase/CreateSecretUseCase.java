package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialRef;
import com.skillmasterai.modules.secret.CredentialValue;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One value, and its first binding: {@code skmr secret set --skill … --env …}.
 *
 * <p>The two writes are one transaction because half a create — a value nobody can reach, or a
 * binding to a credential that was rolled back — is not a state the product has a name for.
 */
@Component
public class CreateSecretUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public CreateSecretUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AuditLog audit) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.audit = audit;
    }

    /** @return empty when the skill address names nothing the caller may invoke */
    @Transactional
    public Optional<Result> create(String name, String value, String skillAddress, String env,
            AuthenticatedSubject subject) {
        SecretSkills.requireEnvName(env);
        SecretSkills.requireNonEmptyValue(value);
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
        if (skill.isEmpty()) {
            return Optional.empty();
        }
        SecretSkills.Resolved resolved = skill.get();
        SecretSkills.requireNoOwnBinding(
                secrets.hasOwnBinding(subject.userId(), resolved.skillId(), env), env);

        String label = name == null || name.isBlank() ? env : name.trim();
        String at = Timestamps.now();
        CredentialRef credential = secrets.create(subject.userId(), label,
                CredentialValue.of(value), at);
        secrets.bind(credential.id(), resolved.skillId(), env, at);

        audit.record(new AuditEvent(subject.userId(), "credential_create", "credential",
                credential.id(), Map.of("skillId", resolved.skillId(), "env", env)));
        return Optional.of(new Result(credential.id(), credential.name(),
                credential.perDayLimit(), 0, credential.createdAt(),
                List.of(new BindingView(skillAddress, env))));
    }

    public record Result(String id, String name, int perDay, int used, String createdAt,
            List<BindingView> bindings) {
        public Result {
            bindings = List.copyOf(bindings);
        }
    }

    /** @param skill {@code namespace/name} */
    public record BindingView(String skill, String env) {
    }
}
