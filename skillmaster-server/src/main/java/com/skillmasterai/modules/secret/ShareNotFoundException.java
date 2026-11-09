package com.skillmasterai.modules.secret;

/**
 * The credential exists and is the caller's, but nobody holds a live grant for that person on that
 * skill.
 *
 * <p><strong>Its own type so the refusal can be about the right thing.</strong> Revoking a share
 * that is not there used to answer 204 and print a success — a person who mistyped the handle was
 * told the grant was gone while the real grantee kept receiving the value. Answering with the
 * binding's not-found instead would be the opposite mistake: the sentence would send the reader to
 * their bindings when what is missing is a grant.
 *
 * <p>{@link CredentialBindingNotFoundException} says "no binding on that skill and variable"; this
 * one says "no share with that person on that skill". They are different tables and different next
 * steps, so they are different codes.
 */
public class ShareNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ShareNotFoundException() {
        super("this credential is not shared with that person on that skill");
    }
}
