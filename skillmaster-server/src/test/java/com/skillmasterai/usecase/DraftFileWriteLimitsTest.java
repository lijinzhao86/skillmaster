package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.IngestLimits;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The v39 ceilings, checked without allocating the bytes they describe.
 *
 * <p>The integration tests used to build a 16 MiB+1 string to reach the old limit; at 100 MiB that
 * is a test that can exhaust the runner's heap instead of proving a rule. A manifest entry carries
 * only a size, so the rule can be pinned here.
 */
class DraftFileWriteLimitsTest {

    private static ManifestEntry entry(String path, long size) {
        return new ManifestEntry(path, "0".repeat(64), size, true);
    }

    @Test
    void oneFileAboveOneHundredMebibytesIsRefused() {
        Manifest manifest = Manifest.of(List.of(
                entry("references/big.bin", IngestLimits.STANDARD_MAX_FILE_BYTES + 1)));

        assertThatThrownBy(() -> DraftFileWrite.requireWithinLimits(manifest))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("100 MiB");
    }

    @Test
    void exactlyOneHundredMebibytesIsLegal() {
        Manifest manifest = Manifest.of(List.of(
                entry("references/big.bin", IngestLimits.STANDARD_MAX_FILE_BYTES)));

        assertThatCode(() -> DraftFileWrite.requireWithinLimits(manifest))
                .doesNotThrowAnyException();
    }

    @Test
    void theWholeVersionAboveFiveHundredTwelveMebibytesIsRefused() {
        List<ManifestEntry> entries = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            entries.add(entry("references/part" + index + ".bin", 90L * 1024 * 1024));
        }

        assertThatThrownBy(() -> DraftFileWrite.requireWithinLimits(Manifest.of(entries)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("512 MiB");
    }

    @Test
    void moreThanFiveHundredTwelveFilesIsRefused() {
        List<ManifestEntry> entries = new ArrayList<>();
        for (int index = 0; index <= IngestLimits.STANDARD_MAX_FILES; index++) {
            entries.add(entry("references/f" + index + ".md", 1));
        }

        assertThatThrownBy(() -> DraftFileWrite.requireWithinLimits(Manifest.of(entries)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("above the limit of 512");
    }
}
