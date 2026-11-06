package com.skillmasterai.usecase;

import com.skillmasterai.modules.skill.SkillLicense;
import com.skillmasterai.modules.skill.SkillSource;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the license sidecar that travels beside the content. It is generated at export time, never
 * stored as a version file: the content digest must not change because the platform attached a
 * notice.
 */
public final class LicenseSidecar {

    public static final String LICENSE_JSON = ".skillmaster/license.json";
    public static final String LICENSE = ".skillmaster/LICENSE";
    public static final String ATTRIBUTION = ".skillmaster/attribution.md";

    private LicenseSidecar() {
    }

    public static Map<String, byte[]> files(SkillLicense license, SkillSource origin) {
        return files(license, origin, true, null);
    }

    /**
     * @param writeLicenseText false when the version already carries the upstream license file; the
     *                         sidecar then points at that file instead of writing a second copy
     */
    public static Map<String, byte[]> files(SkillLicense license, SkillSource origin,
            boolean writeLicenseText) {
        return files(license, origin, writeLicenseText, null);
    }

    /**
     * @param revision the commit this version came from, when the source recorded one; it is
     *                 provenance for `attribution.md`, not a field of the content
     */
    public static Map<String, byte[]> files(SkillLicense license, SkillSource origin,
            boolean writeLicenseText, String revision) {
        if (license == null) {
            return Map.of();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(LICENSE_JSON, json(license, origin).getBytes(StandardCharsets.UTF_8));
        if (writeLicenseText && license.licenseText() != null
                && !license.licenseText().isBlank()) {
            files.put(LICENSE, license.licenseText().getBytes(StandardCharsets.UTF_8));
        }
        files.put(ATTRIBUTION, attribution(license, origin, revision).getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static String json(SkillLicense license, SkillSource origin) {
        StringBuilder json = new StringBuilder("{\"schema_version\":1");
        json.append(",\"spdx\":").append(quote(license.spdx()));
        json.append(",\"name\":").append(quote(license.name()));
        if (license.copyright() != null) {
            json.append(",\"copyright\":").append(quote(license.copyright()));
        }
        json.append(",\"status\":").append(quote(license.status().wire()));
        if (origin != null) {
            json.append(",\"origin\":{");
            json.append("\"kind\":").append(quote(origin.kind()));
            json.append(",\"locator\":").append(quote(origin.locator()));
            if (origin.path() != null && !origin.path().isBlank()) {
                json.append(",\"path\":").append(quote(origin.path()));
            }
            if (origin.url() != null) {
                json.append(",\"url\":").append(quote(origin.url()));
            }
            json.append('}');
        }
        if (license.sourcePath() != null) {
            json.append(",\"license_file\":").append(quote(license.sourcePath()));
        }
        return json.append('}').toString();
    }

    private static String attribution(SkillLicense license, SkillSource origin, String revision) {
        StringBuilder out = new StringBuilder("# 来源与许可\n\n");
        if (origin != null) {
            out.append("- 来源：").append(origin.label());
            if (origin.locator() != null && !origin.locator().isBlank()) {
                out.append(" · ").append(origin.locator());
            }
            out.append('\n');
            if (origin.path() != null && !origin.path().isBlank()) {
                out.append("- 路径：").append(origin.path()).append('\n');
            }
            if (revision != null && !revision.isBlank()) {
                out.append("- commit：").append(revision).append('\n');
            }
        }
        out.append("- 许可：").append(license.spdx());
        if (license.sourcePath() != null) {
            out.append("（原文：").append(license.sourcePath()).append("）");
        }
        out.append('\n');
        if (license.copyright() != null) {
            out.append("- 版权：").append(license.copyright()).append('\n');
        }
        out.append("- 修改：由 SkillMaster 导出并附上本说明（如内容被平台归一化）\n");
        return out.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
