package com.skillmasterai.modules.skill;

import java.util.List;

/**
 * What a known license permits and requires, as facts rather than legal advice. Every row links to
 * the full text; the page says "以全文为准".
 */
public final class LicenseFacts {

    private LicenseFacts() {
    }

    public record Facts(List<String> allows, List<String> requires) {
    }

    public static Facts of(String spdx) {
        if (spdx == null) {
            return new Facts(List.of(), List.of());
        }
        return switch (spdx) {
            case "MIT", "ISC", "BSD-2-Clause", "BSD-3-Clause" ->
                    new Facts(List.of("commercial", "modify", "redistribute"),
                            List.of("notice"));
            case "Apache-2.0" ->
                    new Facts(List.of("commercial", "modify", "redistribute", "patent"),
                            List.of("notice", "state-changes"));
            case "MPL-2.0" ->
                    new Facts(List.of("commercial", "modify", "redistribute"),
                            List.of("notice", "same-license-files"));
            case "GPL-2.0", "GPL-3.0" ->
                    new Facts(List.of("commercial", "modify", "redistribute"),
                            List.of("notice", "same-license"));
            case "AGPL-3.0" ->
                    new Facts(List.of("commercial", "modify", "redistribute"),
                            List.of("notice", "same-license", "network"));
            case "Unlicense", "CC0-1.0" ->
                    new Facts(List.of("commercial", "modify", "redistribute"), List.of());
            case "CC-BY-4.0" ->
                    new Facts(List.of("commercial", "modify", "redistribute"),
                            List.of("attribution"));
            default -> new Facts(List.of(), List.of());
        };
    }
}
