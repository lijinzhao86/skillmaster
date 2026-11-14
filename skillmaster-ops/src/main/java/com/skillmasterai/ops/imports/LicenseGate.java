package com.skillmasterai.ops.imports;

import com.skillmasterai.ops.imports.LicenseStatus;
import com.skillmasterai.ops.imports.Spdx;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * Only permissive licenses pass (ADR 0155).
 *
 * <p>The file at the source is authoritative; the frontmatter declaration is the fallback when there
 * is no file, and a second opinion when there is one. A missing license does not block — it syncs
 * with a blank license and a note, because the alternative is silently dropping a skill whose
 * upstream simply never added a LICENSE file.
 *
 * <p>The whitelist is deliberately a fixed table rather than a configurable one: this is a product
 * rule, and a deployment that could widen it could turn the official namespace into a redistribution
 * point for GPL content.
 */
public final class LicenseGate {

    private static final Set<String> ALLOWED = Set.of(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "MPL-2.0",
            "Unlicense", "CC0-1.0", "CC-BY-4.0");

    private static final Map<String, String> ALIASES = aliases();

    private final ObjectMapper json;

    public LicenseGate(ObjectMapper json) {
        this.json = json;
    }

    public LicenseDecision decide(LicenseFile file, String declared) {
        String declaration = clean(declared);
        if (file != null) {
            return fromFile(file, declaration);
        }
        if (declaration != null) {
            return fromDeclaration(declaration);
        }
        return new LicenseDecision(LicenseDecision.Action.SYNC_EMPTY, "NOASSERTION", "未声明",
                null, null, null, null, LicenseStatus.MISSING, "{}", "（未声明）");
    }

    private LicenseDecision fromFile(LicenseFile file, String declared) {
        String spdx = clean(file.spdx());
        if (spdx == null || !ALLOWED.contains(spdx)) {
            return LicenseDecision.blocked("license_not_allowed:" + (spdx == null ? "unknown" : spdx));
        }
        String note = null;
        if (declared != null && !sameLicense(declared, spdx)) {
            note = "（待校准：声明 " + declared + "）";
        }
        Map<String, String> detail = new LinkedHashMap<>();
        if (declared != null) {
            detail.put("declared", declared);
        }
        return new LicenseDecision(LicenseDecision.Action.SYNC, spdx,
                file.name() == null || file.name().isBlank() ? spdx : file.name(),
                file.text(), file.path(), file.url(),
                file.text() == null ? null : Spdx.copyright(file.text()),
                LicenseStatus.OK, detail(detail), note);
    }

    private LicenseDecision fromDeclaration(String declared) {
        String canonical = canonicalOf(declared);
        if (canonical == null) {
            return LicenseDecision.blocked("license_not_allowed:" + declared);
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("declared", declared);
        return new LicenseDecision(LicenseDecision.Action.SYNC, canonical, declared, null, null,
                null, null, LicenseStatus.DECLARED_ONLY, detail(detail), null);
    }

    private static boolean sameLicense(String declared, String spdx) {
        String canonical = canonicalOf(declared);
        return canonical != null && canonical.equalsIgnoreCase(spdx);
    }

    private static String canonicalOf(String declared) {
        String cleaned = clean(declared);
        if (cleaned == null) {
            return null;
        }
        String alias = ALIASES.get(cleaned.toLowerCase(Locale.ROOT));
        if (alias != null) {
            return alias;
        }
        return ALLOWED.stream().filter(allowed -> allowed.equalsIgnoreCase(cleaned))
                .findFirst().orElse(null);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String detail(Map<String, String> values) {
        if (values.isEmpty()) {
            return "{}";
        }
        try {
            return json.writeValueAsString(values);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /** The spellings people actually write, mapped onto the SPDX ids this gate knows. */
    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("mit license", "MIT");
        aliases.put("the mit license", "MIT");
        aliases.put("apache 2.0", "Apache-2.0");
        aliases.put("apache license 2.0", "Apache-2.0");
        aliases.put("apache-2", "Apache-2.0");
        aliases.put("apache license, version 2.0", "Apache-2.0");
        aliases.put("bsd 2-clause", "BSD-2-Clause");
        aliases.put("bsd 3-clause", "BSD-3-Clause");
        aliases.put("bsd-3", "BSD-3-Clause");
        aliases.put("mpl 2.0", "MPL-2.0");
        aliases.put("mozilla public license 2.0", "MPL-2.0");
        aliases.put("public domain", "Unlicense");
        aliases.put("cc0", "CC0-1.0");
        aliases.put("cc by 4.0", "CC-BY-4.0");
        aliases.put("cc-by-4.0", "CC-BY-4.0");
        return Map.copyOf(aliases);
    }
}
