package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.gateway.GatewayService;
import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.usecase.PublishGatewaySkillUseCase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §4.5, and §1.5's trap.
 *
 * <p>The assertion that matters most is the 404. §1.5 measured that a host which does not publish
 * this convention answers <em>200 with an HTML SPA catch-all</em> for the preferred path, so a
 * client cannot distinguish "not published here" from "published, badly". Serving a template out of
 * the classpath would produce the same lie from our side; these tests exist to keep that from
 * happening by accident.
 *
 * <p>Every route is requested with <strong>no token at all</strong>. That is not incidental to the
 * test — this channel is how a machine that has never authenticated learns where to authenticate,
 * so a route that required one would be unreachable by the only clients that need it.
 */
@Sql("/sql/truncate-business-tables.sql")
class GatewayIT extends AbstractAccountIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private PublishGatewaySkillUseCase publishGateway;

    @Test
    void everythingIsANotFoundBeforeTheGatewayHasBeenPublished() {
        // The truncation above removed it; nothing has re-published it in this test.
        assertThat(get(GatewayService.INDEX_V1_PATH, null).statusCode()).isEqualTo(404);
        assertThat(get(GatewayService.INDEX_V2_PATH, null).statusCode()).isEqualTo(404);
        assertThat(get(GatewayService.BODY_PATH, null).statusCode()).isEqualTo(404);
        assertThat(getBytes("/.well-known/gateway/skillmaster/SKILL.md", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void unauthenticatedClientsCanBootstrap() {
        publishGateway.publish(properties.publicBaseUrl());

        assertThat(get(GatewayService.INDEX_V1_PATH, null).statusCode()).isEqualTo(200);
        assertThat(get(GatewayService.INDEX_V2_PATH, null).statusCode()).isEqualTo(200);
        assertThat(get(GatewayService.BODY_PATH, null).statusCode()).isEqualTo(200);
    }

    @Test
    void bothIndexesArePublishedAndDescribeTheSameSkill() {
        // §1.5: the preferred path only appeared in CLI 1.4.6, so older clients read the legacy one
        // and nothing else. There is no way to tell from a request which side of that line a client
        // is on, so publishing one of them silently excludes the other.
        publishGateway.publish(properties.publicBaseUrl());

        JsonNode v1 = JSON.readTree(get(GatewayService.INDEX_V1_PATH, null).body()).get("skills");
        JsonNode v2 = JSON.readTree(get(GatewayService.INDEX_V2_PATH, null).body()).get("skills");

        assertThat(v1).hasSize(1);
        assertThat(v2).hasSize(1);
        assertThat(v1.get(0).get("name").asText()).isEqualTo(GatewayService.SKILL_NAME);
        assertThat(v2.get(0).get("name").asText()).isEqualTo(GatewayService.SKILL_NAME);
        assertThat(v1.get(0).get("description").asText())
                .as("the description is the whole discovery experience (§5.2): the directory is not "
                        + "resident, so whether an agent looks here at all depends on this sentence")
                .isNotBlank()
                .isEqualTo(v2.get(0).get("description").asText());
    }

    @Test
    void theV1IndexListsFilesRelativeToTheSkillRoot() {
        // clients compose <index-base>/<name>/<file>, so a path carrying the skill's own name would
        // be fetched at .../skillmaster/skillmaster/SKILL.md
        publishGateway.publish(properties.publicBaseUrl());

        JsonNode files = JSON.readTree(get(GatewayService.INDEX_V1_PATH, null).body())
                .get("skills").get(0).get("files");

        assertThat(files).hasSize(1);
        assertThat(files.get(0).asText()).isEqualTo("SKILL.md");
    }

    @Test
    void theV2IndexCarriesAContentDigestAndAnAbsoluteUrl() {
        publishGateway.publish(properties.publicBaseUrl());

        JsonNode entry = JSON.readTree(get(GatewayService.INDEX_V2_PATH, null).body())
                .get("skills").get(0);

        assertThat(entry.get("type").asText()).isEqualTo("skill-md");
        assertThat(entry.get("url").asText())
                .as("absolute, because a client installing from this index is not necessarily "
                        + "talking to the host that served it")
                .isEqualTo(properties.publicBaseUrl() + GatewayService.BODY_PATH);
        assertThat(entry.get("digest").asText()).matches("[0-9a-f]{64}");
    }

    @Test
    void theV2DigestIsNotTheVersionDigest() {
        // The two hash different things — a manifest versus file contents — and declaring the wrong
        // one makes a client conclude the skill changed on every check, forever, with no error
        // anywhere. Comparing against the value the version row holds is the cheapest way to pin
        // that they are not accidentally the same.
        publishGateway.publish(properties.publicBaseUrl());

        String declared = JSON.readTree(get(GatewayService.INDEX_V2_PATH, null).body())
                .get("skills").get(0).get("digest").asText();
        String versionDigest = jdbc.sql("SELECT digest FROM skill_version").query(String.class).single();

        assertThat(declared).isNotEqualTo(versionDigest);
    }

    @Test
    void theSchemaFieldIsOmittedWhenNoUrlIsConfigured() {
        // §1.5: the draft's URL does not currently resolve, so the exact string cannot be verified
        // from here. An absent field is honest; a guessed one would make a validating client reject
        // an otherwise-correct document.
        publishGateway.publish(properties.publicBaseUrl());

        JsonNode body = JSON.readTree(get(GatewayService.INDEX_V2_PATH, null).body());

        assertThat(body.propertyNames()).doesNotContain("$schema");
    }

    @Test
    void theBodyIsTheSourceWithTheHostSubstituted() {
        publishGateway.publish(properties.publicBaseUrl());

        String served = new String(getBytes(GatewayService.BODY_PATH, null).body(),
                StandardCharsets.UTF_8);

        assertThat(served)
                .as("the placeholder is replaced once, at publish time")
                .contains(properties.publicBaseUrl())
                .doesNotContain("https://<host>");
        assertThat(served).contains("name: skillmaster");
    }

    @Test
    void theBodyIsPublishedAsMarkdown() {
        publishGateway.publish(properties.publicBaseUrl());

        assertThat(getBytes(GatewayService.BODY_PATH, null).headers()
                .firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow())
                .startsWith("text/markdown");
    }

    @Test
    void everyBaseTheIndexMightBeReadUnderServesTheSameBytes() {
        // §1.5: the resolution order is "relative to the path first, then the root", and the file
        // route is specified under a different base than the index. Rather than guess which one a
        // given client uses, all three work.
        publishGateway.publish(properties.publicBaseUrl());
        byte[] canonical = getBytes(GatewayService.BODY_PATH, null).body();

        for (String base : GatewayService.FILE_BASE_ALIASES) {
            HttpResponse<byte[]> response =
                    getBytes("/.well-known/" + base + "/skillmaster/SKILL.md", null);
            assertThat(response.statusCode()).as("base '%s'", base).isEqualTo(200);
            assertThat(response.body()).as("base '%s'", base).isEqualTo(canonical);
        }
    }

    @Test
    void anUnknownBaseIsNotFoundRatherThanServed() {
        // The aliases are a stated set, not a wildcard: `{provider}` would otherwise turn every
        // /.well-known/<anything>/skillmaster/... into an entry point.
        publishGateway.publish(properties.publicBaseUrl());

        assertThat(getBytes("/.well-known/something-else/skillmaster/SKILL.md", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void aFileOutsideTheManifestIsNotFound() {
        publishGateway.publish(properties.publicBaseUrl());

        assertThat(getBytes("/.well-known/gateway/skillmaster/other.md", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void republishingUnchangedContentCreatesNoNewVersion() {
        // §5.2 requires the gateway to be updatable; this is the other half of that — a restart
        // must not make every installed client think it is stale. The substitution is deterministic
        // precisely so that this holds.
        publishGateway.publish(properties.publicBaseUrl());
        long versions = count("SELECT count(*) FROM skill_version");

        publishGateway.publish(properties.publicBaseUrl());

        assertThat(count("SELECT count(*) FROM skill_version")).isEqualTo(versions);
        assertThat(count("SELECT count(*) FROM skill")).isEqualTo(1);
    }

    @Test
    void theGatewayLivesInTheReservedNamespaceAndIsAnOrdinarySkill() {
        String digest = publishGateway.publish(properties.publicBaseUrl()).digest();

        assertThat(jdbc.sql("""
                SELECT n.slug FROM skill s JOIN namespace n ON n.id = s.namespace_id
                WHERE s.name = :name
                """).param("name", GatewayService.SKILL_NAME).query(String.class).single())
                .isEqualTo(GatewayService.RESERVED_NAMESPACE_SLUG);
        assertThat(digest).matches("[0-9a-f]{64}");
    }
}
