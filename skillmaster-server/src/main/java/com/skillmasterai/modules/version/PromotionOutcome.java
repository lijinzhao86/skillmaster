package com.skillmasterai.modules.version;

/**
 * What publishing a version did.
 *
 * @param skillId the skill's identity, so the caller can audit the act without a second lookup.
 *                The id rather than the address it was reached through, because a rename must not
 *                be able to orphan an audit trail (ADR 0004)
 * @param version the version's name, or null when its author declared none (ADR 0033) — what was
 *                published, as far as a person is concerned
 * @param digest  the version's identity, so a caller can address it without another lookup
 * @param liveAt  the moment this version <em>first</em> went live. Re-publishing an already-published
 *                version — rollback — keeps the original value rather than moving it, so this is not
 *                "when this call happened"
 * @param changed whether the pointer actually moved. False means the version was already the current
 *                one, so nothing was written and there is nothing to audit; the call is idempotent
 *                in effect rather than a second event
 */
public record PromotionOutcome(String skillId, String version, String digest, String liveAt,
        boolean changed) {
}
