package com.skillmasterai.modules.namespace;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.namespace.internal.NamespaceRepository;
import java.util.Collection;
import java.util.Map;
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
     * The namespace an address names, whether or not the caller has anything to do with it.
     *
     * <p><strong>This is not an authorization decision and must not be mistaken for one.</strong> It
     * answers "which namespace is this", and the caller's standing is a separate question asked
     * where the skill is known — {@code SkillVersionService}, which refuses in the same statement
     * that finds the row (§3.4). An address whose first segment is somebody else's is now
     * <em>expressible</em>, which is what sharing means (ADR 0034): the skill may have been granted
     * to the caller.
     *
     * <p>It replaces {@code readableNamespaceOf}, which answered "your own namespace, when the
     * address named it" — correct while readable and owned were the same set, and wrong the moment
     * they were not. That method's own note said which line would change; this is that change, and
     * it went in the direction the note predicted: the namespace is no longer asked about
     * ownership at all.
     *
     * <p>Absence is empty rather than an exception, unlike {@link #namespaceOfSlug}: there, a
     * missing slug means the caller holds the wrong picture of a deployment; here it is an ordinary
     * address that resolves to nothing, which is a 404 like every other.
     */
    public Optional<Namespace> bySlug(String slug) {
        return repository.findBySlug(slug);
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
     * Slugs for several namespaces at once.
     *
     * <p>A listing needs one slug per row, and since ADR 0034 one page can span namespaces — its
     * rows are M7's, the slug is M4's column, and this is the batch that puts them back together
     * without a query per card. Same shape and same reason as
     * {@code AccountDirectory.handlesOf}.
     *
     * @return one entry per id that names an existing namespace; a missing id is simply absent
     */
    public Map<String, String> slugsOf(Collection<String> namespaceIds) {
        if (namespaceIds.isEmpty()) {
            return Map.of();
        }
        return repository.slugsOf(namespaceIds);
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
