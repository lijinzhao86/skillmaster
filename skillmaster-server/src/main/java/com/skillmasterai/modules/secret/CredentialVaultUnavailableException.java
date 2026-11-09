package com.skillmasterai.modules.secret;

/**
 * The cloud KMS refused the call or could not be reached.
 *
 * <p><strong>Not the same as a corrupted blob.</strong> Nothing is known to be lost here: the
 * ciphertext in the table is intact, the KMS is a dependency that failed, and the caller's action
 * is to retry later rather than to re-create the value. The API answers 503 with its own code so a
 * person reading the error can tell "the vault is down" from "something in the code broke".
 */
public class CredentialVaultUnavailableException extends RuntimeException {

    public CredentialVaultUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
