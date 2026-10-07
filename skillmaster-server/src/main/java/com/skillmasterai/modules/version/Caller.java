package com.skillmasterai.modules.version;

import com.skillmasterai.common.Ulid;

/**
 * Who a skill is being looked up for — the two facts the access predicate is built from.
 *
 * <p><strong>Two, because the predicate is a disjunction</strong> (ADR 0034): the caller may act on
 * a skill because it is in a namespace they own, <em>or</em> because that one skill was granted to
 * them. The namespace half comes from M4, the user half from the authenticated subject, and M7 is
 * where they are spent — so they travel together.
 *
 * <p><strong>A record rather than two parameters</strong>, and not for tidiness. Both fields are
 * ULIDs: a signature taking them side by side would let a caller transpose them and still compile,
 * and the result would be a lookup made on behalf of somebody else. The type makes that
 * unrepresentable rather than merely unlikely — the same reasoning that deleted
 * {@code SessionOptions.ClientID} from the CLI.
 *
 * <p>Not a scope, and not a role. M7 can say whether a caller <em>may</em> do something; whether
 * they <em>did</em> ask with a token that permits it is the filter chain's business, and the two are
 * deliberately not merged ({@code AuthenticatedSubject}'s note says why: an authorization failure
 * here has to render as 404, not 403, so it cannot be decided by the security layer).
 *
 * @param userId         the account asking
 * @param ownNamespaceId the one namespace that account owns. v1 creates exactly one at registration
 *                       (§3.2); when membership arrives this becomes a set, and the change is this
 *                       field's type rather than every signature that carries it
 */
public record Caller(String userId, String ownNamespaceId) {

    public Caller {
        Ulid.requireValid(userId, "caller's user id");
        Ulid.requireValid(ownNamespaceId, "caller's namespace id");
    }
}
