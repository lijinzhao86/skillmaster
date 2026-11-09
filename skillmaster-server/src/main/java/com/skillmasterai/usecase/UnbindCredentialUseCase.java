package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialBindingNotFoundException;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Removes one binding; the value and its other bindings stay. */
@Component
public class UnbindCredentialUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AuditLog audit;

    public UnbindCredentialUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AuditLog audit) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.audit = audit;
    }

    /** @return false when the skill address names nothing the caller may invoke */
    @Transactional
    public boolean unbind(String credentialId, String skillAddress, String env,
            AuthenticatedSubject subject) {
        SecretSkills.owned(secrets, credentialId, subject.userId());
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
        if (skill.isEmpty()) {
            return false;
        }
        SecretSkills.Resolved resolved = skill.get();
        if (!secrets.unbind(credentialId, resolved.skillId(), env)) {
            // **No audit line for something that did not happen.** A 204 plus a "unbound" record is
            // worse than either alone: the table and the audit disagree, and the caller believes a
            // variable stopped travelling when it did not.
            throw new CredentialBindingNotFoundException();
        }
        audit.record(new AuditEvent(subject.userId(), "credential_unbind", "credential",
                credentialId, Map.of("skillId", resolved.skillId(), "env", env)));
        return true;
    }
}
