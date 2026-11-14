package com.skillmasterai.ops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything ops needs from its environment.
 *
 * <p>{@code sourcesFile} is a path, not a resource: the whitelist lives on the host and is
 * copied in by the deploy workflow, so changing a source does not rebuild the image.
 */
@ConfigurationProperties(prefix = "skillmaster.ops")
public record OpsProperties(
        String serverUrl,
        String innerToken,
        String sourcesFile,
        String githubToken,
        int concurrency,
        int maxFiles,
        long maxFileBytes,
        long maxTotalBytes,
        long maxRepoBytes,
        long maxUnpackedBytes) {
}
