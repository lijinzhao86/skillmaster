package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.version.VersionPin;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * §4.1's address grammar, which until now had no unit test at all: the only coverage the API layer
 * had was through HTTP, where a malformed suffix and a missing skill are the same 404.
 *
 * <p>What matters most is the last two groups. A suffix this parser rejects must not become a second
 * kind of error — §4.1 gives every unresolvable address the same answer, and widening the grammar is
 * how that quietly stops being true.
 */
class SkillAddressTest {

    private static final String HEX = "a".repeat(64);

    @Test
    void readsABareNameAsLatest() {
        SkillAddress address = address("pdf-tools");

        assertThat(address.name()).isEqualTo("pdf-tools");
        assertThat(address.pin()).isEqualTo(new VersionPin.Latest());
    }

    @Test
    void readsAVersionNameSuffixAsAPin() {
        SkillAddress address = address("pdf-tools@1.2.3");

        assertThat(address.name()).isEqualTo("pdf-tools");
        assertThat(address.pin()).isEqualTo(new VersionPin.Named("1.2.3"));
    }

    @Test
    void readsEveryShapeSemverAllows() {
        // The grammar is SemVer 2.0.0's, unmodified: a prerelease and build metadata are versions,
        // and pinning one has to work as well as pinning a release does.
        assertThat(address("pdf-tools@0.0.1").pin()).isEqualTo(new VersionPin.Named("0.0.1"));
        assertThat(address("pdf-tools@1.0.0-rc.1").pin())
                .isEqualTo(new VersionPin.Named("1.0.0-rc.1"));
        assertThat(address("pdf-tools@1.0.0+build.1").pin())
                .isEqualTo(new VersionPin.Named("1.0.0+build.1"));
    }

    @Test
    void readsADigestSuffixWithoutItsPresentationPrefix() {
        // The pin carries storage's bare hex; the prefix the address spells is the API's to add and
        // strip, exactly as it does when presenting a digest in a response.
        SkillAddress address = address("pdf-tools@sha256:" + HEX);

        assertThat(address.name()).isEqualTo("pdf-tools");
        assertThat(address.pin()).isEqualTo(new VersionPin.Digest(HEX));
    }

    @Test
    void keepsANameThatIsNotAsciiIntact() {
        // M5 permits a non-ASCII name, so one reaches here percent-decoded by the container. The
        // parser must not decode a second time, or a name holding a literal '%' would be mangled.
        // That second case cannot arrive over HTTP any more — M5 refuses such a name and the
        // servlet firewall refuses its encoding — so it pins the parser's property rather than a
        // live input.
        assertThat(address("飞书任务").name()).isEqualTo("飞书任务");
        assertThat(address("100%-done").name()).isEqualTo("100%-done");
    }

    @Test
    void namesNothingWhenTheSuffixIsNotAVersion() {
        // The same judgement the host's own plugin loader applies, so that a version name that works
        // in one place works in the other (ADR 0033). Each of these is a near miss that a lenient
        // parser would accept, and each would then be a second spelling of a real version — the
        // drift the grammar exists to prevent.
        namesNothing("pdf-tools@1.0");
        namesNothing("pdf-tools@1");
        namesNothing("pdf-tools@1.2");
        namesNothing("pdf-tools@v1.0.0");
        namesNothing("pdf-tools@01.2.3");
        namesNothing("pdf-tools@1.02.3");
        namesNothing("pdf-tools@latest");
        namesNothing("pdf-tools@1.0.0.0");
        namesNothing("pdf-tools@-1.0.0");
        namesNothing("pdf-tools@abc");
        namesNothing("pdf-tools@");
    }

    @Test
    void namesNothingForTheOldIntegerForm() {
        // `@3` was the address form until ADR 0033 replaced the server-allocated integer with the
        // author's semver. It is now indistinguishable from a typo, which is the point of the
        // single-answer rule: an address that no longer resolves says nothing about why.
        namesNothing("pdf-tools@3");
        namesNothing("pdf-tools@03");
        namesNothing("pdf-tools@0");
    }

    @Test
    void namesNothingWhenTheSuffixIsNotAWellFormedDigest() {
        // A digest is fixed-width lowercase hex. Accepting a near miss would mean a lookup that can
        // never succeed while looking like a valid address.
        namesNothing("pdf-tools@sha256:" + "a".repeat(63));
        namesNothing("pdf-tools@sha256:" + "a".repeat(65));
        namesNothing("pdf-tools@sha256:" + "A".repeat(64));
        namesNothing("pdf-tools@sha256:");
        namesNothing("pdf-tools@" + HEX);
    }

    @Test
    void namesNothingWhenASecondSuffixSeparatorAppears() {
        // Only one '@' can belong to an address, because M5 keeps them out of names. A segment with
        // two therefore names nothing rather than resolving to the first one's name.
        namesNothing("pdf-tools@1.0.0@2.0.0");
        namesNothing("pdf-tools@sha256:" + HEX + "@1.0.0");
    }

    private static SkillAddress address(String segment) {
        Optional<SkillAddress> parsed = SkillAddress.parse(segment);
        assertThat(parsed).as("'%s' did not parse", segment).isPresent();
        return parsed.orElseThrow();
    }

    private static void namesNothing(String segment) {
        assertThat(SkillAddress.parse(segment))
                .as("'%s' must resolve to nothing rather than be repaired", segment)
                .isEmpty();
    }
}
