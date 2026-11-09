package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.secret.SecretService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard delete. Bindings, daily counts and shares go with it through the foreign keys, so every
 * skill that reached it stops receiving a value on its next run.
 *
 * <p>The audit row is the history the delete leaves behind; the product's deletion promise is about
 * the live row, and backups expire on the documented schedule.
 */
@Component
public class DeleteSecretUseCase {

    private final SecretService secrets;
    private final AuditLog audit;

    public DeleteSecretUseCase(SecretService secrets, AuditLog audit) {
        this.secrets = secrets;
        this.audit = audit;
    }

    @Transactional
    public void delete(String credentialId, AuthenticatedSubject subject) {
        SecretSkills.owned(secrets, credentialId, subject.userId());
        secrets.delete(credentialId);
        audit.record(AuditEvent.of(subject.userId(), "credential_delete", "credential",
                credentialId));
    }
}
