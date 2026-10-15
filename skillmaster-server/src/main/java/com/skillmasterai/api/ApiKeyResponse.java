package com.skillmasterai.api;

import com.skillmasterai.modules.token.ApiKey;

/**
 * One API key, as the browser plane describes it — everything except the key.
 *
 * <p>Field names are the ones §接口 fixes ({@code expires_at}, {@code last_used_at}, …) and the
 * values are the stored spellings, so nothing here reformats a timestamp or splits a scope list.
 * Two clients read this — the console, which renders it, and {@code GET /api/v1/me}, which answers
 * "which key am I holding" — and a translation layer between them and the row would be a third
 * opinion about what a key is.
 *
 * <p><strong>No {@code user_id}.</strong> Every caller of this shape is looking at its own keys, so
 * the field would carry the same value in every response and one ambient id in a body is one id
 * waiting to be logged.
 *
 * @param scope space-separated, the same vocabulary as the token plane's {@code scope}
 * @param note  null when the person wrote nothing, which is an answer rather than an absence
 */
record ApiKeyResponse(
        String id,
        String name,
        String note,
        String scope,
        String expiresAt,
        String lastUsedAt,
        String revokedAt,
        String createdAt) {

    static ApiKeyResponse of(ApiKey key) {
        return new ApiKeyResponse(key.id(), key.name(), key.note(), key.scope(), key.expiresAt(),
                key.lastUsedAt(), key.revokedAt(), key.createdAt());
    }
}
