package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.skill.LicenseStatus;
import com.skillmasterai.modules.skill.SkillLicense;
import com.skillmasterai.modules.skill.SkillSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The sidecar is generated, never stored: the same version yields the same content digest no matter
 * what the platform knows about its license (v49 A12).
 */
class LicenseSidecarTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SkillLicense license(String text) {
        return new SkillLicense("version-1", "MIT", "MIT License",
                "Copyright (c) 2025 Corey Haines", LicenseStatus.OK, text, "LICENSE",
                "https://example.test/LICENSE", "{}", "2026-11-05T00:00:00Z");
    }

    private static SkillSource origin() {
        return new SkillSource("source-1", "skill-1", SkillSource.ORIGIN, "github",
                "coreyhaines31/marketingskills", "skills/ab-testing", "GitHub",
                "https://github.com/coreyhaines31/marketingskills");
    }

    @Test
    void writesTheStructuredLicenseTheFullTextAndTheAttribution() throws Exception {
        Map<String, byte[]> files = LicenseSidecar.files(license("MIT text"), origin());

        assertThat(files.keySet()).containsExactlyInAnyOrder(
                LicenseSidecar.LICENSE_JSON, LicenseSidecar.LICENSE, LicenseSidecar.ATTRIBUTION);
        JsonNode json = JSON.readTree(files.get(LicenseSidecar.LICENSE_JSON));
        assertThat(json.get("schema_version").asInt()).isEqualTo(1);
        assertThat(json.get("spdx").asText()).isEqualTo("MIT");
        assertThat(json.get("status").asText()).isEqualTo("ok");
        assertThat(json.get("copyright").asText()).isEqualTo("Copyright (c) 2025 Corey Haines");
        assertThat(json.get("origin").get("kind").asText()).isEqualTo("github");
        assertThat(json.get("origin").get("path").asText()).isEqualTo("skills/ab-testing");
        assertThat(json.get("license_file").asText()).isEqualTo("LICENSE");
        assertThat(new String(files.get(LicenseSidecar.LICENSE), StandardCharsets.UTF_8))
                .isEqualTo("MIT text");
        String attribution = new String(files.get(LicenseSidecar.ATTRIBUTION),
                StandardCharsets.UTF_8);
        assertThat(attribution).contains("来源：GitHub · coreyhaines31/marketingskills")
                .contains("路径：skills/ab-testing")
                .contains("许可：MIT（原文：LICENSE）")
                .contains("版权：Copyright (c) 2025 Corey Haines");
    }

    @Test
    void doesNotDuplicateAnUpstreamLicenseFile() {
        Map<String, byte[]> files = LicenseSidecar.files(license("MIT text"), origin(), false);

        assertThat(files.keySet()).containsExactlyInAnyOrder(
                LicenseSidecar.LICENSE_JSON, LicenseSidecar.ATTRIBUTION);
        assertThat(new String(files.get(LicenseSidecar.LICENSE_JSON), StandardCharsets.UTF_8))
                .contains("\"license_file\":\"LICENSE\"");
    }

    @Test
    void doesNotWriteAFullTextFileWhenThereIsNoText() {
        Map<String, byte[]> files = LicenseSidecar.files(license(null), null);

        assertThat(files.keySet()).containsExactlyInAnyOrder(
                LicenseSidecar.LICENSE_JSON, LicenseSidecar.ATTRIBUTION);
        assertThat(new String(files.get(LicenseSidecar.ATTRIBUTION), StandardCharsets.UTF_8))
                .contains("许可：MIT")
                .doesNotContain("来源：");
    }

    @Test
    void noLicenseMeansNoSidecarAtAll() {
        assertThat(LicenseSidecar.files(null, origin())).isEmpty();
    }

    @Test
    void theJsonEscapesWhatTheContentContains() throws Exception {
        Map<String, byte[]> files = LicenseSidecar.files(
                new SkillLicense("v", "MIT", "MIT \"License\"\nSecond line", null,
                        LicenseStatus.OK, null, null, null, "{}", "2026-11-05"),
                null);

        JsonNode json = JSON.readTree(files.get(LicenseSidecar.LICENSE_JSON));
        assertThat(json.get("name").asText()).isEqualTo("MIT \"License\"\nSecond line");
    }
}
