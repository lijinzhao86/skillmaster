package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.auth.Scopes;
import com.skillmasterai.modules.token.ApiKeyPolicy;
import com.skillmasterai.modules.token.ApiKeyRequestException;
import com.skillmasterai.modules.token.ApiKeys;
import com.skillmasterai.modules.token.IssuedApiKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing a key to the account asking for it (ADR 0038).
 *
 * <p>The four decisions live here rather than in M2, because all four are policy rather than
 * storage: what a client is allowed to ask for, what happens when it asks for nothing, and which
 * requests are simply wrong. M2 stores the result and audits it; the console and the CLI never see
 * any of this.
 *
 * <p><strong>The account is the caller and there is no way to name another one.</strong> The
 * subject comes from the session, so "issue a key for somebody else" is not a request this use case
 * can express — which is the whole of v1's answer to who may issue (§开放问题 Q2).
 */
@Component
public class IssueApiKeyUseCase {

    /**
     * Caps on the two pieces of text a person writes, and they are not arbitrary.
     *
     * <p>The columns are {@code TEXT}, so nothing in the database bounds these — and an unbounded
     * field a person can paste a file into ends up in a listing that has to render it. A name is a
     * handle: 100 characters is generous for "laptop" and long enough for a hostname. A note is a
     * sentence, and 500 is a long one. Neither is a security boundary; both are the difference
     * between a listing and a wall of text.
     */
    private static final int MAX_NAME = 100;
    private static final int MAX_NOTE = 500;

    private final ApiKeys keys;
    private final ApiKeyPolicy policy;

    public IssueApiKeyUseCase(ApiKeys keys, ApiKeyPolicy policy) {
        this.keys = keys;
        this.policy = policy;
    }

    /**
     * @param scopes    what the key may do. Write implies read, folded in here so the stored row is
     *                  the truth (ADR 0039 §决定 2)
     * @param expiresAt when it should stop working, or null to take the deployment's default. Null is
     *                  the ordinary case: the console preselects a date and the CLI never issues at all
     * @return the key and its secret, which exists in this value and in no other place afterwards
     */
    @Transactional
    public IssuedApiKey issue(Request request, AuthenticatedSubject subject) {
        requireUsableName(request.name());
        requireUsableNote(request.note());
        Set<String> scopes = checkedScopes(request.scopes());

        Instant expiresAt = request.expiresAt() == null
                ? policy.defaultExpiry(Instant.now())
                : requireIssuable(request.expiresAt(), policy);

        return keys.issue(subject.userId(), request.name().trim(), trimmed(request.note()), scopes,
                expiresAt);
    }

    private static void requireUsableName(String name) {
        if (name == null || name.isBlank()) {
            // Trimmed before it is measured, because leading whitespace is not a name: a field of
            // spaces is a client bug that would otherwise create a key with no visible label.
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.NAME, "blank");
        }
        if (name.trim().length() > MAX_NAME) {
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.NAME, "too_long");
        }
    }

    private static void requireUsableNote(String note) {
        if (note != null && note.trim().length() > MAX_NOTE) {
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.NOTE, "too_long");
        }
    }

    /**
     * At least one scope, and every one of them real (ADR 0038 §决定 4).
     *
     * <p>A credential that may do nothing is not a smaller credential, it is one that will appear
     * broken — the first command it runs is refused, and the person has no way to tell that from a
     * broken key. The reference product requires the same thing. The vocabulary is checked here
     * rather than stored unchecked so that a typo is a 400 now instead of a key that silently cannot
     * do what its name says.
     */
    private static Set<String> checkedScopes(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.SCOPE, "empty");
        }
        // Asked of `Scopes.isKnown` one at a time rather than as `ALL.containsAll(requested)`: the
        // immutable set throws on a null probe, so a hand-built body with a null in the array was a 500
        // where a 400 belongs. A null is simply not a scope this API understands.
        if (requested.stream().anyMatch(scope -> !Scopes.isKnown(scope))) {
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.SCOPE, "unknown");
        }
        return Scopes.withImpliedRead(requested);
    }

    /**
     * The date a request named, or the refusal that says why it cannot be issued here.
     *
     * <p><strong>Two questions, in this order</strong>: is it in the future, and is it within what
     * this deployment issues? The second is ADR 0042 — the deployment's lifetime is a ceiling, not a
     * suggestion — and it is asked second so that a date in the past is told exactly that, rather
     * than being told it exceeds a maximum it never approached.
     */
    private static Instant requireIssuable(Instant expiresAt, ApiKeyPolicy policy) {
        Instant asStored = requireFuture(expiresAt);
        Instant latest = policy.latestExpiry(Instant.now());
        if (latest != null && asStored.isAfter(latest)) {
            // Named apart from "not in the future" because the remedy is different: this one is a
            // legitimate date that this deployment does not issue, and the message says which one it
            // does — otherwise the person tries the same form again with a slightly smaller year.
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.EXPIRY, "beyond_maximum");
        }
        return asStored;
    }

    private static Instant requireFuture(Instant expiresAt) {
        // **Judged as it will be stored, not as it arrived.** A timestamp is written truncated to
        // whole seconds (`Timestamps.format`), so a request carrying a fraction —
        // `…T00:00:00.400Z`, exactly what a client computing "half a second from now" sends — could
        // pass a nanosecond comparison and then be *stored* as `…T00:00:00Z`, a fraction earlier than
        // the moment it was accepted, i.e. already expired. Comparing the stored form removes that,
        // and returning it means the value validated here is the value the row gets.
        //
        // **What is left is the transaction, not the arithmetic**: a date one second ahead is legal
        // and stays legal, so a request that lands in the last millisecond of a second and commits
        // after it still hands back a key whose first request is refused — with `token_expired` and
        // the sentence that says to renew it, which is the honest way for a one-second credential to
        // behave. (Refusing anything within a second of now would also refuse a legitimate, if silly,
        // request, and would need a number nobody could justify.)
        Instant asStored = expiresAt.truncatedTo(ChronoUnit.SECONDS);
        if (!asStored.isAfter(Instant.now())) {
            // A date in the past is not a key with a short life, it is a key that never works — and
            // a person who typed the wrong year should find out here rather than at the terminal.
            throw new ApiKeyRequestException(ApiKeyRequestException.Problem.EXPIRY,
                    "not_in_the_future");
        }
        return asStored;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @param note      why this key was issued, in the person's own words. Null or blank means
     *                  nothing was written, which is a real answer rather than a gap
     * @param expiresAt null asks for the deployment's default (ADR 0039 §决定 1)
     */
    public record Request(String name, String note, Set<String> scopes, Instant expiresAt) {
    }
}
