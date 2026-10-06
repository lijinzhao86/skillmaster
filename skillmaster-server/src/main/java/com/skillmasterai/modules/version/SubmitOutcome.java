package com.skillmasterai.modules.version;

/**
 * What submitting a version produced.
 *
 * <p>Note what is absent: anything about publishing. A submission leaves the version a draft and
 * changes nothing the consumption plane can see (ADR 0031), so there is no "what is live now" to
 * report — the caller that needs that publishes first and reads
 * {@link SkillVersionService#publishVersion}.
 *
 * @param number      the skill's Nth distinct content (ADR 0012). Read back from the stored row, not
 *                    computed by the caller: on a replay this is the original number, and a number
 *                    that was never assigned would be a lie about an address that works.
 * @param submittedAt RFC3339 UTC, when the version was first submitted. On a replay it is the
 *                    original rather than now.
 * @param created     whether this call created the version. False means identical content was
 *                    already there and the unique constraint did its job (ADR 0005) — the version
 *                    is the existing one, which may already be published or still a draft.
 *                    Callers render this distinction as 201 versus 200.
 * @param state       {@link VersionState} — which of the three the version it names is in. A caller
 *                    cannot derive it: on a replay, identical content answers with whatever row
 *                    already holds that digest, and that row may be a draft, published, or even
 *                    discarded, since the unique constraint is on the content rather than on state.
 *                    Without it a client can only guess, and the guess that reads best — "you have
 *                    just submitted a draft" — is the one that is sometimes false.
 */
public record SubmitOutcome(
        String skillId,
        int number,
        String digest,
        int fileCount,
        long totalBytes,
        String submittedAt,
        boolean created,
        String state) {
}
