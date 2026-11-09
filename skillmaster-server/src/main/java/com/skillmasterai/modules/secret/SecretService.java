package com.skillmasterai.modules.secret;

import java.util.List;
import java.util.Optional;

/**
 * M16's published use-case entry.
 *
 * <p>Everything here is expressed in ids and in the five value types. The tables, the sealer, the
 * quota statement and the share resolution stay in {@code internal}; swapping the KMS for another
 * box changes nothing a caller can see.
 *
 * <p><strong>Ownership is not decided here.</strong> The use cases hold the subject and the skill
 * access answer, and they call these methods only after both checks have passed. The methods still
 * take an owner id where a row must be rooted in one, so a use case cannot accidentally operate on
 * a credential it forgot to load.
 *
 * <p>No method logs or returns a plaintext value except {@link #lease}, which is the one designed
 * exit.
 */
public interface SecretService {

    /** Inserts the credential row; the binding is a separate call so a create can fail cleanly. */
    CredentialRef create(String ownerUserId, String name, CredentialValue value, String at);

    /** The owner's credentials, newest first; {@code skillIdFilter} or null for all of them. */
    List<CredentialRef> list(String ownerUserId, String skillIdFilter);

    /** No such credential, or not visible to this call; the use case checks the owner. */
    Optional<CredentialRef> find(String credentialId);

    /** Replaces the value and stamps {@code rotated_at}; bindings and shares stay. */
    CredentialRef rotate(String credentialId, CredentialValue value, String at);

    /** Hard delete; bindings, uses and shares go with it through their foreign keys. */
    void delete(String credentialId);

    /**
     * Removes every credential this person owns (v56's account purge).
     *
     * <p>Not a loop over {@link #delete}: the caller is a scheduled purge with a user id, not a
     * person who loaded a list, and one statement cannot half-succeed the way a loop can.
     *
     * @return how many credentials were removed
     */
    int deleteAllFor(String ownerUserId);

    /**
     * Removes every share addressed to this person (v56's account purge).
     *
     * <p>These are other people's credentials shared with them — rows in someone else's vault that
     * name this person as the grantee. They are not part of the person's own namespace, so the
     * cascade never sees them.
     *
     * @return how many share rows were removed
     */
    int deleteSharesFor(String granteeUserId);

    /** Moves the daily lease ceiling. The range is the schema's (1..10000). */
    CredentialRef setPerDayLimit(String credentialId, int perDayLimit);

    /** Exposes the credential to one skill and one environment variable. */
    CredentialBinding bind(String credentialId, String skillId, String envName, String at);

    /**
     * Removes one binding; the value and every other binding stay.
     *
     * @return whether there was one to remove — {@code false} is "no such binding", which the use
     *         case answers as a 404 rather than as a 204 that did nothing
     */
    boolean unbind(String credentialId, String skillId, String envName);

    /** Every binding of one credential. */
    List<CredentialBinding> bindings(String credentialId);

    /** Whether this owner already has <em>any</em> binding on {@code (skill, env)}. */
    boolean hasOwnBinding(String ownerUserId, String skillId, String envName);

    /** Live shares of one credential, optionally narrowed to one skill. */
    List<CredentialBinding.Share> shares(String credentialId, String skillIdFilter);

    /**
     * The variables this user receives from other owners' credentials on one skill.
     *
     * <p>The listing the console's skill pane draws beside a variable it did not bind itself. It
     * names the owner so the use case can resolve a handle — M16 may not read M1.
     *
     * @param day {@code YYYY-MM-DD} in UTC, the bucket the usage column is counted in
     */
    List<IncomingBinding> incoming(String userId, String skillId, String day);

    /**
     * Grants every binding this credential has on this skill to {@code granteeUserId}.
     *
     * @return the rows written, one per binding that exists on the skill
     * @throws SecretNotFoundException    when the credential has no binding on that skill
     * @throws BindingConflictException   with {@code credential_share_conflict} when a live share
     *                                    already exists
     */
    List<CredentialBinding.Share> share(String credentialId, String skillId,
            String granteeUserId, String grantedBy, String at);

    /**
     * Revokes every live share of this credential on this skill for one person.
     *
     * @return whether a live share was revoked; {@code false} means there was nothing to revoke
     */
    boolean unshare(String credentialId, String skillId, String granteeUserId, String at);

    /**
     * The environment variables this user would receive for this skill — own bindings, plus live
     * shares when {@code sharesAreValid} says the caller is still a collaborator.
     *
     * <p>Names rather than a boolean: the metadata answer needs both "would a lease return
     * anything" (non-empty) and "which declared variables are missing" (the set difference the API
     * layer takes against the author's declaration). One statement answers both.
     */
    java.util.Set<String> boundEnvNames(String userId, String skillId, boolean sharesAreValid);

    /** Leases today's count for one credential; zero when no row exists yet. */
    int usedToday(String credentialId, String day);

    /**
     * Resolves the effective bindings for {@code (user, skill)}, charges the quota and decrypts.
     *
     * <p>Own bindings win over shared ones for the same environment variable, and only one row per
     * variable is used. A credential is charged once per lease even when it serves several
     * variables. An empty result is a legitimate answer — the bindings may have been removed
     * between the metadata read and this call.
     *
     * @throws QuotaExceededException when any credential would exceed its day's limit. The caller's
     *                                transaction rolls back, so a partially charged lease never
     *                                commits
     */
    /**
     * @param sharesAreValid whether this caller is still an accepted collaborator of the skill, so
     *                       a share granted earlier is still worth honouring. The caller answers it
     *                       from M12's access answer — the grant can be withdrawn while the share row
     *                       stays, and a credential must stop travelling the moment the person is no
     *                       longer a collaborator. {@code false} still returns this caller's own
     *                       bindings.
     */
    CredentialLease lease(String userId, String skillId, String day, String at, String expiresAt,
            boolean sharesAreValid);
}
