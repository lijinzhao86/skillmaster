package com.skillmasterai.modules.account;

/**
 * An account, as the rest of the system sees one.
 *
 * <p>Carries no phone number, on purpose. Nothing outside M1 needs it — login accepts one and
 * looks an account up by it, and that is the whole of its use — and a value that travels is a value
 * that ends up in a log line or an error message. There is no reason for it to leave this module.
 *
 * <p>{@code handle} is the public username and the slug of the account's personal namespace (§3.2),
 * which is why callers that need the namespace can derive it rather than being told separately.
 *
 * @param userId ULID. The identity every reference points at (ADR 0004).
 * @param handle The account's username, as chosen at registration.
 */
public record Account(String userId, String handle) {
}
