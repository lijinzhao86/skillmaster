package com.skillmasterai.modules.account.internal;

import com.skillmasterai.modules.account.LegalConsent;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes {@code legal_consent} — one row per account (v56).
 *
 * <p>An insert, not an upsert: registration writes it once, and a second row for the same account
 * would mean a second registration. A future re-consent needs history and is a new table then, not
 * a silent overwrite now.
 */
public final class LegalConsentService implements LegalConsent {

    private final JdbcClient jdbc;

    public LegalConsentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String userId, String termsVersion, String privacyVersion, String at) {
        jdbc.sql("INSERT INTO legal_consent (user_id, terms_version, privacy_version, consented_at)"
                        + " VALUES (:userId, :terms, :privacy, :at)")
                .param("userId", userId)
                .param("terms", termsVersion)
                .param("privacy", privacyVersion)
                .param("at", at)
                .update();
    }
}
