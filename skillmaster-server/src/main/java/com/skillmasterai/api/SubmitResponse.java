package com.skillmasterai.api;

import com.skillmasterai.usecase.model.SubmittedSkill;

/**
 * The body of a successful submission.
 *
 * <p>Note what is absent: anything about publishing. A submission lands a draft and changes nothing
 * the consumption plane can see (ADR 0031), so this response reports what arrived and stops. The
 * version's {@code submitted_at} is therefore not the same fact as the detail endpoint's
 * {@code published_at}, and the two shapes deliberately disagree on that one field — a version that
 * has just been submitted has no publish time to report, and saying otherwise would be inventing
 * one.
 *
 * <p>Field names come out in snake_case — that is the documented wire format, set globally in
 * configuration rather than annotated here field by field.
 *
 * @param created false when identical content was already there. The response is still a success —
 *                idempotence is the feature (ADR 0005) — and the status differs (200 versus 201) so
 *                a client can tell a first submission from a replay without comparing digests
 *                itself
 */
public record SubmitResponse(
        String id,
        String name,
        String namespace,
        boolean created,
        Version version) {

    /**
     * @param state {@code draft} / {@code published} / {@code discarded}. The version a replay names
     *              is whatever row already holds that digest, and the unique constraint is on the
     *              content rather than on the state — so it can be any of the three, and a client that
     *              assumes "you have just submitted a draft" is sometimes telling its user to go and
     *              publish something that is already live, or something that has been thrown away.
     */
    public record Version(int number, String digest, int fileCount, long totalBytes,
            String submittedAt, String state) {
    }

    public static SubmitResponse of(SubmittedSkill submitted) {
        return new SubmitResponse(
                submitted.skillId(),
                submitted.name(),
                submitted.namespaceSlug(),
                submitted.created(),
                new Version(
                        submitted.number(),
                        // The stored digest is bare lowercase hex; the API presents it prefixed, as
                        // §4.2 shows. Storage follows ADR 0005's formula literally and the prefix is
                        // a presentation concern.
                        "sha256:" + submitted.digest(),
                        submitted.fileCount(),
                        submitted.totalBytes(),
                        submitted.submittedAt(),
                        submitted.state()));
    }
}
