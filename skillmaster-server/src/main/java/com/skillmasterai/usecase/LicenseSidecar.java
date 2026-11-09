package com.skillmasterai.usecase;

import com.skillmasterai.modules.skill.LicenseStatus;
import com.skillmasterai.modules.skill.SkillCopy;
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
        return files(license, origin, writeLicenseText, revision, null);
    }

    /**
     * The same sidecar, plus the source an internal copy came from (v54, ADR 0173).
     *
     * <p><strong>A copy renders attribution even without a license row.</strong> The copy promised
     * to carry its source; a source that declared no license still has a provenance, and a zip that
     * travels without one is the case this sidecar exists to prevent. A version that is neither a
     * copy nor licensed still gets nothing — the empty map is the old behaviour, not a new gap.
     *
     * @param copy the source snapshot, or null for a skill that was not produced by copying
     */
    public static Map<String, byte[]> files(SkillLicense license, SkillSource origin,
            boolean writeLicenseText, String revision, SkillCopy copy) {
        if (license == null && copy == null) {
            return Map.of();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (license != null) {
            files.put(LICENSE_JSON, json(license, origin).getBytes(StandardCharsets.UTF_8));
            if (writeLicenseText && license.licenseText() != null
                    && !license.licenseText().isBlank()) {
                files.put(LICENSE, license.licenseText().getBytes(StandardCharsets.UTF_8));
            }
        }
        files.put(ATTRIBUTION,
                attribution(license, origin, revision, copy).getBytes(StandardCharsets.UTF_8));
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

    private static String attribution(SkillLicense license, SkillSource origin, String revision,
            SkillCopy copy) {
        StringBuilder out = new StringBuilder("# 来源与许可\n\n");
        if (copy != null) {
            /* **The internal source comes first, because it is the nearer fact.** The external
               origin, when there is one, describes where the *original* came from; this line
               describes where this copy came from, and a reader following the chain needs both. */
            out.append("- 复制自：").append(copy.sourceAddress())
                    .append(" @").append(copy.sourceVersionLabel()).append('\n');
        }
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
        if (license != null) {
            /* **The reader's word, not SPDX's sentinel.** A version whose source declared nothing
               still carries a `missing` row with `spdx=NOASSERTION` — printing the sentinel would
               say "NOASSERTION" where every other surface says 未声明 (PRD A6). An unrecognised
               declaration is 其他许可, the same word the CLI and the info page use. */
            String label = license.status() == LicenseStatus.MISSING ? "未声明"
                    : "NOASSERTION".equals(license.spdx()) ? "其他许可" : license.spdx();
            out.append("- 许可：").append(label);
            if (license.sourcePath() != null) {
                out.append("（原文：").append(license.sourcePath()).append("）");
            }
            out.append('\n');
            if (license.copyright() != null) {
                out.append("- 版权：").append(license.copyright()).append('\n');
            }
        } else {
            /* A copy whose source declared no license still carries its provenance; saying the
               license is unknown is the truthful half, not an omission (ADR 0173 §决定 5). */
            out.append("- 许可：未声明\n");
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
