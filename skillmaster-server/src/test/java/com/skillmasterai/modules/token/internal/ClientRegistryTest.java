package com.skillmasterai.modules.token.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one thing a client row can ask for that this deployment does not offer.
 *
 * <p>Unit-tested rather than driven through HTTP, for the same reason {@code TokenPolicyTest} is:
 * the row is a deployment input, and the refusal is a startup one. A bad row means the context does
 * not come up, so there is no running server to assert against — the decision is the thing to pin.
 *
 * <p><strong>What the check is really preventing</strong> is not a bad grant but a broken write. v3
 * removed the column that said which user an unattended client acted as; with it gone, the
 * {@code client_credentials} principal — the client id — lands in {@code access_token.user_id},
 * which is {@code NOT NULL REFERENCES app_user(id)}. That was a foreign-key violation, a 500, and an
 * orphan {@code oauth_authorization} row, reproduced on 2026-10-16 by following the server README's
 * own by-hand instructions.
 */
class ClientRegistryTest {

    @Test
    void theGrantsThisDeploymentOffersAreAccepted() {
        assertThatCode(() -> ClientRegistry.grantCheck("skillmaster-cli",
                List.of("authorization_code", "refresh_token"))).doesNotThrowAnyException();
    }

    @Test
    void theRetiredGrantIsRefusedAndTheRowIsNamed() {
        assertThatThrownBy(() -> ClientRegistry.grantCheck("dev-ci", List.of("client_credentials")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-ci")
                .hasMessageContaining("client_credentials")
                // The message has to carry the remedy, because the one reading it is mid-deployment.
                .hasMessageContaining("SKILLMASTER_API_KEY");
    }

    @Test
    void aRowThatAsksForTheRetiredGrantAmongOthersIsStillRefused() {
        // Not "a row whose only grant is retired": `client_credentials` beside a live grant is the
        // shape a rotation would produce, and it has to fail the same way rather than half-work.
        assertThatThrownBy(() -> ClientRegistry.grantCheck("mixed",
                List.of("authorization_code", "client_credentials")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mixed");
    }
}
