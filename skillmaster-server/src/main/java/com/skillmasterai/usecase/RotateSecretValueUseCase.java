package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.secret.CredentialRef;
import com.skillmasterai.modules.secret.CredentialValue;
import com.skillmasterai.modules.secret.SecretService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the value; bindings and shares survive because they name the credential, not the bytes.
 *
 * <p>One audit row: a rotation is a security-relevant event (the old value may be compromised), but
 * it is not a delete — every skill bound to this credential keeps working and starts receiving the
 * new value on its next run.
 */
@Component
public class RotateSecretValueUseCase {

    private final SecretService secrets;
    private final AuditLog audit;

    public RotateSecretValueUseCase(SecretService secrets, AuditLog audit) {
        this.secrets = secrets;
        this.audit = audit;
    }

    @Transactional
    public Result rotate(String credentialId, String value, AuthenticatedSubject subject) {
        SecretSkills.owned(secrets, credentialId, subject.userId());
        SecretSkills.requireNonEmptyValue(value);
        CredentialRef rotated = secrets.rotate(credentialId, CredentialValue.of(value),
                Timestamps.now());
        audit.record(AuditEvent.of(subject.userId(), "credential_rotate", "credential",
                credentialId));
        return new Result(rotated.id(), rotated.rotatedAt());
    }

    public record Result(String id, String rotatedAt) {
    }
}
