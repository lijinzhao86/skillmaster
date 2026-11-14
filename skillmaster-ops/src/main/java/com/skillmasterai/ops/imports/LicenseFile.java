package com.skillmasterai.ops.imports;

/**
 * The license file nearest to the skill, read from the same revision as its content.
 *
 * <p>The declaration is not here: it comes from the skill's own frontmatter, and the gate is the one
 * place the two meet.
 */
public record LicenseFile(String spdx, String name, String text, String path, String url) {
}
