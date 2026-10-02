package com.skillmasterai.modules.namespace;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.namespace.internal.NamespaceRepository;
import java.util.Optional;

/**
 * M4's public face.
 *
 * <p>What is <em>not</em> here is as deliberate as what is: there is no "may this caller read this
 * skill" method, because that decision has to be made where the skill is known and its answer has
 * to be rendered as 404 rather than 403 (§4.2). A predicate exposed here would invite the
 * authorization layer to turn it into a 403, which is the mistake that rule exists to prevent.
 *
 * <p>What <em>is</em> here is the narrower question — which namespace may this caller read from — as
 * {@link #readableNamespaceOf}. It answers a fact about the namespace, not a verdict about a skill,
 * and the callers still hand the namespace to M7 to filter on, so the 404 rule is untouched.
 * Resolving it here rather than in each use case is what keeps "a caller reads only their own
 * namespace" one rule instead of one branch per endpoint.
 */
public final class NamespaceService {

    private final NamespaceRepository repository;
    private final AccountDirectory accounts;

    public NamespaceService(NamespaceRepository repository, AccountDirectory accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    /**
     * The user's personal namespace: the one they own whose slug is their handle (§3.2).
     *
     * <p>Two calls rather than one join, because the handle is M1's column and this is M4 — see
     * {@link NamespaceRepository#findByOwnerAndSlug}. Both are primary-key lookups.
     *
     * @throws IllegalStateException if the user does not exist, or exists without a personal
     *         namespace. Neither is a client error: registration creates the user and their
     *         namespace atomically, so either means the database is in a state the application
     *         never writes. Quietly returning empty would turn that into a 404 for a caller who
     *         did nothing wrong.
     */
    public Namespace personalNamespaceOf(String userId) {
        String handle = accounts.handleOf(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "no user " + userId + "; a token's subject must name one"));
        return repository.findByOwnerAndSlug(userId, handle)
                .orElseThrow(() -> new IllegalStateException(
                        "user " + userId + " has no personal namespace; registration creates one"));
    }

    /**
     * The caller's own namespace, when the address named it — or nothing, when it named another.
     *
     * <p>v1 gives every caller exactly one namespace and its slug is their handle, so the first
     * segment of an address either agrees with it or names a namespace the caller has no
     * relationship with. The second case is <strong>not an authorization failure</strong>: §4.2
     * renders an unreadable private skill as 404 rather than 403, and "that namespace is not yours"
     * has to be the same kind of nothing as "no such skill", so it is empty rather than a no.
     *
     * <p>This is not a general "may I see this namespace" query, and it does not replace the
     * predicate in {@link com.skillmasterai.modules.version.internal.SkillRepository}: callers still
     * pass the namespace they may read from into M7, which filters on it in the statement that finds
     * the row. When sharing and public discovery arrive (§3.2) this is what changes — into a lookup
     * of "a namespace this user owns", so that somebody else's is not a value the use case can even
     * build.
     */
    public Optional<Namespace> readableNamespaceOf(String userId, String slug) {
        Namespace own = personalNamespaceOf(userId);
        return own.slug().equals(slug) ? Optional.of(own) : Optional.empty();
    }

    /**
     * Creates the user's personal namespace: the one they own whose slug is their handle (§3.2).
     *
     * <p>Called from registration, inside the same transaction that writes the account. The two
     * must both exist or neither — an account without a namespace has nowhere to publish, and
     * {@link #personalNamespaceOf} treats that state as one the application never writes, so a
     * half-done registration would surface later as a 500 on somebody's first publish.
     *
     * @return false when something already owns that slug. The caller reports it as the username
     *         being taken, and that is not a conflation: the slug <em>is</em> the handle, so a slug
     *         collision and a handle collision are the same event seen from two tables
     */
    public boolean createPersonalNamespace(String ownerUserId, String handle) {
        return repository.createPersonalNamespace(ownerUserId, handle);
    }

    /**
     * A namespace by its slug, whoever owns it.
     *
     * <p>For the reserved namespace the gateway skill lives in. Note what this is <em>not</em>: a
     * way to read someone else's skills. It resolves a namespace, and every caller still has to
     * state which namespace it is willing to read from — see {@code SkillVersionService}.
     *
     * @throws IllegalStateException if there is no such namespace. Slugs are seeded or created at
     *         registration, so a missing one means the deployment is not the one the caller assumes
     */
    public Namespace namespaceOfSlug(String slug) {
        return repository.findBySlug(slug)
                .orElseThrow(() -> new IllegalStateException("no namespace with slug '" + slug + "'"));
    }

    /**
     * Whether this slug is already somebody's.
     *
     * <p><strong>Not {@link #namespaceOfSlug}, and the difference is the whole reason this exists.</strong>
     * That one treats a missing namespace as a caller holding the wrong picture of the deployment,
     * which is right for the callers it has — they name a slug they know is there. Here absence is
     * the ordinary answer: the caller is asking about a name somebody has not taken yet, and most
     * candidates are free.
     */
    public boolean slugExists(String slug) {
        return repository.findBySlug(slug).isPresent();
    }
}
