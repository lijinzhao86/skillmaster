package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.config.SkillmasterProperties.Sync;
import com.skillmasterai.modules.ingest.IngestLimits;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.webmvc.autoconfigure.WebMvcProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The two ceilings v50's first full import hit: the sync's own size limits, and the async timeout
 * the streaming endpoints run under.
 *
 * <p>The sync shipped stricter than an ordinary ingest, so the same skill passed through the editor
 * and was blocked by the importer. Separately, no {@code spring.mvc.async.request-timeout} was set
 * at all, which put every streaming response on the framework's default and cut a real batch publish
 * off mid-row. A unit test constructing {@code Sync} cannot see either; this one binds the real
 * file, the way {@link BlobSettingsBindingTest} does for the blob settings.
 */
class ScaleLimitsBindingTest {

    @Test
    void theYamlDoesNotSetTheSyncStricterThanAnOrdinaryIngest() throws IOException {
        Sync sync = bindSync(boundEnvironment());

        assertThat(sync.maxFiles()).isEqualTo(IngestLimits.STANDARD_MAX_FILES);
        assertThat(sync.maxFileBytes()).isEqualTo(IngestLimits.STANDARD_MAX_FILE_BYTES);
        assertThat(sync.maxTotalBytes()).isEqualTo(100L * 1024L * 1024L);
        /* The skill's total must fit inside the unpacked budget. `max-repo-bytes` is an earlier
           ceiling — see application.yml — and is deliberately not asserted here. */
        assertThat(sync.maxTotalBytes()).isLessThanOrEqualTo(sync.maxUnpackedBytes());
    }

    @Test
    void theRecordsOwnDefaultsAgreeWithTheYaml() throws IOException {
        // The YAML always supplies these keys, so a reverted @DefaultValue would be invisible to the
        // binding test above. Bind from an environment without the YAML — only the string fields and
        // concurrency are supplied, so the numeric limits can come only from @DefaultValue — and
        // compare it with the file's answer.
        Sync fromYaml = bindSync(boundEnvironment());
        Sync fromDefaults = bindSync(minimumEnvironment());

        assertThat(fromDefaults.maxFiles()).isEqualTo(fromYaml.maxFiles());
        assertThat(fromDefaults.maxFileBytes()).isEqualTo(fromYaml.maxFileBytes());
        assertThat(fromDefaults.maxTotalBytes()).isEqualTo(fromYaml.maxTotalBytes());
    }

    @Test
    void theStreamingEndpointsDoNotRunOnTheFrameworkDefault() throws IOException {
        // Bound onto Boot's own properties class, not read back from the same key the YAML writes:
        // a misspelled key would be silently ignored by Boot and a getProperty assertion would
        // still pass. The default is 30 s, which a real batch publish exceeds (v50's 53-entry import
        // lost its last row to it).
        WebMvcProperties properties = Binder.get(boundEnvironment())
                .bind("spring.mvc", Bindable.of(WebMvcProperties.class))
                .orElseThrow(() -> new AssertionError("spring.mvc did not bind"));

        assertThat(properties.getAsync().getRequestTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    private static Sync bindSync(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("skillmaster.sync", Bindable.of(Sync.class))
                .orElseThrow(() -> new AssertionError("skillmaster.sync did not bind"));
    }

    /**
     * An environment the YAML is not loaded into.
     *
     * <p>The two token strings carry an empty {@code @DefaultValue}, which makes a completely empty
     * bind fail; supplying irrelevant values for them leaves the numeric limits to their defaults —
     * which is exactly what this test wants to read.
     */
    private static StandardEnvironment minimumEnvironment() {
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("minimum", Map.of(
                "skillmaster.sync.enabled", "false",
                "skillmaster.sync.sources-file", "classpath:sync-sources.yml",
                "skillmaster.sync.github-token", "unused",
                "skillmaster.sync.trigger-token", "unused",
                "skillmaster.sync.concurrency", "5",
                "skillmaster.sync.cron", "0 0 2 * * *")));
        return environment;
    }

    private static StandardEnvironment boundEnvironment() throws IOException {
        StandardEnvironment environment = isolatedEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application.yml",
                new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    /**
     * A standard environment without this machine's variables.
     *
     * <p>The YAML's values are placeholders ({@code ${SKILLMASTER_SYNC_MAX_FILES:512}}), so an
     * exported variable would silently outrank the file and make these tests depend on the shell
     * that started them.
     */
    private static StandardEnvironment isolatedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
