package com.skillmasterai.ops.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.ops.imports.LicenseStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Only permissive licenses pass, and a missing one is a note rather than a refusal (ADR 0155). */
class LicenseGateTest {

    private final LicenseGate gate = new LicenseGate(new ObjectMapper());

    @Test
    void aPermissiveFileSyncs() {
        LicenseDecision decision = gate.decide(file("MIT"), null);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.status()).isEqualTo(LicenseStatus.OK);
        assertThat(decision.spdx()).isEqualTo("MIT");
        assertThat(decision.note()).isNull();
    }

    @Test
    void aCopyleftFileIsBlocked() {
        LicenseDecision decision = gate.decide(file("GPL-3.0"), null);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.note()).contains("license_not_allowed").contains("GPL-3.0");
    }

    @Test
    void anUnrecognisedFileIsBlocked() {
        assertThat(gate.decide(file("NOASSERTION"), null).allowed()).isFalse();
    }

    @Test
    void aMissingFileWithAPermissiveDeclarationSyncsAsDeclaredOnly() {
        LicenseDecision decision = gate.decide(null, "Apache-2.0");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.status()).isEqualTo(LicenseStatus.DECLARED_ONLY);
        assertThat(decision.spdx()).isEqualTo("Apache-2.0");
        assertThat(decision.detail()).contains("declared");
    }

    @Test
    void aMissingFileWithACopyleftDeclarationIsBlocked() {
        assertThat(gate.decide(null, "GPL-3.0").allowed()).isFalse();
    }

    @Test
    void noFileAndNoDeclarationSyncsEmpty() {
        LicenseDecision decision = gate.decide(null, null);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.status()).isEqualTo(LicenseStatus.MISSING);
        assertThat(decision.note()).contains("未声明");
    }

    @Test
    void aFileThatDisagreesWithTheDeclarationWinsAndCarriesTheNote() {
        LicenseDecision decision = gate.decide(file("MIT"), "GPL-3.0");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.spdx()).isEqualTo("MIT");
        assertThat(decision.note()).contains("待校准").contains("GPL-3.0");
        assertThat(decision.detail()).contains("GPL-3.0");
    }

    private static LicenseFile file(String spdx) {
        return new LicenseFile(spdx, spdx, "text", "LICENSE", "https://example/LICENSE");
    }
}
