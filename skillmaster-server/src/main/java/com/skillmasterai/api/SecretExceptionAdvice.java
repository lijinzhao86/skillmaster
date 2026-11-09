package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.secret.CredentialBindingNotFoundException;
import com.skillmasterai.modules.secret.CredentialVaultUnavailableException;
import com.skillmasterai.modules.secret.ShareNotFoundException;
import com.skillmasterai.modules.secret.SecretNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The credential vault's own 404, kept beside the module it belongs to.
 *
 * <p>Highest precedence because the shared advice's catch-all {@code Exception} handler would
 * otherwise answer first and turn the one 404 into a 500. The other credential errors already
 * extend {@code RequestException}/{@code ConflictException}, so the shared handler gives them the
 * status their code names.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecretExceptionAdvice {

    /** No such credential, or not the caller's — one answer for both, like every other 404. */
    @ExceptionHandler(SecretNotFoundException.class)
    ResponseEntity<ApiError> onSecretNotFound(SecretNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.CREDENTIAL_NOT_FOUND, e.getMessage()));
    }

    /** The credential is there; the binding is not. A different code, because the fix is different. */
    /**
     * A revocation with nothing live behind it. Same status as the binding's 404 — the thing the
     * caller named does not exist — but its own code, because the next step differs: the reader is
     * pointed at the grants, not at the bindings.
     */
    @ExceptionHandler(ShareNotFoundException.class)
    ResponseEntity<ApiError> onShareNotFound(ShareNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ErrorCode.CREDENTIAL_SHARE_NOT_FOUND,
                e.getMessage()));
    }

    @ExceptionHandler(CredentialBindingNotFoundException.class)
    ResponseEntity<ApiError> onBindingNotFound(CredentialBindingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.CREDENTIAL_BINDING_NOT_FOUND, e.getMessage()));
    }

    /**
     * The KMS is down or refused the call. 503 with its own code, not the catch-all 500: the
     * stored ciphertext is intact, so the caller retries rather than re-creates the value, and a
     * person reading the client's message can tell a dependency outage from a bug.
     */
    @ExceptionHandler(CredentialVaultUnavailableException.class)
    ResponseEntity<ApiError> onVaultUnavailable(CredentialVaultUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ErrorCode.CREDENTIAL_VAULT_UNAVAILABLE, e.getMessage()));
    }
}
