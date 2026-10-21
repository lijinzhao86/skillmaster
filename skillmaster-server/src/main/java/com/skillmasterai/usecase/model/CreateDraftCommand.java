package com.skillmasterai.usecase.model;

import com.skillmasterai.modules.version.VersionPin;

/**
 * What the browser sends to create a draft from a base version and its first edit (v8 TD §接口).
 *
 * @param name       the version's name; required, validated by the caller before this is built
 * @param note       the one-line note, or null
 * @param from       the base version the page saw, or null to let the server resolve the default
 * @param fromDigest the digest the page saw for that base, or null for an empty base
 * @param edit       the first content change; never null
 */
public record CreateDraftCommand(String name, String note, VersionPin from, String fromDigest,
        DraftEdit edit) {
}
