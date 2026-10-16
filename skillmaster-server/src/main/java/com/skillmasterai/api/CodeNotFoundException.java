package com.skillmasterai.api;

/**
 * The sign-in code could not be redeemed — and the four ways that happens are one answer.
 *
 * <p><strong>Unknown, expired, already spent, wrong verifier: the same 404 and the same code.</strong>
 * This is §4.2's rule and the reason {@link SessionNotFoundException} has one answer for two cases:
 * "you already spent that code" is a more useful sentence, and it is also one that tells whoever is
 * holding a random string that the string was once real. The caller who legitimately holds a code is
 * the CLI that asked for it, and it redeems it exactly once — nobody else is helped by the
 * distinction, and somebody guessing is.
 *
 * <p>Thrown from the API layer rather than returned as an absence by the module, in the shape
 * {@link ApiKeyNotFoundException} already has: M2 answers with an {@code Optional}, and the
 * translation into a status and a code happens here.
 */
class CodeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CodeNotFoundException() {
        super("no such sign-in code");
    }
}
