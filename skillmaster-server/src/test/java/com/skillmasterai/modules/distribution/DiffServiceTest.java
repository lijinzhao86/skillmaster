package com.skillmasterai.modules.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.distribution.SkillDiff.Status;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a comparison reports, with the caps doing their work.
 *
 * <p>No Spring and no database: M9 owns no tables, so the whole of this class is a function of two
 * snapshots and a blob store. The integration test covers the same paths through two real
 * submissions; this is where the caps are testable at all — reaching a 1 MiB file or a hundred
 * changed files through the upload endpoint would be a test about the upload endpoint.
 *
 * <p>The fake blob store is deliberately dumb and holds bytes in memory. What is under test is which
 * files are read and what is made of them, not how they are stored — and it records what was asked
 * for, which is how "a file that did not exist on one side was never fetched" is checked rather than
 * assumed.
 */
class DiffServiceTest {

    private static final DiffLimits WIDE_OPEN = new DiffLimits(1024 * 1024, 20000, 100);

    private final FakeBlobStore blobs = new FakeBlobStore();

    @Test
    void onlyTheFilesThatDifferAreListed() {
        SkillDiff diff = compare(snapshot("2.0.0", Map.of("SKILL.md", "one\n", "same.md", "unchanged\n")),
                Optional.of(snapshot("1.0.0", Map.of("SKILL.md", "two\n", "same.md", "unchanged\n"))));

        assertThat(diff.from()).isEqualTo("1.0.0");
        assertThat(diff.to()).isEqualTo("2.0.0");
        assertThat(diff.files()).extracting(SkillDiff.File::relpath)
                .as("a file whose digest matched on both sides is not part of the answer")
                .containsExactly("SKILL.md");
        assertThat(diff.files().get(0).status()).isEqualTo(Status.MODIFIED);
        assertThat(diff.files().get(0).added()).isEqualTo(1);
        assertThat(diff.files().get(0).removed()).isEqualTo(1);
    }

    @Test
    void aFileOnOneSideOnlyIsAnAdditionOrARemoval() {
        SkillDiff diff = compare(snapshot("2.0.0", Map.of("new.md", "hello")),
                Optional.of(snapshot("1.0.0", Map.of("gone.md", "bye"))));

        assertThat(diff.files()).extracting(SkillDiff.File::relpath)
                .as("sorted by relpath, so the caps below cut the same files every run")
                .containsExactly("gone.md", "new.md");
        assertThat(diff.files().get(0).status()).isEqualTo(Status.REMOVED);
        assertThat(diff.files().get(1).status()).isEqualTo(Status.ADDED);
        assertThat(diff.files().get(1).hunks().get(0).lines()).containsExactly("+hello");
    }

    @Test
    void aMissingSideIsNeverFetched() {
        compare(snapshot("2.0.0", Map.of("new.md", "hello\n")),
                Optional.of(snapshot("1.0.0", Map.of("gone.md", "bye\n"))));

        assertThat(blobs.asked)
                .as("a file that did not exist on one side has no digest to fetch it by, and "
                        + "inventing an empty blob for it would be a fetch that could not succeed")
                .hasSize(2);
    }

    @Test
    void withNothingToCompareAgainstEveryFileIsAnAddition() {
        SkillDiff diff = compare(snapshot("1.0.0", Map.of("SKILL.md", "one\n")), Optional.empty());

        assertThat(diff.from())
                .as("null rather than a placeholder: there is no version to name, and the page must "
                        + "be able to say that this is a first submission rather than a change")
                .isNull();
        assertThat(diff.files()).extracting(SkillDiff.File::status).containsExactly(Status.ADDED);
    }

    @Test
    void aBinaryFileIsListedAndNotLineDiffed() {
        SkillDiff diff = compare(snapshot("2.0.0", Map.of(), Map.of("icon.png", "not really a png")),
                Optional.of(snapshot("1.0.0", Map.of(), Map.of("icon.png", "different bytes"))));

        SkillDiff.File file = diff.files().get(0);

        assertThat(file.binary()).isTrue();
        assertThat(file.hunks()).isNull();
        assertThat(file.added()).as("not counted, and null rather than zero").isNull();
        assertThat(file.removed()).isNull();
        assertThat(diff.truncated())
                .as("a binary file is a fact about the file, not something a cap cut")
                .isFalse();
    }

    @Test
    void aFilePastTheSizeCapIsNotRenderedButIsStillListed() {
        SkillDiff diff = new DiffService(blobs, new DiffLimits(8, 20000, 100))
                .compare(Optional.of(snapshot("1.0.0", Map.of("big.md", "0123456789"))),
                        snapshot("2.0.0", Map.of("big.md", "0123456789!")));

        assertThat(diff.files()).extracting(SkillDiff.File::relpath).containsExactly("big.md");
        assertThat(diff.files().get(0).hunks()).isNull();
        assertThat(diff.files().get(0).added()).isNull();
        assertThat(diff.truncated())
                .as("a cap cut this one, so the comparison is short of what was asked for — unlike "
                        + "a binary, where nothing was cut because there was nothing to compare")
                .isTrue();
    }

    @Test
    void pastTheFileCapTheRestAreListedWithNothingRendered() {
        SkillDiff diff = new DiffService(blobs, new DiffLimits(1024 * 1024, 20000, 1))
                .compare(Optional.of(snapshot("1.0.0", Map.of())),
                        snapshot("2.0.0", Map.of("a.md", "a\n", "b.md", "b\n", "c.md", "c\n")));

        assertThat(diff.files()).extracting(SkillDiff.File::relpath)
                .as("the file list is bounded by what changed, not by the cap — so a page can "
                        + "honestly say how many files it is not showing")
                .containsExactly("a.md", "b.md", "c.md");
        // v6 is list-first past the file cap: the first response carries status, size and numstat
        // for every file and no bodies at all. The bodies come with `next`.
        assertThat(diff.files()).extracting(SkillDiff.File::hunks).containsOnlyNulls();
        assertThat(diff.files()).extracting(SkillDiff.File::added)
                .as("numstat rides with the list even when the bodies are batched")
                .doesNotContainNull();
        assertThat(diff.next()).isNotNull();
        assertThat(diff.truncated()).isTrue();
    }

    @Test
    void pastTheLineBudgetALaterFileIsNotRendered() {
        SkillDiff diff = new DiffService(blobs, new DiffLimits(1024 * 1024, 3, 100))
                .compare(Optional.of(snapshot("1.0.0", Map.of())),
                        snapshot("2.0.0", Map.of("a.md", "a\nb", "b.md", "c\nd")));

        assertThat(diff.files().get(0).added()).isEqualTo(2);
        assertThat(diff.files().get(0).hunks()).isNotNull();
        assertThat(diff.files().get(1).hunks())
                .as("computed, then withheld: the response says it is partial rather than showing "
                        + "the first few files and stopping quietly")
                .isNull();
        assertThat(diff.truncated()).isTrue();
    }

    @Test
    void batchesAdvanceAndTheLastOneEnds() {
        DiffService service = new DiffService(blobs, new DiffLimits(1024 * 1024, 20000, 2));
        SkillSnapshot target = snapshot("2.0.0", Map.of(
                "a.md", "a\n", "b.md", "b\n", "c.md", "c\n", "d.md", "d\n", "e.md", "e\n"));
        Optional<SkillSnapshot> base = Optional.of(snapshot("1.0.0", Map.of()));

        SkillDiff list = service.compare(base, target, null, null);
        assertThat(list.next())
                .as("the list response's cursor names batch zero").isEqualTo("0");
        assertThat(list.files()).extracting(SkillDiff.File::hunks).containsOnlyNulls();
        assertThat(list.files()).extracting(SkillDiff.File::added).doesNotContainNull();

        SkillDiff first = service.compare(base, target, null, list.next());
        assertThat(first.files().get(0).hunks()).as("the batch's first body").isNotNull();
        assertThat(first.files().get(1).hunks()).as("the batch's second body").isNotNull();
        assertThat(first.files().get(2).hunks()).as("outside the batch").isNull();
        assertThat(first.next()).as("points at the third file, not back at the first")
                .isEqualTo("2");

        SkillDiff second = service.compare(base, target, null, first.next());
        assertThat(second.next()).isEqualTo("4");
        SkillDiff third = service.compare(base, target, null, second.next());
        assertThat(third.next()).as("the last batch ends the walk").isNull();
        assertThat(third.files().get(4).hunks()).isNotNull();
    }

    @Test
    void anInvalidCursorIsAClientErrorNotAServerOne() {
        SkillSnapshot target = snapshot("2.0.0", Map.of("a.md", "a\n"));
        assertThatThrownBy(() -> new DiffService(blobs, WIDE_OPEN)
                .compare(Optional.of(snapshot("1.0.0", Map.of())), target, null, "not-a-cursor"))
                .isInstanceOf(com.skillmasterai.common.RequestException.class);
    }

    @Test
    void aFileWhoseHunksExceedTheLineCapIsNotCountedAndBlocksNothing() {
        SkillDiff diff = new DiffService(blobs, new DiffLimits(1024 * 1024, 2, 20))
                .compare(Optional.of(snapshot("1.0.0", Map.of())),
                        snapshot("2.0.0", Map.of("a.md", "a\nb\nc\nd")));

        SkillDiff.File file = diff.files().get(0);
        assertThat(file.hunks()).as("one giant file is not diffed at all").isNull();
        assertThat(file.added()).as("and has no countable lines").isNull();
        assertThat(diff.next())
                .as("it cannot be the thing every later batch waits for")
                .isNull();
        assertThat(diff.truncated()).isTrue();
    }

    @Test
    void theSingleFileRequestObeysTheSameLineCapAsTheBatch() {
        SkillDiff diff = new DiffService(blobs, new DiffLimits(1024 * 1024, 2, 20))
                .compare(Optional.of(snapshot("1.0.0", Map.of())),
                        snapshot("2.0.0", Map.of("a.md", "a\nb\nc\nd")), "a.md", null);

        SkillDiff.File file = diff.files().get(0);
        assertThat(file.hunks())
                .as("`file=` is the page's only body path, so the cap has to hold here too")
                .isNull();
        assertThat(file.added()).as("and it has no countable lines, as in the batch").isNull();
    }

    @Test
    void theSizeCapTakesTheLargerSideNotTheTarget() {
        SkillDiff diff = new DiffService(blobs, new DiffLimits(8, 20000, 100))
                .compare(Optional.of(snapshot("1.0.0", Map.of("x.md", "0123456789"))),
                        snapshot("2.0.0", Map.of("x.md", "short")));

        assertThat(diff.files().get(0).hunks())
                .as("the old side is what would be decoded, so the cap has to see it")
                .isNull();
        assertThat(diff.files().get(0).added()).isNull();
    }

    @Test
    void limitsOfZeroAreRefused() {
        assertThatThrownBy(() -> new DiffLimits(0, 20000, 100))
                .as("zero would silently turn every comparison into a list of filenames")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private SkillDiff compare(SkillSnapshot target, Optional<SkillSnapshot> base) {
        return new DiffService(blobs, WIDE_OPEN).compare(base, target);
    }

    /** A snapshot over text files; every other field is the same on both sides and irrelevant here. */
    private SkillSnapshot snapshot(String version, Map<String, String> text) {
        return snapshot(version, text, Map.of());
    }

    private SkillSnapshot snapshot(String version, Map<String, String> text,
            Map<String, String> binary) {
        List<ManifestEntry> entries = new ArrayList<>();
        text.forEach((relpath, value) -> entries.add(entry(relpath, value, false)));
        binary.forEach((relpath, value) -> entries.add(entry(relpath, value, true)));
        return new SkillSnapshot("skill-1", "v-" + version, "ns-1", "pdf-tools", "title", "description", "{}",
                version, "digest-" + version, entries.size(),
                entries.stream().mapToLong(ManifestEntry::size).sum(), "published", "at", "at", false,
                Manifest.of(entries));
    }

    /**
     * One manifest entry, with its bytes handed to the blob store under their real digest.
     *
     * <p>Staged rather than written through {@link BlobStore#put}, because these tests are about a
     * comparison and not about storing anything — but the digest has to be the true one of the bytes,
     * because that is what the service reads and what decides whether two files differ.
     */
    private ManifestEntry entry(String relpath, String value, boolean isBinary) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String sha256Hex = Sha256Hex.of(bytes);
        blobs.stage(sha256Hex, bytes);
        return new ManifestEntry(relpath, sha256Hex, bytes.length, isBinary);
    }

    /** Holds whatever the snapshots were built from, and remembers which digests were read. */
    private static final class FakeBlobStore implements BlobStore {

        private final Map<String, byte[]> staged = new HashMap<>();
        private final List<String> asked = new ArrayList<>();

        void stage(String sha256Hex, byte[] bytes) {
            staged.put(sha256Hex, bytes);
        }

        @Override
        public BlobRef put(byte[] bytes) {
            throw new UnsupportedOperationException("these tests stage content directly");
        }

        @Override
        public byte[] get(String sha256Hex) {
            asked.add(sha256Hex);
            byte[] bytes = staged.get(sha256Hex);
            if (bytes == null) {
                throw new AssertionError("nothing staged for " + sha256Hex);
            }
            return bytes;
        }

        @Override
        public boolean exists(String sha256Hex) {
            return staged.containsKey(sha256Hex);
        }

        @Override
        public int deleteUnreferenced(java.util.Set<String> referenced) {
            throw new UnsupportedOperationException("the diff deletes nothing");
        }
    }
}
