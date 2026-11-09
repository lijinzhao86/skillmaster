package com.skillmasterai.modules.secret.internal;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.kms20160120.AsyncClient;
import com.aliyun.sdk.service.kms20160120.models.DecryptRequest;
import com.aliyun.sdk.service.kms20160120.models.DecryptResponse;
import com.aliyun.sdk.service.kms20160120.models.EncryptRequest;
import com.aliyun.sdk.service.kms20160120.models.EncryptResponse;
import com.skillmasterai.modules.secret.CredentialVaultUnavailableException;
import com.skillmasterai.modules.secret.SecretSettings;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Alibaba Cloud KMS (20160120): wraps the data key with one CMK.
 *
 * <p>Kept as the alternative provider (ADR 0172); production seals with {@link HuaweiKeyWrapper}.
 * **This path has no production traffic** — it is compiled and its configuration is validated, but
 * do not assume it works end to end without a round trip against a real CMK first.
 *
 * <p>The CMK never leaves the service: {@code Encrypt} returns a self-describing blob carrying the
 * CMK and its version. Each call is bounded by {@link #CALL_TIMEOUT_SECONDS}: it runs inside the
 * lease's database transaction, and a hung SDK call would hold a connection until the SDK's own
 * default gave up. Errors are rethrown without the data key — this class never logs it.
 */
public final class AliyunKeyWrapper implements KeyWrapper {

    private static final long CALL_TIMEOUT_SECONDS = 5;

    private final AsyncClient client;
    private final String keyId;

    public AliyunKeyWrapper(SecretSettings settings) {
        this.keyId = settings.kmsKeyId();
        var builder = AsyncClient.builder().region(settings.region());
        if (settings.accessKeyId() == null || settings.accessKeyId().isBlank()) {
            builder.credentialsProvider(DefaultCredentialProvider.builder().build());
        } else {
            builder.credentialsProvider(StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(settings.accessKeyId())
                    .accessKeySecret(settings.accessKeySecret())
                    .build()));
        }
        this.client = builder.build();
    }

    @Override
    public byte[] wrap(byte[] dataKey) {
        CompletableFuture<EncryptResponse> call = null;
        try {
            call = client.encrypt(EncryptRequest.builder()
                    .keyId(keyId)
                    .plaintext(Base64.getEncoder().encodeToString(dataKey))
                    .build());
            String blob = call.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getBody()
                    .getCiphertextBlob();
            if (blob == null || blob.isBlank()) {
                throw new CredentialVaultUnavailableException("KMS returned no ciphertext", null);
            }
            return Base64.getDecoder().decode(blob);
        } catch (CredentialVaultUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw new CredentialVaultUnavailableException("KMS encrypt was interrupted", e);
        } catch (TimeoutException e) {
            // The SDK's own timeout is far longer; cancelling keeps timed-out calls from piling up.
            call.cancel(true);
            throw new CredentialVaultUnavailableException("KMS encrypt timed out", e);
        } catch (ExecutionException e) {
            throw new CredentialVaultUnavailableException("KMS encrypt failed", e.getCause());
        } catch (RuntimeException e) {
            // A malformed response (e.g. a blob that is not Base64) and any synchronous SDK
            // failure are still the vault being unusable, not a bug to answer with 500.
            throw new CredentialVaultUnavailableException("KMS encrypt failed", e);
        }
    }

    @Override
    public byte[] unwrap(byte[] wrapped) {
        CompletableFuture<DecryptResponse> call = null;
        try {
            call = client.decrypt(DecryptRequest.builder()
                    .ciphertextBlob(Base64.getEncoder().encodeToString(wrapped))
                    .build());
            String plaintext = call.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getBody()
                    .getPlaintext();
            if (plaintext == null || plaintext.isBlank()) {
                throw new CredentialVaultUnavailableException("KMS returned no plaintext", null);
            }
            return Base64.getDecoder().decode(plaintext);
        } catch (CredentialVaultUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw new CredentialVaultUnavailableException("KMS decrypt was interrupted", e);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new CredentialVaultUnavailableException("KMS decrypt timed out", e);
        } catch (ExecutionException e) {
            throw new CredentialVaultUnavailableException("KMS decrypt failed", e.getCause());
        } catch (RuntimeException e) {
            throw new CredentialVaultUnavailableException("KMS decrypt failed", e);
        }
    }
}
