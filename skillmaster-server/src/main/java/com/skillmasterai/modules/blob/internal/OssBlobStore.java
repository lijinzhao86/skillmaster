package com.skillmasterai.modules.blob.internal;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsProvider;
import com.aliyun.oss.model.ObjectMetadata;
import com.skillmasterai.modules.blob.BlobSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The private OSS bucket, addressed by object key (ADR 0130).
 *
 * <p><strong>Only one implementation of how bytes reach the bucket.</strong> The routing store owns
 * the decision of <em>where</em> a blob lives; this class only moves bytes for a key it is given.
 * Keeping the two apart is what lets the routing rule change without touching signing, retries or
 * the SDK.
 *
 * <p>The bucket is private and no presigned URL is ever produced. A blob's reachability depends on
 * which skills reference it, so that decision belongs at the API, not at the object.
 */
public final class OssBlobStore {

    /** Content-addressed key: the same digest can never name two different byte strings. */
    static String keyOf(String sha256Hex) {
        return "blobs/" + sha256Hex;
    }

    private final OSS client;
    private final String bucket;

    public OssBlobStore(BlobSettings settings) {
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout((int) Duration.ofSeconds(10).toMillis());
        configuration.setSocketTimeout((int) Duration.ofMinutes(5).toMillis());
        configuration.setMaxConnections(64);

        CredentialsProvider credentials = settings.accessKeyId().isBlank()
                ? new InstanceProfileCredentialsProvider(settings.roleName())
                : new DefaultCredentialProvider(settings.accessKeyId(),
                        settings.accessKeySecret());
        this.client = new OSSClientBuilder().build(settings.endpoint(), credentials, configuration);
        this.bucket = settings.bucket();
    }

    /**
     * Uploads one file under its content key.
     *
     * <p>The object is immutable once written and identical content is byte-identical, so a
     * concurrent upload of the same digest is not a conflict — the later one overwrites with the
     * same bytes.
     */
    void put(String key, Path file, long size) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        try (InputStream in = Files.newInputStream(file)) {
            client.putObject(bucket, key, in, metadata);
        } catch (IOException e) {
            throw new UncheckedIOException("could not upload " + key, e);
        }
    }

    InputStream open(String key) {
        return client.getObject(bucket, key).getObjectContent();
    }

    byte[] get(String key) {
        try (InputStream in = open(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + key, e);
        }
    }

    void delete(String key) {
        client.deleteObject(bucket, key);
    }

    boolean exists(String key) {
        return client.doesObjectExist(bucket, key);
    }
}
