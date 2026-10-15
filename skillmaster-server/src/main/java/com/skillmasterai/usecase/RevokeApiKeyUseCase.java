package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.token.ApiKey;
import com.skillmasterai.modules.token.ApiKeys;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stopping one of the caller's keys (ADR 0038 §决定 2).
 *
 * <p><strong>Idempotent, and the two ways it can hit nothing are answered differently.</strong> A
 * key that is not there — or is not the caller's, which is the same absence here — is a 404, because
 * a console showing a row that has since gone should be told. Revoking one that was already revoked
 * is a success with no new timestamp: the person's intent is already the state of the world, and the
 * listing's answer to "when did it stop working" must not move because they clicked twice.
 *
 * <p>The read happens before the write so that the 404 and the idempotent 204 can be told apart at
 * all; {@link ApiKeys#revoke} deliberately has no opinion about a key that is not there.
 */
@Component
public class RevokeApiKeyUseCase {

    private final ApiKeys keys;

    public RevokeApiKeyUseCase(ApiKeys keys) {
        this.keys = keys;
    }

    /**
     * @return the key as it was found, or empty when this account has no such key — which the console
     *         renders as 404. The key is returned rather than a boolean so that a caller which wants to
     *         say <em>what</em> it just revoked does not have to read it a second time
     */
    @Transactional
    public Optional<ApiKey> revoke(String id, AuthenticatedSubject subject) {
        Optional<ApiKey> found = keys.find(subject.userId(), id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        keys.revoke(subject.userId(), id);
        return found;
    }
}
