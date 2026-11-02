package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.blob.BlobSettings;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The real {@code application.yml} and the flat {@link BlobSettings} record have to agree.
 *
 * <p>v39 shipped them disagreeing: the YAML nested the OSS keys under {@code blobs.oss.*}, the
 * record is flat, and Spring silently ignored the unknown subtree — so setting every OSS variable
 * left object storage disabled. A unit test that constructs {@code defaults()} cannot see that; this
 * one binds the file.
 */
class BlobSettingsBindingTest {

    @Test
    void theYamlBindsItsOssKeysOntoTheFlatRecord() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // What a deployment sets; a map source outranks the YAML's placeholders.
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
                "skillmaster.blobs.oss-enabled", "true",
                "skillmaster.blobs.endpoint", "https://oss.example.com",
                "skillmaster.blobs.bucket", "skills-private",
                "skillmaster.blobs.role-name", "skillmaster-oss")));
        List<org.springframework.core.env.PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml",
                        new ClassPathResource("application.yml"));
        for (org.springframework.core.env.PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }

        BlobSettings settings = Binder.get(environment)
                .bind("skillmaster.blobs", Bindable.of(BlobSettings.class))
                .orElseThrow(() -> new AssertionError("skillmaster.blobs did not bind"));

        assertThat(settings.ossEnabled()).isTrue();
        assertThat(settings.endpoint()).isEqualTo("https://oss.example.com");
        assertThat(settings.bucket()).isEqualTo("skills-private");
        assertThat(settings.roleName()).isEqualTo("skillmaster-oss");
        assertThat(settings.thresholdBytes()).isEqualTo(1024L * 1024L);
        assertThat(settings.maxBytes()).isEqualTo(100L * 1024L * 1024L);
    }
}
