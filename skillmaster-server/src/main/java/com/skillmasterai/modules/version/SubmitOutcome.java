package com.skillmasterai.modules.version;

/**
 * What submitting a version produced.
 *
 * <p>Note what is absent: anything about publishing. A submission leaves the version a draft and
 * changes nothing the consumption plane can see (ADR 0031), so there is no "what is live now" to
 * report — the caller that needs that publishes first and reads
 * {@link SkillVersionService#publishVersion}.
 *
 * @param version     the version name the author declared, or null when they declared none. Read
 *                    back from the stored row rather than taken from the submission: on a replay the
 *                    row is an older one and its name is that row's.
 * @param submittedAt RFC3339 UTC, when the version was first submitted. On a replay it is the
 *                    original rather than now.
 * @param created     whether this call created the version. False means identical content was
 *                    already there and the unique constraint did its job (ADR 0005) — the version
 *                    is the existing one, which may already be published or still a draft.
 *                    Callers render this distinction as 201 versus 200.
 * @param skillCreated whether this call created the <em>skill</em>, which is a different question
 *                    from {@code created}: the latter is true for a new version of an old skill too.
 *                    It is the only way to tell a first submission from a subsequent one now that
 *                    the version number has left the wire (ADR 0033), and it is what a CLI needs to
 *                    say 已创建 rather than 已提交.
 * @param state       {@link VersionState} — which of the three the version it names is in. A caller
 *                    cannot derive it: on a replay, identical content answers with whatever row
 *                    already holds that digest, and that row may be a draft, published, or even
 *                    discarded, since the unique constraint is on the content rather than on state.
 *                    Without it a client can only guess, and the guess that reads best — "you have
 *                    just submitted a draft" — is the one that is sometimes false.
 */
public record SubmitOutcome(
        String skillId,
        String version,
        String digest,
        int fileCount,
        long totalBytes,
        String submittedAt,
        boolean created,
        boolean skillCreated,
        String state) {
}
