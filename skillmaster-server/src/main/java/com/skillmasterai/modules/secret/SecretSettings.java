package com.skillmasterai.modules.secret;

import java.util.Base64;

/**
 * How this deployment seals values, read once at startup.
 *
 * <p><strong>{@code local} is for development and tests only.</strong> It is a symmetric key in this
 * process's configuration, which is a legitimate convenience for a laptop and not a substitute for a
 * cloud KMS that can be audited and rotated independently of the application. The production
 * check lives in {@code config.SecretSettingsConfig}, which refuses to publish these settings when a
 * {@code prod} profile is active — the module cannot read the environment, and the check has to
 * happen where the profiles are.
 *
 * @param sealer          {@code huawei}, {@code aliyun} or {@code local}
 * @param kmsKeyId        the CMK the KMS wraps the data key with; required for the cloud sealers
 * @param region          the KMS region, e.g. {@code cn-east-3}; required for the cloud sealers
 * @param accessKeyId     an independent identity's key; required for {@code huawei} (this host has
 *                        no Huawei credential source), optional for {@code aliyun} (its chain can
 *                        reach a RAM role); required together with {@code accessKeySecret}
 * @param accessKeySecret paired with {@code accessKeyId}
 * @param localKey        Base64 of exactly 32 bytes; required for {@code local}
 */
public record SecretSettings(String sealer, String kmsKeyId, String region,
        String accessKeyId, String accessKeySecret, String localKey) {

    public static final String ALIYUN = "aliyun";
    public static final String HUAWEI = "huawei";
    public static final String LOCAL = "local";

    public SecretSettings {
        sealer = sealer == null || sealer.isBlank() ? LOCAL : sealer.trim().toLowerCase(
                java.util.Locale.ROOT);
        // Trailing whitespace survives `export FOO="bar "` and would otherwise build an invalid
        // endpoint or a signature that never matches — with a startup check that passed.
        kmsKeyId = trimmed(kmsKeyId);
        region = trimmed(region);
        accessKeyId = trimmed(accessKeyId);
        accessKeySecret = trimmed(accessKeySecret);
        localKey = trimmed(localKey);
        // `kms` is the 0170 spelling of a single provider; v52 kept `aliyun` and `huawei` explicit
        // (ADR 0172) so the deployment's `server.env` says which cloud the values are sealed in.
        if (!ALIYUN.equals(sealer) && !HUAWEI.equals(sealer) && !LOCAL.equals(sealer)) {
            throw new IllegalArgumentException(
                    "skillmaster.secret.sealer must be 'aliyun', 'huawei' or 'local', was '"
                            + sealer + "'");
        }
        if (isCloud(sealer)) {
            if (isBlank(kmsKeyId)) {
                throw new IllegalArgumentException(
                        "skillmaster.secret.kms-key-id is required when the sealer is " + sealer);
            }
            if (isBlank(region)) {
                throw new IllegalArgumentException(
                        "skillmaster.secret.region is required when the sealer is " + sealer);
            }
            if (isBlank(accessKeyId) != isBlank(accessKeySecret)) {
                throw new IllegalArgumentException(
                        "skillmaster.secret.access-key-id and access-key-secret must be given"
                                + " together");
            }
            // Huawei's default chain probes the Huawei metadata service. This deployment's host is
            // an Aliyun ECS, where that lookup does not answer: the client would sit through its
            // connect timeout and then fail with a message that names no configuration key. `aliyun`
            // keeps the blank path because its chain can reach a RAM role on the same host.
            if (HUAWEI.equals(sealer) && isBlank(accessKeyId)) {
                throw new IllegalArgumentException(
                        "skillmaster.secret.access-key-id and access-key-secret are required when"
                                + " the sealer is huawei; this host has no Huawei credential source"
                                + " to fall back on");
            }
        }
        if (LOCAL.equals(sealer)) {
            if (isBlank(localKey)) {
                throw new IllegalArgumentException(
                        "skillmaster.secret.local-key is required when the sealer is local;"
                                + " supply Base64 of 32 bytes");
            }
            // Validated here so a malformed key fails at startup; the decoded copy is not kept.
            java.util.Arrays.fill(localKeyBytes(localKey), (byte) 0);
        }
    }

    /** Whether values are sealed by a cloud KMS rather than the local key. */
    public boolean usesCloudKms() {
        return isCloud(sealer);
    }

    private static boolean isCloud(String sealer) {
        return ALIYUN.equals(sealer) || HUAWEI.equals(sealer);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /** The decoded AES key; throws here so a malformed key fails startup, not the first write. */
    public byte[] localKeyBytes() {
        return localKeyBytes(localKey);
    }

    private static byte[] localKeyBytes(String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "skillmaster.secret.local-key must be Base64; it is not", e);
        }
        if (key.length != 32) {
            java.util.Arrays.fill(key, (byte) 0);
            throw new IllegalArgumentException(
                    "skillmaster.secret.local-key must decode to 32 bytes (AES-256), was "
                            + key.length);
        }
        return key;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
