package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.ThrottledException;
import com.skillmasterai.modules.account.UsernamePolicy;
import com.skillmasterai.modules.namespace.NamespaceService;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers whether a candidate username can still be taken, for a form that asks as soon as the field
 * is left rather than at submit.
 *
 * <p><strong>Two tables have to be asked, and asking only one is wrong.</strong> A registered user's
 * handle is in {@code app_user}, but registration's guard is two UNIQUE constraints, and the other one
 * is on {@code namespace.slug} — so a name can be spoken for with no user behind it, and a check
 * against accounts alone would answer "free" for a name the submit then refuses, after a text message
 * has been sent to pay for the discovery. That is the one case where being late is worse than saying
 * nothing.
 *
 * <p>Which is <em>not</em> how the seeded reserved name works, though the two are easy to confuse: the
 * {@code skillmaster} namespace is reachable through its owner, because {@code V2} seeds an account
 * whose handle is that very name — deliberately, so the reserved name is held by the
 * {@code UNIQUE(app_user.handle)} constraint rather than by a deny list somebody has to remember to
 * consult. The namespace read is what covers a slug that no handle matches, which nothing in v1
 * creates but the schema permits and an organisation namespace would.
 *
 * <p><strong>This is not an account-existence oracle, and the difference is what a username is.</strong>
 * A username is a public identifier: it appears as the first segment of every address its owner has
 * published, on every search card, and in every access log. Asking which ones are taken tells a caller
 * nothing that reading one published address would not. That is not true of the login endpoint's
 * answers, which is why those are deliberately all the same — see {@code ErrorCode.INVALID_CREDENTIALS}.
 */
@Component
public class CheckUsernameUseCase {

    private final AccountRegistrar accounts;
    private final NamespaceService namespaces;
    private final AuthThrottle throttles;

    public CheckUsernameUseCase(AccountRegistrar accounts, NamespaceService namespaces,
            AuthThrottle throttles) {
        this.accounts = accounts;
        this.namespaces = namespaces;
        this.throttles = throttles;
    }

    /**
     * @return the issue code the username would be refused with, or empty when it is free
     * @throws ThrottledException when the caller's address has spent its lookups for the window
     */
    public Optional<String> problemWith(String handle, String clientIp) {
        Optional<String> malformed = UsernamePolicy.problemWith(handle);
        if (malformed.isPresent()) {
            // Ahead of the counter because it costs nothing: whether a name is even the right shape
            // is a regular expression, while the question this endpoint exists to answer is two
            // reads. Somebody correcting a typo should not spend one of their own lookups on a
            // request that never reached a table.
            return malformed;
        }
        if (throttles.countUsernameLookup(clientIp) instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        return taken(handle) ? Optional.of("already_taken") : Optional.empty();
    }

    private boolean taken(String handle) {
        return accounts.handleTaken(handle) || namespaces.slugExists(handle);
    }
}
