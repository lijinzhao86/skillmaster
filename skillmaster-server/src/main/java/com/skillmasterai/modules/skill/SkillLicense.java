package com.skillmasterai.modules.skill;

/**
 * The license that applies to one version.
 *
 * <p>The full text lives here rather than in a version file on purpose: it must not change the
 * content digest, and it must not be swept by the blob GC that only follows {@code version_file}.
 * The text is small; the sidecar writer puts it into {@code .skillmaster/LICENSE} at download time.
 */
public record SkillLicense(String versionId, String spdx, String name, String copyright,
        LicenseStatus status, String licenseText, String sourcePath, String sourceUrl, String detail,
        String fetchedAt) {
}
