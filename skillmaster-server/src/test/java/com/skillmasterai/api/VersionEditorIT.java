package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractAccountIT;
import com.skillmasterai.support.Browser;
import com.skillmasterai.support.Multipart;
import com.skillmasterai.support.Zips;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The browser's way of writing a version (v8): the empty base, the create route, the one-edit route,
 * and the answers each of them gives.
 *
 * <p>Nothing here drives the page. The properties are the server's: a draft is born on the first
 * content change and not when the page opens; a base is a live version or the newest draft and never
 * a discarded row; every refusal names what happened well enough for a banner to say it; and the two
 * existing v7 use cases still own permissions, limits and the tree's byte rules.
 *
 * <p>The fixture is one skill with a live {@code 1.2.3}, a binary file, and drafts the tests make as
 * they go. Nothing writes rows directly: the digests, counts and audit entries are the server's own.
 */
@Sql("/sql/truncate-business-tables.sql")
class VersionEditorIT extends AbstractAccountIT {

    private static final String BASE = "/web/skills";
    /** Not valid UTF-8, which is the ingest rule's definition of binary (a NUL is not). */
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '4', '\n',
        (byte) 0xFF, (byte) 0xFE, 0, 1, 2, 3, '\n'};

    private String username;
    private String phone;
    private String token;

    @BeforeEach
    void signUp() {
        username = randomUsername();
        phone = randomPhone();
        token = tokenFor(registerAndSignIn(username, phone));
    }

    /* --------------------------------------------------------------- the empty base */

    /**
     * E2's server half: {@code ?base=none} is the skill row and nothing else, even while the default
     * read resolves a version.
     *
     * <p>That difference is the whole reason the parameter exists. A page that read the default
     * would copy a discarded row for {@code maintain} — and a base is never a discarded version —
     * or would have to guess which of the rows it listed was a base.
     */
    @Test
    void anEmptyBaseIsTheSkillRowWithNoVersionAndNoFiles() {
        JsonNode first = submit(live("1.2.3", "first description")).get(0);
        publish(first.get("draft_id").asText(), "1.2.3");
        // A newer draft, so the default read has something to select that is not the live version.
        submit(live("2.0.0", "second description"));

        JsonNode blank = body(webGet(base() + "?base=none"));
        assertThat(blank.get("version").isNull())
                .as("no version is selected: this is the base a create starts from")
                .isTrue();
        assertThat(blank.get("files").size()).isZero();
        assertThat(blank.get("name").asText()).isEqualTo("pdf-tools");
        assertThat(blank.get("title").asText())
                .as("the skill row's own metadata, not the selected version's")
                .isEqualTo("PDF Tools");
        assertThat(blank.get("description").asText())
                .as("written at the first submission and moved only by a publish")
                .isEqualTo("first description");

        JsonNode selected = body(webGet(base()));
        assertThat(selected.get("version").isNull())
                .as("the ordinary read does select the newest draft — which is exactly why the"
                        + " empty base may not be read that way")
                .isFalse();
        assertThat(selected.get("version").get("state").asText())
                .as("the pointer is the first answer, even while a newer draft exists")
                .isEqualTo("published");
    }

    @Test
    void anUnknownBaseValueIsARequestError() {
        submit(live("1.2.3", "description"));
        HttpResponse<String> unknown = webGet(base() + "?base=1.2.3");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(code(unknown)).isEqualTo("invalid_request");
        assertThat(field(unknown)).isEqualTo("base");
    }

    /* --------------------------------------------------------------- create */

    @Test
    void theFirstContentChangeCreatesTheDraft() {
        JsonNode submitted = submit(live("1.2.3", "live description")).get(0);
        publish(submitted.get("draft_id").asText(), "1.2.3");

        JsonNode liveDetail = body(webGet(base()));
        JsonNode live = liveDetail.get("version");
        String digest = live.get("digest").asText();
        long before = count("SELECT count(*) FROM skill_version WHERE state = 'draft'");

        HttpResponse<String> created = create(Map.of(
                "name", "editor draft",
                "note", "one line",
                "from", "1.2.3",
                "from_digest", digest,
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode version = body(created).get("version");
        assertThat(version.get("state").asText()).isEqualTo("draft");
        assertThat(version.get("code").asText()).matches("[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(version.get("draft_name").asText()).isEqualTo("editor draft");
        assertThat(version.get("note").asText()).isEqualTo("one line");
        assertThat(version.get("file_count").asInt())
                .as("the base's files, with the one edited file replaced")
                .isEqualTo(live.get("file_count").asInt());
        assertThat(body(created).get("files"))
                .as("the page redraws the tree from this, not from a second read")
                .hasSize(live.get("file_count").asInt());

        // Unchanged files keep the base's blob, byte for byte: building a version is a copy of the
        // manifest, not a re-upload of every file (v8 TD §数据模型).
        Map<String, String> liveHashes = new LinkedHashMap<>();
        for (JsonNode file : liveDetail.get("files")) {
            liveHashes.put(file.get("relpath").asText(), file.get("sha256").asText());
        }
        for (JsonNode file : body(created).get("files")) {
            String relpath = file.get("relpath").asText();
            if (!relpath.equals("SKILL.md")) {
                assertThat(file.get("sha256").asText())
                        .as("%s is the same blob the base held", relpath)
                        .isEqualTo(liveHashes.get(relpath));
            }
        }

        assertThat(count("SELECT count(*) FROM skill_version WHERE state = 'draft'"))
                .as("exactly one row was born").isEqualTo(before + 1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'version_file_write'"
                + " AND detail LIKE '%\"op\":\"create\"%'"))
                .as("the create is one audit entry, with its operation named")
                .isEqualTo(1);

        // The draft is addressed by its code and reads back through the browser plane.
        String code = version.get("code").asText();
        HttpResponse<String> read = webGet(filePath(code, "SKILL.md"));
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(changedBody());
    }

    @Test
    void aCreateWithoutANameABaseDigestOrAnEditIsRefusedByItsOwnCode() {
        JsonNode submitted = submit(live("1.2.3", "live description")).get(0);
        publish(submitted.get("draft_id").asText(), "1.2.3");
        String digest = body(webGet(base())).get("version").get("digest").asText();
        long rows = count("SELECT count(*) FROM skill_version");

        HttpResponse<String> unnamed = create(Map.of(
                "from", "1.2.3", "from_digest", digest,
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));
        assertThat(unnamed.statusCode()).as(unnamed.body()).isEqualTo(400);
        assertThat(code(unnamed)).isEqualTo("draft_name_required");

        Map<String, Object> noEdit = new LinkedHashMap<>();
        noEdit.put("name", "draft");
        noEdit.put("from", "1.2.3");
        noEdit.put("from_digest", digest);
        HttpResponse<String> missingEdit = create(noEdit);
        assertThat(missingEdit.statusCode()).as(missingEdit.body()).isEqualTo(400);
        assertThat(code(missingEdit)).isEqualTo("invalid_request");
        assertThat(field(missingEdit)).isEqualTo("edit");

        HttpResponse<String> noDigest = create(Map.of(
                "name", "draft", "from", "1.2.3",
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));
        assertThat(noDigest.statusCode()).as(noDigest.body()).isEqualTo(428);
        assertThat(code(noDigest)).isEqualTo("if_match_required");

        HttpResponse<String> malformedFrom = create(Map.of(
                "name", "draft", "from", "1.2",
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));
        assertThat(malformedFrom.statusCode()).as(malformedFrom.body()).isEqualTo(400);
        assertThat(code(malformedFrom)).isEqualTo("invalid_request");
        assertThat(field(malformedFrom)).isEqualTo("from");

        HttpResponse<String> malformedDigest = create(Map.of(
                "name", "draft", "from", "1.2.3", "from_digest", "not-a-digest",
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));
        assertThat(malformedDigest.statusCode()).as(malformedDigest.body()).isEqualTo(400);
        assertThat(code(malformedDigest)).isEqualTo("invalid_request");
        assertThat(field(malformedDigest)).isEqualTo("from_digest");

        HttpResponse<String> stale = create(Map.of(
                "name", "draft", "from", "1.2.3",
                "from_digest", "sha256:" + "0".repeat(64),
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(code(stale)).isEqualTo("digest_mismatch");
        assertThat(detail(stale, "current_digest")).isEqualTo(bare(digest));

        assertThat(count("SELECT count(*) FROM skill_version"))
                .as("no refusal wrote a row").isEqualTo(rows);
    }

    /**
     * E2's "all versions discarded" case, on the server: a blank base has no digest to send and the
     * row is born from the skill row's own template.
     */
    @Test
    void aBlankBaseNeedsNoDigestAndStartsWithTheTemplate() {
        JsonNode draft = submit(live("2.0.0", "row description")).get(0);
        HttpResponse<String> discarded = webPost(base() + "/discard",
                json(Map.of("version", draft.get("draft_id").asText())));
        assertThat(discarded.statusCode()).as(discarded.body()).isEqualTo(200);
        assertThat(body(webGet(base() + "?base=none")).get("version").isNull()).isTrue();

        // Before anything new exists: a digest supplied against a blank base describes something
        // else, and the request says so rather than comparing it with nothing.
        HttpResponse<String> withDigest = create(Map.of(
                "name", "other", "from_digest", "sha256:" + "1".repeat(64),
                "edit", edit("write", "SKILL.md", "x\n", null, null)));
        assertThat(withDigest.statusCode()).as(withDigest.body()).isEqualTo(400);
        assertThat(code(withDigest)).isEqualTo("invalid_request");
        assertThat(field(withDigest)).isEqualTo("from_digest");

        // The first edit touches another file on purpose: it leaves the generated SKILL.md alone,
        // which is what lets this test read the template back (E2's whole point).
        HttpResponse<String> created = create(Map.of(
                "name", "blank draft", "note", "",
                "edit", edit("write", "references/api.md", "api\n", null, null)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(body(created).get("version").get("draft_name").asText()).isEqualTo("blank draft");
        String code = body(created).get("version").get("code").asText();

        String template = webGet(filePath(code, "SKILL.md")).body();
        assertThat(template).isEqualTo(
                "---\nname: \"pdf-tools\"\ndescription: \"row description\"\n---\n\n# PDF Tools\n");
        JsonNode detail = body(webGet(base() + "@" + code)).get("version");
        assertThat(detail.get("title").asText())
                .as("the row's metadata is the skill row's — what `?base=none` returned — not what"
                        + " the template parses to: a draft that has not touched SKILL.md yet must"
                        + " not rewrite the skill's title when it is published")
                .isEqualTo("PDF Tools");
        assertThat(body(webGet(base() + "@" + code)).get("description").asText())
                .isEqualTo("row description");
    }

    /* --------------------------------------------------------------- one edit */

    @Test
    void oneEditWritesMovesAndDeletesInsideTheDraft() {
        String code = createDraft();
        String before = digest(code);

        HttpResponse<String> written = edit(code, write("references/new.md", "one\n", before));
        assertThat(written.statusCode()).as(written.body()).isEqualTo(200);
        String afterWrite = body(written).get("version").get("digest").asText();
        assertThat(afterWrite).isNotEqualTo(before);
        assertThat(webGet(filePath(code, "references/new.md")).body()).isEqualTo("one\n");

        HttpResponse<String> same = edit(code, write("references/new.md", "one\n", afterWrite));
        assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
        assertThat(body(same).get("version").get("digest").asText())
                .as("the same bytes are the same state reached twice, not a new version")
                .isEqualTo(afterWrite);

        HttpResponse<String> renamed = edit(code,
                rename("references/new.md", "guides/new.md", afterWrite));
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(webGet(filePath(code, "guides/new.md")).body()).isEqualTo("one\n");

        HttpResponse<String> deleted = edit(code, delete("guides/new.md", digest(code)));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
        assertThat(webGet(filePath(code, "guides/new.md")).statusCode()).isEqualTo(404);
    }

    @Test
    void theTreeAndTheBodysTwoRulesAreTheServersOwn() {
        String code = createDraft();

        HttpResponse<String> intoAFile = edit(code, write("SKILL.md/x.md", "x\n", digest(code)));
        assertThat(intoAFile.statusCode()).as(intoAFile.body()).isEqualTo(400);
        assertThat(code(intoAFile)).isEqualTo("invalid_path");
        assertThat(field(intoAFile)).isEqualTo("SKILL.md/x.md");

        HttpResponse<String> renameIntoAFile = edit(code,
                rename("references/notes.md", "SKILL.md/notes.md", digest(code)));
        assertThat(renameIntoAFile.statusCode()).as(renameIntoAFile.body()).isEqualTo(400);
        assertThat(code(renameIntoAFile)).isEqualTo("invalid_path");

        HttpResponse<String> rootDelete = edit(code, delete("SKILL.md", digest(code)));
        assertThat(rootDelete.statusCode()).as(rootDelete.body()).isEqualTo(409);
        assertThat(code(rootDelete)).isEqualTo("skill_md_cannot_be_deleted");

        HttpResponse<String> rootRename = edit(code, rename("SKILL.md", "BODY.md", digest(code)));
        assertThat(rootRename.statusCode()).as(rootRename.body()).isEqualTo(409);
        assertThat(code(rootRename)).isEqualTo("skill_md_cannot_be_deleted");

        HttpResponse<String> ontoExisting = edit(code,
                rename("references/notes.md", "references/split.md", digest(code)));
        assertThat(ontoExisting.statusCode()).as(ontoExisting.body()).isEqualTo(409);
        assertThat(code(ontoExisting)).isEqualTo("file_already_exists");

        HttpResponse<String> escape = edit(code, write("../escape.md", "x\n", digest(code)));
        assertThat(escape.statusCode()).as(escape.body()).isEqualTo(400);
        assertThat(code(escape)).isEqualTo("invalid_path");
    }

    /**
     * A v7 draft may already hold {@code a} and {@code a/b} — the tree rule is checked on the path
     * this request touches, never over the whole manifest, or such a draft would be uneditable.
     */
    @Test
    void aPreexistingFileAndDirectoryClashDoesNotBlockUnrelatedEdits() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pdf-tools/SKILL.md", (liveSkillMd("1.2.3", "live description"))
                .getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/references/a", "file\n".getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/references/a/b.md", "nested\n".getBytes(StandardCharsets.UTF_8));
        String code = submit(Zips.of(files)).get(0).get("draft_id").asText();

        // The create route checks the same target, and only the target: a first edit that deletes
        // the file half is not a tree change either, even though the base already holds the clash.
        HttpResponse<String> createdDeleting = create(Map.of(
                "name", "delete the clash",
                "from", code,
                "from_digest", digest(code),
                "edit", edit("delete", "references/a", null, null, null)));
        assertThat(createdDeleting.statusCode()).as(createdDeleting.body()).isEqualTo(201);

        // A first edit that *creates* the clash is a tree change, and the create route checks it:
        // `references/a` cannot be a file while `references/a/b.md` is one.
        HttpResponse<String> createdWriting = create(Map.of(
                "name", "write the clash",
                "from", code,
                "from_digest", digest(code),
                "edit", edit("write", "references/a", "x\n", null, null)));
        assertThat(createdWriting.statusCode()).as(createdWriting.body()).isEqualTo(400);
        assertThat(code(createdWriting)).isEqualTo("invalid_path");

        HttpResponse<String> unrelated = edit(code,
                write("references/notes.md", "one\n", digest(code)));
        assertThat(unrelated.statusCode()).as(unrelated.body()).isEqualTo(200);

        // Deleting the file half of the clash is not a tree change; the removed path is not checked.
        HttpResponse<String> deleted = edit(code, delete("references/a", digest(code)));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
        assertThat(webGet(filePath(code, "references/a/b.md")).body()).isEqualTo("nested\n");
    }

    @Test
    void aStaleBaselineIs409AndTheRetryWithTheFreshDigestWins() {
        String code = createDraft();
        Browser other = secondSignedInBrowser(phone, PASSWORD);
        String stale = digest(code);

        HttpResponse<String> first = other.post(uri(editPath(code)),
                write("SKILL.md", changedBody() + "\nsecond writer\n", stale),
                other.csrfToken());
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);

        HttpResponse<String> conflict = edit(code,
                write("SKILL.md", changedBody() + "\nmine\n", stale));
        assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        assertThat(code(conflict)).isEqualTo("digest_mismatch");
        String current = detail(conflict, "current_digest");
        assertThat(current).isEqualTo(bare(body(first).get("version").get("digest").asText()));

        HttpResponse<String> overwritten = edit(code,
                write("SKILL.md", changedBody() + "\nmine\n", current));
        assertThat(overwritten.statusCode()).as(overwritten.body()).isEqualTo(200);
        assertThat(webGet(filePath(code, "SKILL.md")).body()).endsWith("mine\n");
        assertThat(webGet(filePath(code, "references/notes.md")).body())
                .as("the other writer's unrelated file is untouched")
                .isEqualTo("unchanged\n");
    }

    @Test
    void anEditWithoutABaselineIs428AndAMalformedOneIs428Too() {
        String code = createDraft();

        HttpResponse<String> missing = edit(code, write("references/notes.md", "x\n", null));
        assertThat(missing.statusCode()).isEqualTo(428);
        assertThat(code(missing)).isEqualTo("if_match_required");

        HttpResponse<String> malformed = edit(code,
                write("references/notes.md", "x\n", "not-a-digest"));
        assertThat(malformed.statusCode()).isEqualTo(428);
        assertThat(code(malformed)).isEqualTo("if_match_required");

        // `text` is required and may be empty, so a missing field is refused rather than silently
        // truncating the file to zero bytes.
        Map<String, Object> noText = edit("write", "references/notes.md", null, null, null);
        noText.put("base_digest", digest(code));
        HttpResponse<String> missingText = edit(code, json(noText));
        assertThat(missingText.statusCode()).as(missingText.body()).isEqualTo(400);
        assertThat(code(missingText)).isEqualTo("invalid_request");
        assertThat(field(missingText)).isEqualTo("text");
    }

    /* --------------------------------------------------------------- collisions */

    @Test
    void aCollisionNamesThePublishedVersionItHit() {
        JsonNode live = submit(live("1.2.3", "live description")).get(0);
        publish(live.get("draft_id").asText(), "1.2.3");
        String digest = body(webGet(base())).get("version").get("digest").asText();

        // Putting the live body back is a create whose result is the version that already exists.
        HttpResponse<String> clash = create(Map.of(
                "name", "same as live",
                "from", "1.2.3", "from_digest", digest,
                "edit", edit("write", "SKILL.md", liveSkillMd("1.2.3", "live description"),
                        null, null)));
        assertThat(clash.statusCode()).as(clash.body()).isEqualTo(409);
        assertThat(code(clash)).isEqualTo("content_already_exists");
        assertThat(detail(clash, "state")).isEqualTo("published");
        assertThat(detail(clash, "pin")).isEqualTo("1.2.3");
        assertThat(detail(clash, "code")).matches("[0-9A-HJKMNP-TV-Z]{8}");
    }

    @Test
    void aCollisionWithADraftNamesItsVersionCodeAndNoNumber() {
        JsonNode live = submit(live("1.2.3", "live description")).get(0);
        publish(live.get("draft_id").asText(), "1.2.3");
        String other = submit(live("2.0.0", "draft description")).get(0).get("draft_id").asText();
        String draftBody = webGet(filePath(other, "SKILL.md")).body();

        // The base is 1.2.3, so the digest is 1.2.3's — not the newest draft's, which is what the
        // ordinary read selects.
        String liveDigest = body(webGet(base() + "@1.2.3")).get("version").get("digest").asText();
        HttpResponse<String> clash = create(Map.of(
                "name", "same as draft",
                "from", "1.2.3",
                "from_digest", liveDigest,
                "edit", edit("write", "SKILL.md", draftBody, null, null)));
        assertThat(clash.statusCode()).as(clash.body()).isEqualTo(409);
        assertThat(code(clash)).isEqualTo("content_already_exists");
        assertThat(detail(clash, "state")).isEqualTo("draft");
        assertThat(detail(clash, "code")).isEqualTo(other);
        assertThat(detailOrNull(clash, "pin")).isNull();
    }

    /* --------------------------------------------------------------- permissions */

    @Test
    void theLadderDecidesWhoMayOpenAndWhoMayWrite() {
        JsonNode live = submit(live("1.2.3", "live description")).get(0);
        publish(live.get("draft_id").asText(), "1.2.3");
        String draft = submit(live("2.0.0", "draft description")).get(0).get("draft_id").asText();

        String collaborator = randomUsername();
        String collaboratorPhone = randomPhone();
        registerAndSignIn(collaborator, collaboratorPhone);
        Browser theirs = secondSignedInBrowser(collaboratorPhone, PASSWORD);
        // Registration re-pointed the shared jar; the owner is who grants.
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);
        grant(collaborator, "write");

        HttpResponse<String> othersDraftRead = theirs.get(uri(base() + "@" + draft));
        assertThat(othersDraftRead.statusCode())
                .as("write may read somebody else's draft; only changing it needs maintain")
                .isEqualTo(200);

        HttpResponse<String> othersDraftWrite = theirs.post(uri(editPath(draft)),
                write("references/notes.md", "theirs\n", digest(draft)),
                theirs.csrfToken());
        assertThat(othersDraftWrite.statusCode()).as(othersDraftWrite.body()).isEqualTo(403);
        assertThat(code(othersDraftWrite)).isEqualTo("forbidden");

        // The edit route names a row by its row id; a published row's *address* is its number, so
        // the digest is read at the number and the write is addressed by the id.
        String published = live.get("draft_id").asText();
        String liveDigest = body(webGet(base() + "@1.2.3")).get("version").get("digest").asText();
        HttpResponse<String> publishedWrite = theirs.post(uri(editPath(published)),
                write("references/notes.md", "x\n", liveDigest),
                theirs.csrfToken());
        assertThat(publishedWrite.statusCode()).as(publishedWrite.body()).isEqualTo(409);
        assertThat(code(publishedWrite)).isEqualTo("version_is_not_draft");

        // A `write` caller who did not submit it is refused by the permission rule first…
        HttpResponse<String> notMine = theirs.patch(
                uri(base() + "/versions/1.2.3/name"),
                json(Map.of("name", "renamed")));
        assertThat(notMine.statusCode()).as(notMine.body()).isEqualTo(403);
        assertThat(code(notMine)).isEqualTo("forbidden");

        // …while the owner, who may change anything, meets the frozen-content rule.
        HttpResponse<String> frozenName = browser.patch(
                uri(base() + "/versions/1.2.3/name"),
                json(Map.of("name", "renamed")));
        assertThat(frozenName.statusCode()).as(frozenName.body()).isEqualTo(400);
        assertThat(code(frozenName)).isEqualTo("draft_name_is_frozen");

        // Copying somebody else's draft is a read, so `write` may start a new version from it
        // (v8 TD §接口: 复制是读). The copy is a draft of its own.
        String draftDigest = body(webGet(base() + "@" + draft)).get("version").get("digest")
                .asText();
        HttpResponse<String> copied = theirs.post(uri(base() + "/versions"), json(Map.of(
                "name", "from someone else's draft",
                "from", draft,
                "from_digest", draftDigest,
                "edit", edit("write", "references/copied.md", "copied\n", null, null))),
                theirs.csrfToken());
        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        assertThat(body(copied).get("version").get("submitted_by").asText())
                .isEqualTo(collaborator);

        grant(collaborator, "invoke");
        HttpResponse<String> invokeCreate = theirs.post(uri(base() + "/versions"),
                json(Map.of("name", "nope",
                        "edit", edit("write", "SKILL.md", "x\n", null, null))),
                theirs.csrfToken());
        assertThat(invokeCreate.statusCode()).as(invokeCreate.body()).isEqualTo(403);
        assertThat(code(invokeCreate)).isEqualTo("forbidden");
    }

    /**
     * The two states `write` cannot see and `maintain` can, plus the rule that makes the rungs
     * differ inside a visible state: `maintain` changes any draft, not only its own.
     */
    @Test
    void revokedAndDiscardedRowsAreAnsweredByStateAndRungTogether() {
        JsonNode older = submit(live("1.2.2", "older description")).get(0);
        publish(older.get("draft_id").asText(), "1.2.2");
        publish(submit(live("1.2.3", "live description")).get(0).get("draft_id").asText(), "1.2.3");
        String revoked = older.get("draft_id").asText();
        String liveDigest = body(webGet(base() + "@1.2.3")).get("version").get("digest").asText();
        HttpResponse<String> delisted = webPost(base() + "/versions/1.2.2/revoke", "{}");
        assertThat(delisted.statusCode()).as(delisted.body()).isEqualTo(200);

        String collaborator = randomUsername();
        String collaboratorPhone = randomPhone();
        registerAndSignIn(collaborator, collaboratorPhone);
        Browser theirs = secondSignedInBrowser(collaboratorPhone, PASSWORD);
        assertThat(signIn(phone, PASSWORD).statusCode()).isEqualTo(200);
        grant(collaborator, "write");

        HttpResponse<String> writeOnRevoked = theirs.get(uri(base() + "@" + revoked));
        assertThat(writeOnRevoked.statusCode())
                .as("a revoked row is invisible below maintain").isEqualTo(404);
        HttpResponse<String> maintainOnRevoked = edit(revoked,
                write("references/notes.md", "x\n", liveDigest));
        assertThat(maintainOnRevoked.statusCode()).as(maintainOnRevoked.body()).isEqualTo(409);
        assertThat(code(maintainOnRevoked)).isEqualTo("version_is_not_draft");

        JsonNode throwaway = submit(live("3.0.0", "throwaway description")).get(0);
        String discarded = throwaway.get("draft_id").asText();
        assertThat(webPost(base() + "/discard", json(Map.of("version", discarded))).statusCode())
                .isEqualTo(200);
        HttpResponse<String> writeOnDiscarded = theirs.get(uri(base() + "@" + discarded));
        assertThat(writeOnDiscarded.statusCode()).isEqualTo(404);
        HttpResponse<String> maintainOnDiscarded = edit(discarded,
                write("references/notes.md", "x\n", digest(discarded)));
        assertThat(maintainOnDiscarded.statusCode()).isEqualTo(409);
        assertThat(code(maintainOnDiscarded)).isEqualTo("version_is_not_draft");

        // `maintain` is not "my own drafts only": the owner changes a draft the collaborator wrote.
        HttpResponse<String> colleagueCreated = theirs.post(uri(base() + "/versions"),
                json(Map.of(
                        "name", "colleague draft",
                        "from", "1.2.3",
                        "from_digest", liveDigest,
                        "edit", edit("write", "references/colleague.md", "one\n", null, null))),
                theirs.csrfToken());
        assertThat(colleagueCreated.statusCode()).as(colleagueCreated.body()).isEqualTo(201);
        String colleagueDraft = body(colleagueCreated).get("version").get("code").asText();
        HttpResponse<String> ownerEdits = edit(colleagueDraft,
                write("references/colleague.md", "two\n", digest(colleagueDraft)));
        assertThat(ownerEdits.statusCode()).as(ownerEdits.body()).isEqualTo(200);
    }

    /* --------------------------------------------------------------- limits and labels */

    @Test
    void theWholeVersionCeilingsAndTheFrontmatterRulesAreUnchanged() {
        String code = createDraft();
        String before = digest(code);
        int filesBefore = body(webGet(base() + "@" + code)).get("version").get("file_count")
                .asInt();

        HttpResponse<String> tooLarge = edit(code,
                write("references/huge.txt", "x".repeat(16 * 1024 * 1024 + 1), digest(code)));
        assertThat(tooLarge.statusCode()).as(tooLarge.body()).isEqualTo(400);
        assertThat(code(tooLarge)).isEqualTo("invalid_upload");
        assertThat(issue(tooLarge)).isEqualTo("too_large");

        HttpResponse<String> missingFrontmatter = edit(code,
                write("SKILL.md", "# no frontmatter\n", digest(code)));
        assertThat(missingFrontmatter.statusCode()).as(missingFrontmatter.body()).isEqualTo(400);
        assertThat(code(missingFrontmatter)).isEqualTo("invalid_upload");
        assertThat(issue(missingFrontmatter)).isEqualTo("missing_frontmatter");

        HttpResponse<String> wrongName = edit(code,
                write("SKILL.md", "---\nname: other-skill\ndescription: d\n---\n", digest(code)));
        assertThat(wrongName.statusCode()).as(wrongName.body()).isEqualTo(400);
        assertThat(code(wrongName)).isEqualTo("invalid_upload");
        assertThat(issue(wrongName)).isEqualTo("name_mismatch");

        // A refusal is a rollback, not a partial write: the digest and the count are where they
        // were, so nothing the delegate wrote before the check survived.
        assertThat(digest(code)).isEqualTo(before);
        assertThat(body(webGet(base() + "@" + code)).get("version").get("file_count").asInt())
                .isEqualTo(filesBefore);
    }

    /**
     * The file-count ceiling is the whole version's, exactly as a submission's: the fixture starts
     * with six files, so the 512th is accepted and the 513th is not.
     */
    @Test
    void theFileCountCeilingIsStillFiveHundredAndTwelve() {
        String code = createDraft();
        String current = digest(code);
        for (int index = 0; index < 506; index++) {
            HttpResponse<String> written = edit(code,
                    write("references/bulk-" + index + ".md", "x\n", current));
            assertThat(written.statusCode()).as("file %d: %s", index, written.body()).isEqualTo(200);
            current = body(written).get("version").get("digest").asText();
        }
        HttpResponse<String> over = edit(code,
                write("references/one-too-many.md", "x\n", current));
        assertThat(over.statusCode()).as(over.body()).isEqualTo(400);
        assertThat(code(over)).isEqualTo("invalid_upload");
        assertThat(issue(over)).isEqualTo("too_many_files");
    }

    @Test
    void theLabelsAreWrittenOnTheirOwnAndCountedInCodePoints() {
        String code = createDraft();
        String before = digest(code);

        HttpResponse<String> named = browser.patch(uri(base() + "/versions/" + code + "/name"),
                json(Map.of("name", "改名之后")));
        assertThat(named.statusCode()).as(named.body()).isEqualTo(200);
        assertThat(body(named).get("name").asText()).isEqualTo("改名之后");

        HttpResponse<String> noted = browser.patch(uri(base() + "/versions/" + code + "/note"),
                json(Map.of("note", "一句说明")));
        assertThat(noted.statusCode()).as(noted.body()).isEqualTo(200);
        assertThat(digest(code))
                .as("labels are not content: the address and the bytes do not move")
                .isEqualTo(before);
        assertThat(body(webGet(base() + "@" + code)).get("version").get("draft_name").asText())
                .isEqualTo("改名之后");

        HttpResponse<String> tooLongName = browser.patch(
                uri(base() + "/versions/" + code + "/name"),
                json(Map.of("name", "😀".repeat(41))));
        assertThat(tooLongName.statusCode()).as(tooLongName.body()).isEqualTo(400);
        assertThat(code(tooLongName)).isEqualTo("draft_name_too_long");

        HttpResponse<String> tooLongNote = browser.patch(
                uri(base() + "/versions/" + code + "/note"),
                json(Map.of("note", "😀".repeat(201))));
        assertThat(tooLongNote.statusCode()).as(tooLongNote.body()).isEqualTo(400);
        assertThat(code(tooLongNote)).isEqualTo("note_too_long");
    }

    /* --------------------------------------------------------------- binary */

    @Test
    void aBinaryFileIsListedAsOneAndComesBackByteForByte() {
        String code = submit(live("1.2.3", "live description")).get(0).get("draft_id").asText();

        JsonNode pdf = null;
        for (JsonNode file : body(webGet(base() + "@" + code)).get("files")) {
            if (file.get("relpath").asText().equals("assets/manual.pdf")) {
                pdf = file;
            }
        }
        assertThat(pdf).as("the fixture's binary file is in the manifest").isNotNull();
        assertThat(pdf.get("is_binary").asBoolean()).isTrue();

        String cookie = sessionCookieName + "=" + cookieValue(sessionCookieName);
        HttpResponse<byte[]> bytes = sendBytes(HttpRequest
                .newBuilder(uri(filePath(code, "assets/manual.pdf")))
                .header(HttpHeaders.COOKIE, cookie)
                .GET()
                .build());
        assertThat(bytes.statusCode()).as("reading a binary through the browser plane")
                .isEqualTo(200);
        assertThat(bytes.body()).isEqualTo(PDF);
    }

    /* --------------------------------------------------------------- helpers */

    private String base() {
        return BASE + "/" + username + "/pdf-tools";
    }

    private String editPath(String code) {
        return base() + "/versions/" + code + "/edits";
    }

    /** The file's address on the browser plane: the version pin rides on the name segment. */
    private String filePath(String code, String relpath) {
        return base() + "@" + code + "/files/" + relpath;
    }

    private HttpResponse<String> create(Map<String, ?> body) {
        return webPost(base() + "/versions", json(body));
    }

    private HttpResponse<String> edit(String code, String body) {
        return webPost(editPath(code), body);
    }

    private List<JsonNode> submit(byte[] zip) {
        Multipart multipart = Multipart.create()
                .file("file", "pdf-tools.zip", zip)
                .field("name", "fixture");
        HttpResponse<String> response = send(request("/api/v1/skills", token)
                .header(HttpHeaders.CONTENT_TYPE, multipart.contentType())
                .POST(multipart.publisher()).build());
        assertThat(response.statusCode()).as("submit failed: %s", response.body()).isEqualTo(201);
        return List.of(JSON.readTree(response.body()).get("version"));
    }

    private void publish(String code, String releaseName) {
        HttpResponse<String> published = webPost(base() + "/publish",
                json(Map.of("version", code, "release_name", releaseName)));
        assertThat(published.statusCode()).as("publish failed: %s", published.body()).isEqualTo(200);
    }

    private void grant(String handle, String role) {
        HttpResponse<String> granted = webPost(base() + "/grants",
                json(Map.of("handle", handle, "role", role)));
        assertThat(granted.statusCode()).as("grant failed: %s", granted.body()).isEqualTo(201);
    }

    /** A draft made through the browser's own route, from the live version. */
    private String createDraft() {
        JsonNode submitted = submit(live("1.2.3", "live description")).get(0);
        publish(submitted.get("draft_id").asText(), "1.2.3");
        String digest = body(webGet(base())).get("version").get("digest").asText();
        HttpResponse<String> created = create(Map.of(
                "name", "editor draft",
                "from", "1.2.3",
                "from_digest", digest,
                "edit", edit("write", "SKILL.md", changedBody(), null, null)));
        assertThat(created.statusCode()).as("creating the draft: %s", created.body())
                .isEqualTo(201);
        return body(created).get("version").get("code").asText();
    }

    private String digest(String draftOrVersion) {
        return body(webGet(base() + "@" + draftOrVersion)).get("version").get("digest").asText();
    }

    private String code(HttpResponse<String> response) {
        return body(response).get("error").get("code").asText();
    }

    /** `error.details` carries the digest as bare hex, while a version's `digest` is prefixed. */
    private static String bare(String digest) {
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    private String detail(HttpResponse<String> response, String field) {
        String found = detailOrNull(response, field);
        assertThat(found).as("detail %s in %s", field, response.body()).isNotNull();
        return found;
    }

    private String detailOrNull(HttpResponse<String> response, String field) {
        for (JsonNode node : body(response).get("error").get("details")) {
            if (node.get("field").asText().equals(field)) {
                return node.get("issue").asText();
            }
        }
        return null;
    }

    private static Map<String, Object> edit(String op, String path, String text, String from,
            String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", op);
        if (path != null) {
            body.put("path", path);
        }
        if (text != null) {
            body.put("text", text);
        }
        if (from != null) {
            body.put("from", from);
        }
        if (to != null) {
            body.put("to", to);
        }
        return body;
    }

    private String write(String path, String text, String baseDigest) {
        Map<String, Object> body = edit("write", path, text, null, null);
        if (baseDigest != null) {
            body.put("base_digest", baseDigest);
        }
        return json(body);
    }

    private String rename(String from, String to, String baseDigest) {
        Map<String, Object> body = edit("rename", null, null, from, to);
        body.put("base_digest", baseDigest);
        return json(body);
    }

    private String delete(String path, String baseDigest) {
        Map<String, Object> body = edit("delete", path, null, null, null);
        body.put("base_digest", baseDigest);
        return json(body);
    }

    /** One skill: a live candidate with the five files and the binary this suite reads. */
    private static byte[] live(String version, String description) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pdf-tools/SKILL.md",
                liveSkillMd(version, description).getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/references/notes.md", "unchanged\n".getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/references/split.md", "split\n".getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/references/legacy.md", "legacy\n".getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/scripts/compress.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        files.put("pdf-tools/assets/manual.pdf", PDF);
        return Zips.of(files);
    }

    private static String liveSkillMd(String version, String description) {
        return "---\nname: pdf-tools\ntitle: PDF Tools\nversion: \"" + version + "\"\n"
                + "description: " + description + "\n---\nbody at " + version + "\n";
    }

    private static String changedBody() {
        return "---\nname: pdf-tools\ntitle: PDF Tools\nversion: \"1.2.3\"\n"
                + "description: live description\n---\nbody at 1.2.3, edited\n";
    }
}
