package com.skillmasterai.usecase;

import com.skillmasterai.modules.token.StaleApiKeys;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily "unused for a year" sweep (ADR 0171 §决定 6).
 *
 * <p><strong>The transaction is here rather than in M2</strong> because modules do not declare
 * transactions (rules §一). The module writes the revoked rows and their audit rows inside the
 * boundary this opens, so a crash mid-sweep cannot leave a key revoked with no record of why.
 *
 * <p>It is a cleanup, not a reminder: nothing is sent before a key disappears. The console's
 * 「两周内到期」 count covers dated keys; a key with no date that nobody uses simply goes away after
 * its year, which is the cost ADR 0171 §后果 records.
 */
@Component
public class RevokeUnusedApiKeysUseCase {

    /** 365 days, in the same day-based arithmetic the rest of the key lifetime uses. */
    static final Duration UNUSED_FOR = Duration.ofDays(365);

    private final StaleApiKeys keys;

    // **Qualified because the same registry is both faces.** `ApiKeyRegistry` implements `ApiKeys`
    // and `StaleApiKeys`, so Spring sees two beans assignable to `StaleApiKeys` — the user face and
    // the system face. Naming the system one is what keeps the sweep from being ambiguous.
    public RevokeUnusedApiKeysUseCase(@Qualifier("staleApiKeys") StaleApiKeys keys) {
        this.keys = keys;
    }

    /**
     * @param now passed rather than read, so a test can put the boundary exactly where it wants it
     * @return how many keys were revoked
     */
    @Transactional
    public int revokeUnused(Instant now) {
        return keys.revokeUnusedBefore(now.minus(UNUSED_FOR));
    }
}
