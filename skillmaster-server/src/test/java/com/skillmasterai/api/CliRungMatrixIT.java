package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * v5 test-plan T1's CLI half: the real binary against the real server, at the rungs where its
 * commands behave differently (T1, and T2's retired-word refusal).
 *
 * <p><strong>Why a subprocess and not a second mock server.</strong> The claim is about the client
 * and the server agreeing: {@code invoke} and {@code read} resolve the version through
 * {@code /metadata} and then fetch bytes themselves, while {@code files} is the manifest and needs
 * {@code browse}. A Go test with a stub server proves the parsing; only the real pair proves the
 * path. The CLI is built once per class and driven through its documented environment variables, so
 * no credential is written to this machine.
 *
 * <p><strong>Skipped when Go is absent.</strong> The server's test suite should not require a
 * second toolchain; when there is no {@code go} on the path this class reports as skipped rather
 * than failing. The acceptance run that produced the result in the test plan was made with Go
 * present.
 */
@Sql("/sql/truncate-business-tables.sql")
class CliRungMatrixIT extends AbstractAccountIT {

    private static final String SKILLS = "/api/v1/skills";
    private static final Path CLI = Path.of("target", "skillmaster-cli-e2e").toAbsolutePath();

    private String owner;
    private String ownerToken;
    private String friend;
    private String friendToken;
    private String other;
    private Path home;

    @BeforeAll
    static void buildCli() throws IOException, InterruptedException {
        assumeTrue(onPath("go"), "the Go toolchain is not installed; the CLI matrix is skipped");

        // A file rather than a pipe: reading a pipe to EOF before `waitFor` makes the timeout
        // inert, and a compiler with a lot to say can fill the pipe and block for ever.
        Path log = Files.createTempFile("skillmaster-cli-build", ".log");
        try {
            Process build = new ProcessBuilder("go", "build", "-o", CLI.toString(),
                    "./cmd/skillmaster")
                    .directory(Path.of("..", "skillmaster-cli").toAbsolutePath().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            boolean finished = build.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                build.destroyForcibly();
            }
            String output = Files.readString(log);
            assertThat(finished).as("go build timed out: %s", output).isTrue();
            assertThat(build.exitValue()).as("the CLI did not build: %s", output).isZero();
        } finally {
            Files.deleteIfExists(log);
        }
    }

    @BeforeEach
    void twoAccounts() throws IOException {
        other = randomUsername();
        registerAndSignIn(other, randomPhone());

        String friendPhone = randomPhone();
        friend = randomUsername();
        registerAndSignIn(friend, friendPhone);
        friendToken = tokenOver(secondSignedInBrowser(friendPhone, PASSWORD));

        owner = randomUsername();
        ownerToken = tokenFor(registerAndSignIn(owner, randomPhone()));
        home = Files.createTempDirectory("skillmaster-cli-home");
    }

    @AfterEach
    void removeHome() throws IOException {
        if (home == null) {
            return;
        }
        try (var paths = Files.walk(home)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort: the directory is a throwaway under the system temp dir
                }
            });
        }
    }

    @Test
    void anInvokeGrantInvokesAndReadsThroughTheCliButCannotList() throws Exception {
        published();
        grant("invoke");

        Run invoked = run(friendToken, "skill", "invoke", address());
        assertThat(invoked.exit()).as("invoke: %s", invoked).isZero();
        assertThat(invoked.out()).contains("body 1.0.0");

        Run read = run(friendToken, "skill", "read", address(), "references/notes.md");
        assertThat(read.exit()).as("read: %s", read).isZero();
        assertThat(read.out()).contains("notes");

        Run cat = run(friendToken, "skill", "cat", address(), "references/notes.md");
        assertThat(cat.exit()).as("cat: %s", cat).isZero();
        assertThat(cat.out()).isEqualTo("notes\n");

        Run files = run(friendToken, "skill", "files", address());
        assertThat(files.exit()).as("files at invoke must be refused: %s", files).isNotZero();
        assertThat(files.err())
                .as("the refusal names the missing rung, so it is the server's 403 and not a 404")
                .contains("权限");
    }

    @Test
    void aBrowseGrantListsTheManifestThroughTheCli() throws Exception {
        published();
        String second = addDraft("2.0.0");
        assertThat(webPost("/web/skills/" + owner + "/pdf-tools/publish", json(Map.of(
                "version", second, "release_name", "2.0.0"))).statusCode())
                .as("a second published version gives the comparison two real sides")
                .isEqualTo(200);
        grant("browse");

        Run files = run(friendToken, "skill", "files", address());
        assertThat(files.exit()).as("files: %s", files).isZero();
        assertThat(files.out()).contains("references/notes.md");

        Run diff = run(friendToken, "version", "diff", address() + "@1.0.0");
        assertThat(diff.exit())
                .as("v6 lets browse compare two published versions (2.0.0 against 1.0.0): %s",
                        diff)
                .isZero();
    }

    @Test
    void theCliRefusesTheRetiredRoleWithoutWritingAGrant() throws Exception {
        published();

        Run share = run(ownerToken, "skill", "share", address(), "--to", other,
                "--role", "viewer");
        assertThat(share.exit()).as("a retired role is refused: %s", share).isNotZero();
        assertThat(share.err()).contains("不再接受");
        assertThat(count("SELECT count(*) FROM skill_grant"))
                .as("nothing was sent, so nothing was written")
                .isZero();
    }

    /**
     * The consumption list and share, end to end: `skills list` and `skill share`.
     *
     * <p>The old spelling was {@code skill list}; v13 renamed it (ADR 0077), and this case follows
     * the current command surface rather than the one it was written against.
     */
    @Test
    void theConsumptionListAndShareWorkThroughTheCli() throws Exception {
        published();
        grant("browse");

        Run listing = run(friendToken, "skills", "list");
        assertThat(listing.exit()).as("list: %s", listing).isZero();
        assertThat(listing.out()).contains("pdf-tools");

        Run share = run(ownerToken, "skill", "share", address(), "--to", friend,
                "--role", "write");
        assertThat(share.exit()).as("share: %s", share).isZero();
        assertThat(count("SELECT count(*) FROM skill_grant WHERE grantee_id ="
                + " (SELECT id FROM app_user WHERE handle = :handle)", Map.of("handle", friend)))
                .isEqualTo(1);

        // The role moved rather than a second row appearing.
        assertThat(count("SELECT count(*) FROM skill_grant")).isEqualTo(1);
        assertThat(run(friendToken, "skills", "list").out()).contains("pdf-tools");
    }

    /** T-PERM's write half: the content commands follow the same ladder. */
    @Test
    void writeAndRemoveNeedTheWriteRung() throws Exception {
        published();
        String draft = addDraft("2.0.0");

        grant("browse");
        Run refusedWrite = runWithInput(friendToken, "hello\n",
                "skill", "write", address() + "@" + draft, "notes/new.md");
        assertThat(refusedWrite.exit())
                .as("browse cannot write: %s", refusedWrite)
                .isNotZero();
        assertThat(refusedWrite.err())
                .as("the draft is not a version browse can see, so the address is simply absent")
                .contains("no skill at that address");
        Run refusedRemove = run(friendToken, "skill", "rm", address() + "@" + draft,
                "references/notes.md");
        assertThat(refusedRemove.exit()).as("browse cannot remove: %s", refusedRemove).isNotZero();
        assertThat(refusedRemove.err())
                .as("same ordinary nothing: the draft is invisible below write")
                .contains("no skill at that address");

        grant("write");
        // `write` changes its own drafts, not somebody else's — that is `maintain`'s exception.
        String ownDraft = addDraftAs(friendToken, "2.1.0");
        Run written = runWithInput(friendToken, "hello\n",
                "skill", "write", address() + "@" + ownDraft, "notes/new.md");
        assertThat(written.exit()).as("write may write its own draft: %s", written).isZero();
        Run readBack = run(friendToken, "skill", "cat", address() + "@" + ownDraft, "notes/new.md");
        assertThat(readBack.exit()).as("cat: %s", readBack).isZero();
        assertThat(readBack.out()).isEqualTo("hello\n");

        Run removed = run(friendToken, "skill", "rm", address() + "@" + ownDraft, "notes/new.md");
        assertThat(removed.exit()).as("write may remove: %s", removed).isZero();
    }

    /** T-ID: a row written before v7 keeps its 26-character ULID and stays addressable. */
    @Test
    void aLegacyUlidDraftIsAddressableThroughTheCli() throws Exception {
        published();
        String shortCode = addDraft("2.0.0");
        String ulid = "01M3HTG7GCCVBGRPAFFSVSF12W";
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,
                        changelog, source, submitted_by, submitted_at, state, state_at, title,
                        description, frontmatter, version)
                SELECT :ulid, skill_id, number + 100, repeat('b', 64), file_count, total_bytes,
                        changelog, source, submitted_by, submitted_at, state, state_at, title,
                        description, frontmatter, NULL
                FROM skill_version WHERE id = :code
                """).param("ulid", ulid).param("code", shortCode).update();
        jdbc.sql("""
                INSERT INTO version_file (version_id, relpath, blob_sha256, size, is_binary)
                SELECT :ulid, relpath, blob_sha256, size, is_binary
                FROM version_file WHERE version_id = :code
                """).param("ulid", ulid).param("code", shortCode).update();

        Run show = run(ownerToken, "version", "show", address() + "@" + ulid);
        assertThat(show.exit()).as("a legacy ULID is still an address: %s", show).isZero();
        Run list = run(ownerToken, "version", "list", address());
        assertThat(list.exit()).as("version list: %s", list).isZero();
        assertThat(list.out()).contains(ulid);
    }

    /** T36–T37: the author-plane version commands, against the real server. */
    @Test
    void theVersionCommandsReadAndEditTheAuthorPlane() throws Exception {
        published();
        String draft = addDraft("2.0.0");

        Run list = run(ownerToken, "version", "list", address());
        assertThat(list.exit()).as("version list: %s", list).isZero();
        assertThat(list.out()).contains("draft");

        Run show = run(ownerToken, "version", "show", address() + "@" + draft);
        assertThat(show.exit()).as("version show: %s", show).isZero();
        assertThat(show.out()).contains(draft);

        Run diff = run(ownerToken, "version", "diff", address() + "@1.0.0");
        assertThat(diff.exit()).as("version diff: %s", diff).isZero();

        Run rename = run(ownerToken, "version", "rename", address() + "@" + draft,
                "--name", "renamed-by-cli");
        assertThat(rename.exit()).as("version rename: %s", rename).isZero();
        Run afterRename = run(ownerToken, "version", "list", address());
        assertThat(afterRename.out()).contains("renamed-by-cli");

        Run note = run(ownerToken, "version", "note", address() + "@" + draft,
                "--note", "note from the CLI");
        assertThat(note.exit()).as("version note: %s", note).isZero();
        assertThat(run(ownerToken, "version", "show", address() + "@" + draft).out())
                .contains("note from the CLI");

        Run publish = run(ownerToken, "version", "publish", address() + "@" + draft);
        assertThat(publish.exit())
                .as("publishing is a page action; the CLI has no such verb")
                .isNotZero();
        assertThat(publish.err()).as("the refusal says where the action lives")
                .contains("网页");

        Run discard = run(ownerToken, "version", "discard", address() + "@" + draft, "--yes");
        assertThat(discard.exit()).as("version discard: %s", discard).isZero();
        Run afterDiscard = run(ownerToken, "version", "show", address() + "@" + draft);
        assertThat(afterDiscard.out()).contains("已丢弃");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private String address() {
        return owner + "/pdf-tools";
    }

    private String published() {
        HttpResponse<String> submitted = submit(skill("1.0.0"));
        assertThat(submitted.statusCode()).as("submit failed: %s", submitted.body())
                .isEqualTo(201);
        String draft = JSON.readTree(submitted.body()).get("version").get("draft_id").asText();
        assertThat(webPost("/web/skills/" + owner + "/pdf-tools/publish", json(Map.of(
                "version", draft, "release_name", "1.0.0"))).statusCode())
                .as("publish failed")
                .isEqualTo(200);
        return draft;
    }

    private String addDraft(String version) {
        return addDraftAs(ownerToken, version);
    }

    private String addDraftAs(String token, String version) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", skill(version))
                .field("name", "draft " + version);
        HttpResponse<String> added = send(request(SKILLS + "/" + owner + "/pdf-tools/versions",
                token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
        assertThat(added.statusCode()).as("adding a draft failed: %s", added.body())
                .isEqualTo(201);
        return JSON.readTree(added.body()).get("version").get("draft_id").asText();
    }

    private HttpResponse<String> submit(byte[] zip) {
        Multipart multipart = Multipart.create().file("file", "pdf-tools.zip", zip);
        return send(request(SKILLS, ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher())
                .build());
    }

    private void grant(String role) {
        HttpResponse<String> granted = send(request(SKILLS + "/" + owner + "/pdf-tools/grants",
                ownerToken)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json(Map.of("handle", friend, "role", role))))
                .build());
        assertThat(granted.statusCode()).as("granting %s: %s", role, granted.body())
                .isEqualTo(201);
    }

    private String tokenOver(com.skillmasterai.support.Browser who) {
        who.get(uri(SESSION));
        HttpResponse<String> response = who.post(uri(KEYS),
                json(Map.of("name", "harness", "scopes", List.of("skills:read", "skills:write"))),
                who.csrfToken());
        assertThat(response.statusCode()).as("signing a key: %s", response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("token").asText();
    }

    private Run runWithInput(String token, String input, String... args)
            throws IOException, InterruptedException {
        Run running = start(token, input, args);
        return running;
    }

    private Run run(String token, String... args) throws IOException, InterruptedException {
        return start(token, null, args);
    }

    private Run start(String token, String input, String... args)
            throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add(CLI.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("SKILLMASTER_SERVER", "http://localhost:" + port);
        builder.environment().put("SKILLMASTER_API_KEY", token);
        builder.environment().put("HOME", home.toString());
        // `os.UserConfigDir` prefers XDG_CONFIG_HOME on Linux and APPDATA on Windows, so a
        // developer's environment would otherwise escape the throwaway HOME.
        builder.environment().put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        builder.environment().put("APPDATA", home.toString());
        builder.environment().put("USERPROFILE", home.toString());

        Path outFile = Files.createTempFile(home, "cli-out", ".txt");
        Path errFile = Files.createTempFile(home, "cli-err", ".txt");
        builder.redirectOutput(outFile.toFile()).redirectError(errFile.toFile());
        Process process = builder.start();
        if (input != null) {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String out = Files.readString(outFile);
        String err = Files.readString(errFile);
        Files.deleteIfExists(outFile);
        Files.deleteIfExists(errFile);
        assertThat(finished).as("the CLI did not finish: %s %s", out, err).isTrue();
        return new Run(process.exitValue(), out, err);
    }

    private record Run(int exit, String out, String err) {
    }

    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            for (String name : List.of(command, command + ".exe")) {
                if (Files.isExecutable(Path.of(directory, name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] skill(String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pdf-tools/SKILL.md",
                "---\nname: pdf-tools\ndescription: a skill for the CLI rung matrix\n"
                        + "version: \"" + version + "\"\n---\nbody " + version + "\n");
        files.put("pdf-tools/references/notes.md", "notes\n");
        return Zips.ofText(files);
    }
}
