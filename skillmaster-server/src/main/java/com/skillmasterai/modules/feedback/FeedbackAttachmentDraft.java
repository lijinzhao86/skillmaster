package com.skillmasterai.modules.feedback;

/**
 * A validated attachment before its feedback row exists (v65).
 *
 * <p>The use case builds this after the type, size and digest checks; {@link FeedbackService}
 * turns it into a {@link FeedbackAttachment} once it knows the feedback id. Splitting the two
 * records is what lets the bytes be uploaded before the database transaction starts without the
 * service ever seeing a file.
 */
public record FeedbackAttachmentDraft(
        String id,
        int position,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String objectKey) {
}
