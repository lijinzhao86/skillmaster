package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.token.ApiKey;
import com.skillmasterai.modules.token.ApiKeyPolicy;
import com.skillmasterai.modules.token.ApiKeyRequestException;
import com.skillmasterai.modules.token.ApiKeys;
import com.skillmasterai.modules.token.IssuedApiKey;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replacing one of the caller's keys with a fresh one that continues it (ADR 0039 §决定 3).
 *
 * <p><strong>What carries over and what does not.</strong> The name, the note and the scopes stay —
 * the key is still "the one on the build server". The date does not: what comes out is a new
 * credential, so it takes the date a new credential takes. That is not a style choice. A renewal is
 * what a person does when a key is about to stop working, so a renewal that copied the old date
 * forward would hand them a key that is already expired and leave the console with nowhere else to
 * change it.
 *
 * <p><strong>A revoked key cannot be renewed.</strong> There is nothing to continue: revocation is a
 * decision that this credential must not work, and renewing it would be a way to undo that decision
 * without saying so. The answer is to issue a new one, which is what the refusal says.
 *
 * <p>The whole thing is one transaction, opened here rather than in M2 (§2.5 rule 2): the old row is
 * marked and the new one inserted inside it, so there is no window in which both work — a period
 * with two live credentials is a period with nobody answerable for the second one.
 */
@Component
public class RenewApiKeyUseCase {

    private final ApiKeys keys;
    private final ApiKeyPolicy policy;

    public RenewApiKeyUseCase(ApiKeys keys, ApiKeyPolicy policy) {
        this.keys = keys;
        this.policy = policy;
    }

    /**
     * @return the new key and its secret, or empty when this account has no such key — the console's
     *         404, and the same absence as a key belonging to somebody else
     * @throws ApiKeyRequestException when the key exists and is revoked
     */
    @Transactional
    public Optional<IssuedApiKey> renew(String id, AuthenticatedSubject subject) {
        Optional<ApiKey> found = keys.find(subject.userId(), id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (found.get().revokedAt() != null) {
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.REVOKED, "revoked");
        }
        // Read here rather than by M2, for the same reason issuance is: when a key should expire is
        // this deployment's decision, and M2 is where the number is stored, not where it is chosen.
        Instant expiresAt = policy.defaultExpiry(Instant.now());
        Optional<IssuedApiKey> renewed = keys.rotate(subject.userId(), id, expiresAt);
        if (renewed.isPresent()) {
            return renewed;
        }
        // **Empty here can only mean one thing**, and it is not "no such key": the read above found
        // this row, live, and `rotate` reports empty only when it lost the race to stop it — the
        // other request of a double submission, or somebody revoking from another tab between the two
        // statements. Answering 404 would be a claim about the address; what actually happened is
        // that this key has just been revoked, which is the answer this command already gives a
        // revoked key, and the one the person's next attempt will get. The transaction rolls back, so
        // nothing of this attempt is left behind.
        throw new ApiKeyRequestException(ApiKeyRequestException.Problem.REVOKED, "revoked");
    }
}
