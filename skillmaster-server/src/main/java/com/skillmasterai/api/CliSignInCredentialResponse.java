package com.skillmasterai.api;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.token.CliSignIn;

/**
 * The credential itself, in the one response it will ever appear in.
 *
 * <p><strong>{@code expiresAt} is when it dies if nothing else happens to it</strong> — the idle ruler
 * as it stood at issuance, not a fixed end. Every use slides it forward (up to the absolute ceiling),
 * so a client that showed this as "expires at" would be showing the earliest moment it <em>could</em>
 * end rather than the moment it will. The CLI prints it that way on purpose: it is the honest answer
 * to "how long have I got", and the answer is "at least this".
 *
 * <p>The time is the stored spelling rendered as text, RFC3339 UTC, exactly as {@link SessionResponse}
 * sends its own: nothing here reformats a timestamp into a sentence.
 *
 * <p><strong>{@code scope} comes back because it is not always what was asked for.</strong> What a
 * login may do is what the client is registered for, intersected with the request (v4 §接口), so the
 * answer to "what can this credential do" is the granted set and not the requested one — and the
 * command that answers that question (Q9) reads it from here.
 *
 * <p><strong>{@code name} comes back because this is the client's only chance to learn it.</strong>
 * The terminal prints, right after a login, 它的名字 / 权限 / 什么时候到期 (prd 验收 #1), and the name is
 * the one of those three the client cannot work out for itself: the person typed it on the
 * login-request screen, so the server holds it while the process that redeems the code has never seen
 * it. This route is one request and nothing else — there is no session here to ask anything by, and
 * the code is a digest of a value with no name in it — so a client that was not told here would have
 * to invent one, which is worse than the machine's own name arriving beside its credential. It is
 * neither a secret nor the credential: the same string is already on the console's 「登录设备」 row
 * ({@code CLI · <name>}) and in the audit row that records the sign-in, and {@code accessToken} is the
 * only field here that has to be protected.
 */
record CliSignInCredentialResponse(String accessToken, String expiresAt, String scope, String name) {

    static CliSignInCredentialResponse of(CliSignIn.Credential credential) {
        return new CliSignInCredentialResponse(
                credential.token(),
                Timestamps.format(credential.expiresAt()),
                String.join(" ", credential.scopes()),
                credential.name());
    }
}
