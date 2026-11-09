package com.skillmasterai.modules.secret.internal;

import com.huaweicloud.sdk.core.ClientBuilder;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.http.HttpConfig;
import com.huaweicloud.sdk.core.region.Region;
import com.huaweicloud.sdk.kms.v2.KmsClient;
import com.huaweicloud.sdk.kms.v2.model.DecryptDataRequest;
import com.huaweicloud.sdk.kms.v2.model.DecryptDataRequestBody;
import com.huaweicloud.sdk.kms.v2.model.EncryptDataRequest;
import com.huaweicloud.sdk.kms.v2.model.EncryptDataRequestBody;
import com.skillmasterai.modules.secret.CredentialVaultUnavailableException;
import com.skillmasterai.modules.secret.SecretSettings;
import java.util.Base64;

/**
 * Huawei Cloud KMS (DEW): wraps the data key with one CMK. This is the production sealer (ADR 0172).
 *
 * <p><strong>The endpoint is built from the region</strong> rather than looked up in the SDK's
 * built-in table: KMS is a regional service and the id the deployment carries
 * ({@code cn-east-3}) is the same one {@code hcloud} uses. No trailing slash.
 *
 * <p><strong>The timeouts are deliberately short.</strong> A lease decrypts inside its database
 * transaction, so a KMS call that hangs holds a connection until it gives up; the SDK's defaults
 * (60 s connect, 120 s read) would let a KMS outage exhaust the pool and take every endpoint down
 * with it. Five seconds is several times the measured call time (69–104 ms on this link) and fails
 * as a {@link CredentialVaultUnavailableException}, which answers 503 instead of 500.
 *
 * <p>The KMS never sees a credential value — only the 32-byte data key from
 * {@link EnvelopeSealer}'s envelope — which is what keeps the call under the API's 4096-character
 * plaintext limit regardless of how large the value is.
 */
public final class HuaweiKeyWrapper implements KeyWrapper {

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final KmsClient client;
    private final String keyId;

    public HuaweiKeyWrapper(SecretSettings settings) {
        this.keyId = settings.kmsKeyId();
        ClientBuilder<KmsClient> builder = KmsClient.newBuilder()
                .withRegion(new Region(settings.region(),
                        "https://kms." + settings.region() + ".myhuaweicloud.com"))
                .withHttpConfig(new HttpConfig()
                        .withConnectionTimeout(CONNECT_TIMEOUT_MS)
                        .withReadTimeout(READ_TIMEOUT_MS));
        if (settings.accessKeyId() != null && !settings.accessKeyId().isBlank()) {
            builder.withCredential(new BasicCredentials()
                    .withAk(settings.accessKeyId())
                    .withSk(settings.accessKeySecret()));
        }
        this.client = builder.build();
    }

    @Override
    public byte[] wrap(byte[] dataKey) {
        try {
            EncryptDataRequest request = new EncryptDataRequest().withBody(
                    new EncryptDataRequestBody()
                            .withKeyId(keyId)
                            .withPlainText(Base64.getEncoder().encodeToString(dataKey)));
            String cipherText = client.encryptData(request).getCipherText();
            if (cipherText == null || cipherText.isBlank()) {
                throw new CredentialVaultUnavailableException("KMS returned no ciphertext", null);
            }
            return Base64.getDecoder().decode(cipherText);
        } catch (CredentialVaultUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CredentialVaultUnavailableException("KMS encrypt failed", e);
        }
    }

    @Override
    public byte[] unwrap(byte[] wrapped) {
        try {
            DecryptDataRequest request = new DecryptDataRequest().withBody(
                    new DecryptDataRequestBody()
                            .withKeyId(keyId)
                            .withCipherText(Base64.getEncoder().encodeToString(wrapped)));
            String plainText = client.decryptData(request).getPlainText();
            if (plainText == null || plainText.isBlank()) {
                throw new CredentialVaultUnavailableException("KMS returned no plaintext", null);
            }
            return Base64.getDecoder().decode(plainText);
        } catch (CredentialVaultUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CredentialVaultUnavailableException("KMS decrypt failed", e);
        }
    }
}
