package com.skillmasterai.modules.skill;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads public repository facts into the generic signal array.
 *
 * <p><strong>Only fixed endpoints, never a URL supplied by the caller.</strong> The locator is
 * {@code owner/repo}; the adapter composes the GitHub API URLs itself, so a hostile locator cannot
 * turn a publish into an SSRF request.
 */
public final class GitHubSourceAdapter implements SourceAdapter {

    public static final String KIND = "github";

    private static final Pattern LOCATOR =
            Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,38})/[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern COPYRIGHT =
            Pattern.compile("(?im)^.*copyright.*$");
    private static final String API = "https://api.github.com";
    private static final List<String> LICENSE_NAMES =
            List.of("LICENSE", "LICENSE.MD", "LICENSE.TXT", "COPYING", "NOTICE");

    private final HttpClient http;
    private final ObjectMapper json;
    private final String api;
    /** Read once, never logged: an unauthenticated call is allowed 60 times an hour. */
    private final String token = System.getenv("SKILLMASTER_GITHUB_TOKEN");

    public GitHubSourceAdapter(ObjectMapper json) {
        this(json, API);
    }

    /** The base URL is a seam for tests: the adapter never reads it from a caller's locator. */
    GitHubSourceAdapter(ObjectMapper json, String api) {
        this.json = json;
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public SourceBinding parse(String locator, String path) {
        String clean = locator == null ? "" : locator.trim();
        if (!LOCATOR.matcher(clean).matches()) {
            throw new InvalidSourceException("a GitHub locator must be owner/repo");
        }
        String subpath = path == null ? "" : path.trim();
        if (subpath.startsWith("/") || subpath.contains("..")) {
            throw new InvalidSourceException("a source path must be relative and may not contain ..");
        }
        return new SourceBinding(KIND, clean, subpath, "GitHub",
                "https://github.com/" + clean);
    }

    @Override
    public FetchedSource fetch(SourceBinding binding, String revisionHint) {
        try {
            JsonNode repo = get("/repos/" + binding.locator());
            if (repo == null) {
                return new FetchedSource(null, "[]", SourceSnapshot.ERROR, null);
            }
            List<Map<String, String>> signals = new ArrayList<>();
            addCount(signals, "stars", "Stars", repo.path("stargazers_count").asLong(),
                    binding.url() + "/stargazers");
            addCount(signals, "forks", "Forks", repo.path("forks_count").asLong(),
                    binding.url() + "/forks");
            String pushed = date(repo.path("pushed_at").asText(""));
            if (!pushed.isEmpty()) {
                signals.add(signal("last_commit", "最近提交", "date", pushed, pushed, null));
            }
            String revision = commit(binding);
            return new FetchedSource(revision, json.writeValueAsString(signals),
                    SourceSnapshot.OK, license(binding));
        } catch (InvalidSourceException failure) {
            throw failure;
        } catch (Exception failure) {
            return new FetchedSource(null, "[]", SourceSnapshot.ERROR, null);
        }
    }

    private String commit(SourceBinding binding) {
        try {
            String path = binding.path() == null ? "" : binding.path();
            String suffix = path.isEmpty() ? "" : "?path=" + path + "&per_page=1";
            JsonNode commits = get("/repos/" + binding.locator() + "/commits" + suffix);
            if (commits != null && commits.isArray() && !commits.isEmpty()) {
                return commits.get(0).path("sha").asText("");
            }
        } catch (Exception ignored) {
            // A commit is provenance, not the reason the page exists; the rest of the snapshot stands.
        }
        return null;
    }

    /**
     * The license nearest to the skill, not the repository's headline license.
     *
     * <p>A repository can hold several skills with different licenses; the one on the repository
     * root answers a different question. Walk from the skill's directory upward and take the first
     * license file found; only when there is none does the repository's own license answer.
     */
    private LicenseCandidate license(SourceBinding binding) {
        String dir = binding.path() == null ? "" : binding.path().trim();
        while (!dir.isEmpty()) {
            LicenseCandidate found = licenseIn(binding, dir);
            if (found != null) {
                return found;
            }
            int slash = dir.lastIndexOf('/');
            dir = slash < 0 ? "" : dir.substring(0, slash);
        }
        return rootLicense(binding);
    }

    private LicenseCandidate licenseIn(SourceBinding binding, String dir) {
        try {
            JsonNode listing = get("/repos/" + binding.locator() + "/contents/" + encodePath(dir));
            if (listing == null || !listing.isArray()) {
                return null;
            }
            for (JsonNode entry : listing) {
                String name = entry.path("name").asText("");
                if (!LICENSE_NAMES.contains(name.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                String path = entry.path("path").asText("");
                JsonNode file = get("/repos/" + binding.locator() + "/contents/" + encodePath(path));
                if (file == null) {
                    continue;
                }
                String encoded = file.path("content").asText("");
                if (encoded.isEmpty()) {
                    continue;
                }
                String text = new String(Base64.getMimeDecoder().decode(encoded),
                        StandardCharsets.UTF_8);
                String spdx = detectSpdx(text);
                String label = spdx.equals("NOASSERTION") ? "其他许可" : spdx;
                return new LicenseCandidate(spdx, label, copyright(text), text, path,
                        file.path("html_url").asText(binding.url()), LicenseStatus.OK.wire(), "{}");
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private LicenseCandidate rootLicense(SourceBinding binding) {
        try {
            JsonNode body = get("/repos/" + binding.locator() + "/license");
            if (body == null) {
                return null;
            }
            String encoded = body.path("content").asText("");
            if (encoded.isEmpty()) {
                return null;
            }
            String text = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
            String spdx = body.path("license").path("spdx_id").asText("NOASSERTION");
            String name = body.path("license").path("name").asText(spdx);
            String path = body.path("path").asText("LICENSE");
            String url = body.path("html_url").asText(binding.url());
            return new LicenseCandidate(spdx, name, copyright(text), text, path, url,
                    LicenseStatus.OK.wire(), "{}");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encodePath(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .map(segment -> java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    /** The fixed table the product names, recognised from the text itself. */
    private static String detectSpdx(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("apache license") && lower.contains("version 2.0")) {
            return "Apache-2.0";
        }
        if (lower.contains("gnu affero general public license")) {
            return "AGPL-3.0";
        }
        if (lower.contains("gnu general public license")) {
            return lower.contains("version 3") ? "GPL-3.0" : "GPL-2.0";
        }
        if (lower.contains("mozilla public license version 2.0")
                || lower.contains("mozilla public license, v. 2.0")) {
            return "MPL-2.0";
        }
        if (lower.contains("permission is hereby granted, free of charge")) {
            return "MIT";
        }
        if (lower.contains("permission to use, copy, modify, and/or distribute this software")) {
            return "ISC";
        }
        if (lower.contains("redistribution and use in source and binary forms")) {
            return lower.contains("neither the name") ? "BSD-3-Clause" : "BSD-2-Clause";
        }
        if (lower.contains("this is free and unencumbered software released into the public domain")) {
            return "Unlicense";
        }
        if (lower.contains("cc0 1.0 universal")) {
            return "CC0-1.0";
        }
        if (lower.contains("creative commons attribution 4.0")) {
            return "CC-BY-4.0";
        }
        return "NOASSERTION";
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api + path))
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "skillmaster");
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return json.readTree(response.body());
    }

    private static void addCount(List<Map<String, String>> signals, String key, String label,
            long value, String url) {
        signals.add(signal(key, label, "count", Long.toString(value), abbreviate(value), url));
    }

    private static Map<String, String> signal(String key, String label, String kind, String value,
            String display, String url) {
        Map<String, String> signal = new LinkedHashMap<>();
        signal.put("key", key);
        signal.put("label", label);
        signal.put("kind", kind);
        signal.put("value", value);
        signal.put("display", display);
        if (url != null) {
            signal.put("url", url);
        }
        return signal;
    }

    private static String abbreviate(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        }
        return Long.toString(value);
    }

    private static String date(String timestamp) {
        return timestamp.length() >= 10 ? timestamp.substring(0, 10) : timestamp;
    }

    private static String copyright(String text) {
        Matcher matcher = COPYRIGHT.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
    }
}
