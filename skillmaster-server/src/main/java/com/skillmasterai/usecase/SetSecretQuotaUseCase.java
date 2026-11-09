package com.skillmasterai.usecase;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.secret.CredentialRef;
import com.skillmasterai.modules.secret.SecretService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves the daily lease ceiling. The range is the product's (1..10,000), and raising it is a
 * decision the user makes — the CLI asks for confirmation before it sends the request; this class
 * only enforces the boundary.
 */
@Component
public class SetSecretQuotaUseCase {

    private final SecretService secrets;

    public SetSecretQuotaUseCase(SecretService secrets) {
        this.secrets = secrets;
    }

    @Transactional
    public Result set(String credentialId, int perDay, AuthenticatedSubject subject) {
        if (perDay < 1 || perDay > 10_000) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "per_day must be between 1 and 10000");
        }
        SecretSkills.owned(secrets, credentialId, subject.userId());
        CredentialRef changed = secrets.setPerDayLimit(credentialId, perDay);
        return new Result(changed.id(), changed.perDayLimit());
    }

    public record Result(String id, int perDay) {
    }
}
