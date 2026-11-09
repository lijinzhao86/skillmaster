package com.skillmasterai.modules.secret;

/**
 * The credential exists and is the caller's, but it has no binding on that skill and variable.
 *
 * <p><strong>Its own type so the answer can be a 404 rather than a 204 that did nothing.</strong>
 * Removing a binding that is not there used to answer success and write an audit line saying it
 * happened; the record then disagreed with the table. A 404 with its own code is what lets a caller
 * tell "the address was wrong" from "there was nothing to remove".
 */
public class CredentialBindingNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CredentialBindingNotFoundException() {
        super("this credential has no binding on that skill and variable");
    }
}
