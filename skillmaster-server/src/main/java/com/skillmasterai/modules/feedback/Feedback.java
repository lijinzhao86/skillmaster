package com.skillmasterai.modules.feedback;

import java.util.Map;

/**
 * One feedback document, as M18 stores it.
 *
 * <p>{@code contentSha256} is the server's digest of {@code content}, never a value the client
 * supplied. {@code consentVersion} is the privacy policy version this deployment served when the
 * row was written, for the same reason.
 *
 * @param receipt   the value a person sees and types: {@code FB-YYYYMMDD-XXXXXX}
 * @param source    the channel the submission arrived on: {@code cli} in v64
 * @param archiveKey the object key of the durability copy, or null before the archive job ran
 * @param diagnostics only the whitelisted keys; null when none were sent
 */
public record Feedback(
        String id,
        String receipt,
        String userId,
        String skillAddress,
        String content,
        String contentSha256,
        String consentVersion,
        String consentAt,
        String source,
        Map<String, Object> diagnostics,
        String archiveKey,
        String archivedAt,
        String createdAt) {

    public Feedback {
        diagnostics = diagnostics == null ? null : Map.copyOf(diagnostics);
    }
}
