package com.skillmasterai.modules.auth;

import com.skillmasterai.common.ErrorCode;

/**
 * What a presented credential turned out to be: a subject, or a name for the refusal.
 *
 * <p><strong>The refusal used to be nameless, and naming it is a deliberate change to this seam's
 * contract.</strong> Until v2 a rejected credential and an absent one produced the same empty answer,
 * on the reasoning that "why was I refused" is a question an unauthenticated caller has no business
 * asking. That reasoning holds for <em>whether a credential exists</em> and stops holding the moment
 * one does: a person pasting a key into a CLI has to be told whether it expired (renew it) or was
 * revoked (issue another one), because those are two different things to go and do, and the design
 * requires the message to say which (ADR 0039 §决定 3).
 *
 * <p><strong>So the line is drawn between "not ours" and "ours, and not usable".</strong>
 * {@link ErrorCode#UNAUTHENTICATED} covers both "no credential was presented" and "this string
 * matches nothing we have ever issued" — a guess learns nothing, which is the property the original
 * comment was protecting. The three named refusals are only ever produced after a row was found by
 * its sha256, and finding one requires already holding 256 bits of secret: whoever is being told why
 * is the person holding the credential, and telling a thief "this one was revoked" costs less than
 * leaving the person who owns it unable to act.
 *
 * <p>{@link Rejected} refuses to carry anything but those four codes, so a caller cannot turn this
 * into a channel for a fifth kind of answer by inventing one.
 */
public sealed interface TokenValidation {

    record Accepted(AuthenticatedSubject subject) implements TokenValidation {
    }

    /**
     * @param reason which kind of no. Four are possible and the validator decides between them in one
     *               place — the precedence when a credential is both revoked and expired belongs
     *               there, not here
     */
    record Rejected(ErrorCode reason) implements TokenValidation {

        public Rejected {
            switch (reason) {
                case UNAUTHENTICATED, TOKEN_REVOKED, TOKEN_EXPIRED, ACCOUNT_SUSPENDED -> {
                    // The four answers this seam is allowed to give.
                }
                default -> throw new IllegalArgumentException(reason + " is not a refusal this seam"
                        + " produces — see TokenValidation's javadoc for the four that are");
            }
        }
    }
}
