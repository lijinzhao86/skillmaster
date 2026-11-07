package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.GitHubTar;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads one skill directory out of a GitHub repository (v50).
 *
 * <p><strong>One commits call per entry, one codeload download per repository per run.</strong> The
 * commits call answers "which commit last touched this directory"; the archive is the repository's
 * default-branch tip, downloaded once and read by every skill in it (ADR 0163): a repository with
 * 177 skills and 128 distinct directory revisions used to be downloaded 128 times — about 30 GB for
 * one 232 MB repository, every download cut off at the timeout. The archive is also where the
 * license comes from, so content and license still come from one tree; the tree is the repository's
 * tip rather than the skill directory's own commit, and that difference is what makes one download
 * enough.
 *
 * <p><strong>The tarball is hostile input</strong> and is handed to {@link GitHubTar}, which enforces
 * the path, entry-type and size ceilings before anything is returned here. {@link #download(String)}
 * writes it to a temporary file the caller owns — a 232 MB archive cannot be held in memory — and
 * the caller deletes it when the run ends; no partial extraction is ever handed on.
 *
 * <p>The base URLs are a test seam. Production always builds them from the fixed strings below; a
 * caller's locator can change the path but never the host.
 */
public final class GitHubContentReader {

    /** What one entry may spend on an archive. Mirrors {@link GitHubTar.Limits}. */
    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes, long maxRepoBytes,
            long maxUnpackedBytes) {
    }

    public record File(String relpath, byte[] content, boolean executable) {
    }

    /** The license nearest to the skill directory, read from the same tree as the content. */
    public record License(String spdx, String name, String text, String path, String url) {
    }

    public record Fetched(List<File> files, License license, String error) {
        static Fetched failed(String reason) {
            return new Fetched(List.of(), null, reason);
        }
    }

    public record Revision(String sha, String error) {
    }

    public record Children(List<String> names, String error) {
    }

    private static final String API = "https://api.github.com";
    private static final String CODELOAD = "https://codeload.github.com";

    private final ObjectMapper json;
    private final String api;
    private final String codeload;
    private final String token;
    private final Limits limits;
    private final HttpClient http;

    public GitHubContentReader(ObjectMapper json, String token, Limits limits) {
        this(json, API, CODELOAD, token, limits);
    }

    /** The base URLs are a seam for tests; they are never read from a locator. */
    public GitHubContentReader(ObjectMapper json, String api, String codeload, String token,
            Limits limits) {
        this.json = json;
        this.api = api;
        this.codeload = codeload;
        this.token = token == null ? "" : token;
        this.limits = limits;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * The latest commit that touched {@code path} (the repository's HEAD when it is empty).
     *
     * <p>A directory that does not exist answers {@code not_found}; an empty path always has the
     * repository's own last commit.
     */
    public Revision revision(String locator, String path) {
        String repo = locator == null ? "" : locator.trim();
        if (!validLocator(repo)) {
            return new Revision(null, "invalid_locator");
        }
        String dir = clean(path);
        String url = api + "/repos/" + repo + "/commits?per_page=1"
                + (dir.isEmpty() ? "" : "&path=" + encodePath(dir));
        try {
            HttpResponse<String> response = get(url, Duration.ofSeconds(10), "application/vnd.github+json");
            if (response.statusCode() == 404) {
                return new Revision(null, "not_found");
            }
            if (response.statusCode() != 200) {
                return new Revision(null, statusReason(response.statusCode()));
            }
            JsonNode body = json.readTree(response.body());
            if (!body.isArray() || body.isEmpty()) {
                return new Revision(null, "no_commits");
            }
            String sha = body.get(0).path("sha").asText("");
            return sha.isBlank() ? new Revision(null, "no_commits") : new Revision(sha, null);
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new Revision(null, "upstream_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Revision(null, "upstream_timeout");
        } catch (Exception failure) {
            return new Revision(null, "upstream_io");
        }
    }

    /** A repository archive on disk, and the commit it was taken at. */
    public record Downloaded(Path file, String ref, String error) {
    }

    /**
     * How long one archive may take. A large repository is tens of megabytes — the biggest source
     * in the 2026-11-07 whitelist is 232 MB and needed 191 seconds from the deployment host — and
     * it is downloaded once per run, not once per skill, so the ceiling can afford to be generous.
     */
    private static final Duration ARCHIVE_TIMEOUT = Duration.ofSeconds(600);

    /**
     * Downloads the whole repository at its default-branch tip — the one archive a run keeps for
     * every skill in it (ADR 0163).
     *
     * <p>The file is the caller's: it must be deleted when the run ends. An error is answered as
     * data, like every other read here, so one unreachable repository fails its entries rather
     * than the run.
     */
    public Downloaded download(String locator) {
        String repo = locator == null ? "" : locator.trim();
        if (!validLocator(repo)) {
            return new Downloaded(null, null, "invalid_locator");
        }
        Revision head = revision(repo, "");
        if (head.error() != null) {
            return new Downloaded(null, null, head.error());
        }
        Path file = null;
        /* One deadline for the whole download, not one per phase: the request timeout stops at the
           headers, so a peer that answers late and then stalls would otherwise spend two ceilings. */
        long deadline = System.nanoTime() + ARCHIVE_TIMEOUT.toNanos();
        try {
            file = Files.createTempFile("skillmaster-sync-", ".tar.gz");
            HttpRequest request = request(codeload + "/" + repo + "/tar.gz/" + head.sha(),
                    ARCHIVE_TIMEOUT, "application/octet-stream").GET().build();
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                /* Closed, not drained: an error body is not worth reusing the connection for, and
                   reading it would be unbounded — the request timeout stops at the headers, so a
                   peer that goes quiet mid-body would block the whole run here. A close that fails
                   must not replace the status with a generic network error. */
                closeQuietly(response.body());
                return failedDownload(file, statusReason(response.statusCode()));
            }
            try (InputStream body = response.body()) {
                String error = copyWithDeadline(body, file, deadline);
                if (error != null) {
                    return failedDownload(file, error);
                }
            }
            return new Downloaded(file, head.sha(), null);
        } catch (java.net.http.HttpTimeoutException timeout) {
            return failedDownload(file, "upstream_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failedDownload(file, "upstream_timeout");
        } catch (Exception failure) {
            return failedDownload(file, "upstream_io");
        }
    }

    /**
     * The files under {@code path} in an archive {@link #download(String)} returned, plus the
     * nearest license file.
     *
     * @param licensePath a repository-relative license file to use instead of the nearest one;
     *                    empty means "find the nearest". A configured path that is not in this
     *                    tree fails the entry — the operator asked for that file, and silently
     *                    falling back to "no license" would sync content the whitelist did not
     *                    intend to allow.
     * @param ref         the commit the archive was taken at, used for the license's link
     */
    public Fetched filesIn(Path archive, String locator, String path, String licensePath,
            String ref) {
        String repo = locator == null ? "" : locator.trim();
        if (!validLocator(repo) || archive == null) {
            return Fetched.failed("invalid_input");
        }
        String dir = clean(path);
        String explicitLicense = clean(licensePath);
        try (InputStream body = Files.newInputStream(archive)) {
            return fetched(extractWithDeadline(body, dir, explicitLicense), dir, repo, ref,
                    explicitLicense);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Fetched.failed("upstream_timeout");
        } catch (Exception failure) {
            return Fetched.failed("upstream_io");
        }
    }

    private Fetched fetched(GitHubTar.Result result, String dir, String repo, String revision,
            String explicitLicense) {
        if (result.error() != null) {
            return Fetched.failed(result.error());
        }
        License license = licenseOf(result.licenses(), dir, repo, revision, explicitLicense);
        if (!explicitLicense.isEmpty() && license == null) {
            return Fetched.failed("license_path_missing");
        }
        List<File> files = result.files().stream()
                .map(entry -> new File(entry.path(), entry.content(), entry.executable()))
                .toList();
        return new Fetched(files, license, null);
    }

    /**
     * Copies the body to {@code file} under a wall-clock ceiling and the repository byte ceiling.
     *
     * <p>The request's own timeout stops at the headers; a peer that goes quiet afterwards would
     * hold this run — and the guard that refuses every later trigger — for as long as it stayed
     * silent. The copy therefore runs on its own thread and is abandoned, with its stream closed,
     * when the ceiling passes.
     */
    private String copyWithDeadline(InputStream body, Path file, long deadlineNanos)
            throws InterruptedException {
        long remaining = TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime());
        if (remaining <= 0) {
            closeQuietly(body);
            return "upstream_timeout";
        }
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "github-archive");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> copy = worker.submit(() -> {
            long total = 0;
            try (OutputStream out = Files.newOutputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    total += read;
                    if (total > limits.maxRepoBytes()) {
                        return "too_large";
                    }
                    out.write(buffer, 0, read);
                }
            } catch (IOException failure) {
                return "upstream_io";
            }
            return null;
        });
        try {
            return copy.get(remaining, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            copy.cancel(true);
            closeQuietly(body);
            return "upstream_timeout";
        } catch (ExecutionException failure) {
            return "upstream_io";
        } finally {
            worker.shutdownNow();
        }
    }

    private static Downloaded failedDownload(Path file, String error) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Best effort: the file lives in the system temporary directory, and a
                // failure to delete it is the operating system's to clean up.
            }
        }
        return new Downloaded(null, null, error);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The status is the answer; closing is best effort.
        }
    }

    /**
     * Reads the body under a wall-clock ceiling.
     *
     * <p>{@link HttpRequest#timeout} stops at the response headers; a peer that goes quiet after
     * them would block {@code tar.read} forever, and the run's guard would then answer 409 to every
     * later trigger until the process restarted. The extraction therefore runs on its own thread and
     * is abandoned — with its stream closed — when the ceiling passes.
     */
    private GitHubTar.Result extractWithDeadline(InputStream body, String dir, String licensePath)
            throws InterruptedException {
        java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "github-tar");
                    thread.setDaemon(true);
                    return thread;
                });
        java.util.concurrent.Future<GitHubTar.Result> future = worker.submit(
                () -> GitHubTar.extract(body, dir, new GitHubTar.Limits(limits.maxFiles(),
                        limits.maxFileBytes(), limits.maxTotalBytes(), limits.maxRepoBytes(),
                        limits.maxUnpackedBytes()), licensePath));
        try {
            return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            try {
                body.close();
            } catch (IOException ignored) {
                // Closing is the point; a failure to close changes nothing about the timeout.
            }
            return GitHubTar.Result.failed("upstream_timeout");
        } catch (java.util.concurrent.ExecutionException failure) {
            return GitHubTar.Result.failed("upstream_io");
        } finally {
            worker.shutdownNow();
        }
    }

    /** The names of the direct subdirectories of {@code path}, hidden ones removed. */
    public Children children(String locator, String path) {
        String repo = locator == null ? "" : locator.trim();
        if (!validLocator(repo)) {
            return new Children(List.of(), "invalid_locator");
        }
        String dir = clean(path);
        String url = api + "/repos/" + repo + "/contents"
                + (dir.isEmpty() ? "" : "/" + encodePath(dir));
        try {
            HttpResponse<String> response = get(url, Duration.ofSeconds(10), "application/vnd.github+json");
            if (response.statusCode() == 404) {
                return new Children(List.of(), "not_found");
            }
            if (response.statusCode() != 200) {
                return new Children(List.of(), statusReason(response.statusCode()));
            }
            JsonNode body = json.readTree(response.body());
            if (!body.isArray()) {
                return new Children(List.of(), "not_a_directory");
            }
            List<String> names = new ArrayList<>();
            for (JsonNode entry : body) {
                String type = entry.path("type").asText("");
                String name = entry.path("name").asText("");
                if ("dir".equals(type) && !name.isBlank() && !name.startsWith(".")) {
                    names.add(name);
                }
            }
            names.sort(Comparator.naturalOrder());
            return new Children(names, null);
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new Children(List.of(), "upstream_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Children(List.of(), "upstream_timeout");
        } catch (Exception failure) {
            return new Children(List.of(), "upstream_io");
        }
    }

    /**
     * The license nearest to {@code dir}: the directory itself first, then its parents, then the
     * repository root. A repository can hold several skills under different licenses, so the
     * headline license is only the answer when nothing closer exists.
     */
    private static License licenseOf(List<GitHubTar.Entry> licenses, String dir, String locator,
            String revision, String explicit) {
        if (licenses.isEmpty()) {
            return null;
        }
        if (!explicit.isEmpty()) {
            return licenses.stream()
                    .filter(entry -> entry.path().equals(explicit))
                    .findFirst()
                    .map(entry -> license(entry, locator, revision))
                    .orElse(null);
        }
        String current = dir;
        while (true) {
            GitHubTar.Entry found = nearestIn(licenses, current);
            if (found != null) {
                return license(found, locator, revision);
            }
            if (current.isEmpty()) {
                return null;
            }
            int slash = current.lastIndexOf('/');
            current = slash < 0 ? "" : current.substring(0, slash);
        }
    }

    private static License license(GitHubTar.Entry entry, String locator, String revision) {
        String text = new String(entry.content(), StandardCharsets.UTF_8);
        String spdx = Spdx.detect(text);
        String name = "NOASSERTION".equals(spdx) ? "其他许可" : spdx;
        String url = "https://github.com/" + locator + "/blob/" + revision + "/" + entry.path();
        return new License(spdx, name, text, entry.path(), url);
    }

    private static GitHubTar.Entry nearestIn(List<GitHubTar.Entry> licenses, String dir) {
        return licenses.stream()
                .filter(entry -> parentOf(entry.path()).equals(dir))
                .min(Comparator.comparingInt(entry -> rank(baseName(entry.path()))))
                .orElse(null);
    }

    private static int rank(String name) {
        int index = Spdx.LICENSE_NAMES.indexOf(name.toUpperCase(Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String clean(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean validLocator(String locator) {
        return locator != null && GitHubSourceAdapter.LOCATOR.matcher(locator.trim()).matches();
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static String statusReason(int status) {
        return switch (status) {
            case 403, 429 -> "rate_limited";
            case 404 -> "not_found";
            default -> "upstream_http_" + status;
        };
    }

    private HttpResponse<String> get(String url, Duration timeout, String accept)
            throws Exception {
        return http.send(request(url, timeout, accept).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String url, Duration timeout, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", accept)
                .header("User-Agent", "skillmaster");
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
