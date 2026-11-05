package com.skillmasterai.modules.skill;

/**
 * A namespace whose {@code official} column is false tried to make a skill public (ADR 0146).
 *
 * <p>Neither a validation failure nor a missing rung: the request is well formed and the caller may
 * administer the skill. What is refused is a platform policy about which namespaces are allowed into
 * the public catalogue, so the answer is its own 403 rather than {@code forbidden} or a 400.
 *
 * <p>Thrown only after the caller's rung has been checked, so a caller who cannot see the skill still
 * gets the one 404 rather than learning which namespaces are official.
 */
public final class PublicIsOfficialOnlyException extends RuntimeException {

    public PublicIsOfficialOnlyException() {
        super("only the official namespaces can publish a skill");
    }
}
