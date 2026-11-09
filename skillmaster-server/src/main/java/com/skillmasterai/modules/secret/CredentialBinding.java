package com.skillmasterai.modules.secret;

/**
 * One exposure of a value: {@code (credential, skill, env name)}.
 *
 * <p>The binding, not the credential, is what injection resolves. A skill nobody bound the value to
 * never receives it, which is the whole of the product's "a credential belongs to a person"
 * promise.
 *
 * <p>{@link Share} is nested here rather than a type of its own because the module's published
 * surface is deliberately the five value types plus the service; a share is a grant on one binding
 * and reads naturally beside it.
 */
public record CredentialBinding(String id, String credentialId, String skillId, String envName,
        String createdAt) {

    /**
     * One live grant of a binding to another account.
     *
     * @param granteeUserId the account that may receive this binding's value while the grant lives
     * @param skillId       the binding's skill — sharing is only effective where the grant exists
     * @param envName       the binding's environment variable
     * @param createdAt     when the share was granted
     */
    public record Share(String granteeUserId, String skillId, String envName, String createdAt) {
    }
}
