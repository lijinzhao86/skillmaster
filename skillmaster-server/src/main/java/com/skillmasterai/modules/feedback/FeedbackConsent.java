package com.skillmasterai.modules.feedback;

/**
 * What a person is asked to agree to before a feedback document leaves their machine.
 *
 * <p>The notice lives on the server rather than in the CLI: a policy change must reach the person
 * without waiting for a CLI release. The version is the privacy policy version; a client that shows
 * a stale notice is refused with {@code feedback_consent_stale} and fetches this again.
 *
 * @param version the privacy policy version this notice belongs to
 * @param notice  plain text, printed verbatim by the CLI
 */
public record FeedbackConsent(String version, String notice) {
}
