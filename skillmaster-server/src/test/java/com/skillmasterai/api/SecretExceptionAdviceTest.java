package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.modules.secret.CredentialVaultUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A KMS outage answers 503 with its own code, not the catch-all 500.
 *
 * <p>The distinction is the caller's next step: retry later (the ciphertext is intact) versus
 * report a bug.
 */
class SecretExceptionAdviceTest {

    @Test
    void theVaultOutageIsA503WithItsOwnCode() {
        ResponseEntity<ApiError> response = new SecretExceptionAdvice().onVaultUnavailable(
                new CredentialVaultUnavailableException("KMS decrypt failed", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code())
                .isEqualTo("credential_vault_unavailable");
    }
}
