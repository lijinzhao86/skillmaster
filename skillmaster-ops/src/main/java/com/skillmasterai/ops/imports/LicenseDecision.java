package com.skillmasterai.ops.imports;

import com.skillmasterai.ops.imports.LicenseStatus;

/**
 * What the license gate decided, and every fact needed to write it.
 *
 * <p>{@code note} is the fragment appended to the draft's changelog; {@code detail} is the JSON stored
 * on {@code skill_version_license}. A blocked decision carries neither.
 */
public record LicenseDecision(Action action, String spdx, String name, String text, String path,
        String url, String copyright, LicenseStatus status, String detail, String note) {

    public enum Action { SYNC, SYNC_EMPTY, BLOCK }

    public static LicenseDecision blocked(String reason) {
        return new LicenseDecision(Action.BLOCK, null, null, null, null, null, null, null,
                "{}", reason);
    }

    public boolean allowed() {
        return action != Action.BLOCK;
    }
}
