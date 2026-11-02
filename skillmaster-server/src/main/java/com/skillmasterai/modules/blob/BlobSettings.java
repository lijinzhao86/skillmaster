package com.skillmasterai.modules.blob;

/**
 * Where large bytes go and how large "large" is (ADR 0130).
 *
 * <p><strong>One threshold, and it is about size, not content.</strong> A blob above
 * {@link #thresholdBytes} lives in the private OSS bucket; everything else stays in
 * {@code blob_content}. Text is not special: a hundred-mebibyte file of ASCII is exactly the kind of
 * content the database should not carry, and a storage decision that consulted the UTF-8 probe
 * would put the same bytes in two places.
 *
 * <p>{@code ossEnabled=false} is a supported deployment: every blob stays in PostgreSQL, which is
 * what a local run and the default test profile use. Turning it on without an endpoint and bucket
 * is refused here rather than failing on the first upload.
 *
 * @param endpoint          the bucket's endpoint, e.g. {@code https://oss-cn-hangzhou.aliyuncs.com}
 * @param bucket            the private bucket's name
 * @param accessKeyId       optional; an ECS RAM role supplies credentials when this is blank
 * @param accessKeySecret   optional, paired with {@code accessKeyId}
 * @param thresholdBytes    strictly greater than this goes to OSS
 * @param tempDir           where an in-flight upload larger than the threshold is spooled
 */
public record BlobSettings(boolean ossEnabled, String endpoint, String bucket,
        String accessKeyId, String accessKeySecret, String roleName, long thresholdBytes,
        long maxBytes, String tempDir) {

    public BlobSettings {
        if (thresholdBytes <= 0) {
            throw new IllegalArgumentException("skillmaster.blobs.threshold-bytes must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("skillmaster.blobs.max-bytes must be positive");
        }
        if (ossEnabled && (isBlank(endpoint) || isBlank(bucket))) {
            throw new IllegalArgumentException(
                    "skillmaster.blobs.oss-enabled needs both an endpoint and a bucket");
        }
        if (isBlank(tempDir)) {
            tempDir = System.getProperty("java.io.tmpdir");
        }
        if ((isBlank(accessKeyId)) != (isBlank(accessKeySecret))) {
            throw new IllegalArgumentException(
                    "skillmaster.blobs.access-key-id and access-key-secret must be given together");
        }
        if (ossEnabled && isBlank(accessKeyId) && isBlank(roleName)) {
            throw new IllegalArgumentException(
                    "skillmaster.blobs needs either an access key pair or a role-name");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The spool directory as a path; {@link #tempDir()} is blank only before the compact ctor. */
    public java.nio.file.Path tempDirPath() {
        return java.nio.file.Path.of(tempDir);
    }

    /** The defaults a deployment that says nothing gets: database only, with the threshold and
     *  ceiling below. */
    public static BlobSettings defaults() {
        return new BlobSettings(false, "", "", "", "", "", 1024L * 1024L,
                100L * 1024L * 1024L, "");
    }
}
