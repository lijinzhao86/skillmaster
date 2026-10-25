package com.skillmasterai.modules.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.SemVer;
import org.junit.jupiter.api.Test;

/**
 * The one place a declared kind turns into a number (ADR 0091).
 *
 * <p>These are unit tests rather than integration tests on purpose: the arithmetic has no database in
 * it, and every interesting case is a string. The cases are the ones that cost something if they go
 * wrong — a wrong digit is published to everybody who calls the skill without a version.
 */
class ReleaseTypeTest {

    @Test
    void eachKindMovesItsOwnDigitAndZeroesTheOnesBelow() {
        assertThat(ReleaseType.MAJOR.applyTo("1.2.3")).contains("2.0.0");
        assertThat(ReleaseType.MINOR.applyTo("1.2.3")).contains("1.3.0");
        assertThat(ReleaseType.PATCH.applyTo("1.2.3")).contains("1.2.4");
    }

    @Test
    void buildMetadataIsDroppedRatherThanCarriedForward() {
        // `1.2.3+build.7` is the same precedence as `1.2.3`; keeping the metadata would publish a
        // version that differs only in a part SemVer says takes no part.
        assertThat(ReleaseType.PATCH.applyTo("1.2.3+build.7")).contains("1.2.4");
        assertThat(SemVer.isPrerelease("1.2.3+build-7"))
                .as("a hyphen inside metadata is not a prerelease marker")
                .isFalse();
    }

    @Test
    void aPrereleaseBaseProducesNoAnswer() {
        // SemVer gives no single successor to `1.0.0-rc.1`, and ADR 0091 refuses to invent one: the
        // caller has to name the number.
        assertThat(SemVer.isPrerelease("2.0.0-rc.2")).isTrue();
        assertThat(ReleaseType.MINOR.applyTo("2.0.0-rc.2")).isEmpty();
        assertThat(ReleaseType.PATCH.applyTo("2.0.0-rc.2")).isEmpty();
    }

    @Test
    void anInvalidOrAbsentBaseProducesNoAnswer() {
        assertThat(ReleaseType.PATCH.applyTo(null)).isEmpty();
        assertThat(ReleaseType.PATCH.applyTo("")).isEmpty();
        assertThat(ReleaseType.PATCH.applyTo("1.2")).isEmpty();
        assertThat(ReleaseType.PATCH.applyTo("01.2.3"))
                .as("a leading zero is not a version, so it is not a baseline either")
                .isEmpty();
    }

    @Test
    void theWireSpellingIsTheNameLowerCased() {
        assertThat(ReleaseType.from("major")).contains(ReleaseType.MAJOR);
        assertThat(ReleaseType.from("MINOR"))
                .as("a request body is written by people and by CLIs")
                .contains(ReleaseType.MINOR);
        assertThat(ReleaseType.from("MInor")).contains(ReleaseType.MINOR);
        assertThat(ReleaseType.from("patch")).contains(ReleaseType.PATCH);
    }

    @Test
    void anUnknownSpellingIsABadFieldRatherThanABadRequest() {
        // Naming the field is what lets a CLI say `--release bogus` instead of "something failed".
        assertThatThrownBy(() -> ReleaseType.canonicalOrNull("bogus"))
                .isInstanceOf(RequestException.class)
                .hasMessageContaining("release type");
    }

    @Test
    void noDeclarationStaysNull() {
        assertThat(ReleaseType.canonicalOrNull(null)).isNull();
        assertThat(ReleaseType.canonicalOrNull("  ")).isNull();
        assertThat(ReleaseType.canonicalOrNull("patch")).isEqualTo("patch");
    }
}
