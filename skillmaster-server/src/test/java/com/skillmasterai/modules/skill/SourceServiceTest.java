package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillmasterai.modules.skill.SourceAdapter.FetchedSource;
import com.skillmasterai.modules.skill.SourceAdapter.LicenseCandidate;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.modules.skill.internal.SourceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * The two moments that decide a skill's source and license: the publish record and the later fetch.
 *
 * <p>The publish path may not touch the network — it writes what SKILL.md declared and marks the
 * snapshot pending. The fetch path merges what the adapter read with that declaration and lands in
 * exactly one of the four license states (v49 A11). Each test drives one of those endings.</p>
 */
class SourceServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SKILL = "skill-1";
    private static final String VERSION = "version-1";
    private static final String SOURCE = "source-1";

    private SourceRepository repository;
    private SourceService service;

    @BeforeEach
    void setUp() {
        repository = mock(SourceRepository.class);
        when(repository.findOrigin(SKILL)).thenReturn(Optional.of(new SkillSource(SOURCE, SKILL,
                SkillSource.ORIGIN, "github", "coreyhaines31/marketingskills", "skills/x",
                "GitHub", "https://github.com/coreyhaines31/marketingskills")));
        when(repository.putOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any())).thenReturn(new SkillSource(SOURCE, SKILL, SkillSource.ORIGIN, "github",
                "coreyhaines31/marketingskills", "skills/x", "GitHub",
                "https://github.com/coreyhaines31/marketingskills"));
    }

    @Test
    void aDeclaredSourceAndLicenseAreRecordedWithoutTheNetwork() {
        service = serviceWith(new OfficialSourceAdapter());

        service.record(SKILL, VERSION, """
                {"metadata":{"source":{"kind":"official","locator":"dev/pdf-tools"},
                 "license":"MIT"}}
                """, true, "dev", "pdf-tools");

        verify(repository).putOrigin(eq(SKILL), eq("official"), eq("dev/pdf-tools"), eq(""),
                eq("官方"), isNull());
        verify(repository).putSnapshot(SOURCE, VERSION, null, "[]", SourceSnapshot.PENDING);
        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.DECLARED_ONLY);
        assertThat(license.spdx()).isEqualTo("MIT");
        assertThat(license.detail()).contains("declared").contains("MIT");
    }

    @Test
    void anOfficialSkillWithNoDeclarationGetsTheOfficialSource() {
        // The registry has to know "official" even though the frontmatter named nothing.
        service = serviceWith(new OfficialSourceAdapter());
        when(repository.putOrigin(anyString(), eq("official"), anyString(), anyString(),
                anyString(), any())).thenReturn(new SkillSource(SOURCE, SKILL, SkillSource.ORIGIN,
                "official", "dev/pdf-tools", "", "官方", null));

        service.record(SKILL, VERSION, "{}", true, "dev", "pdf-tools");

        verify(repository).putOrigin(SKILL, "official", "dev/pdf-tools", "", "官方", null);
        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.MISSING);
        assertThat(license.spdx()).isEqualTo("NOASSERTION");
    }

    @Test
    void aNonOfficialSkillWithNoDeclarationRecordsNothing() {
        service = serviceWith(new OfficialSourceAdapter());

        service.record(SKILL, VERSION, "{}", false, "someone", "pdf-tools");

        verify(repository, never()).putOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
        verify(repository, never()).putLicense(any());
        verify(repository, never()).putSnapshot(anyString(), anyString(), any(), anyString(),
                anyString());
    }

    @Test
    void aFetchedLicenseThatMatchesNothingIsOk() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, candidate("MIT"))));
        when(repository.findLicense(VERSION)).thenReturn(Optional.empty());

        service.refresh(SKILL, VERSION);

        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.OK);
        assertThat(license.spdx()).isEqualTo("MIT");
        assertThat(license.licenseText()).contains("Permission is hereby granted");
    }

    @Test
    void theSourceFileWinsOverADisagreeingDeclaration() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, candidate("MIT"))));
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "Apache-2.0", "Apache-2.0", null, LicenseStatus.DECLARED_ONLY, null, null, null,
                "{\"declared\":\"Apache-2.0\"}", "2026-11-01")));

        service.refresh(SKILL, VERSION);

        // v49 shows what the version carries; the declaration disagreement is v50's ingest check.
        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.OK);
        assertThat(license.spdx()).isEqualTo("MIT");
        assertThat(license.detail()).contains("declared").contains("Apache-2.0");
    }

    @Test
    void anUnrecognisedFetchedLicenseIsOtherNotOk() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, candidate("NOASSERTION"))));
        when(repository.findLicense(VERSION)).thenReturn(Optional.empty());

        service.refresh(SKILL, VERSION);

        assertThat(capturedLicense().status()).isEqualTo(LicenseStatus.OTHER);
    }

    @Test
    void aLicenseRefIsOtherRatherThanOk() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, new LicenseCandidate("LicenseRef-Custom", "Custom", null,
                        "custom terms", "LICENSE", "https://example.test/LICENSE", "ok", "{}"))));
        when(repository.findLicense(VERSION)).thenReturn(Optional.empty());

        service.refresh(SKILL, VERSION);

        assertThat(capturedLicense().status()).isEqualTo(LicenseStatus.OTHER);
    }

    @Test
    void aMissingPlaceholderIsLeftAloneWhenTheSourceStillHasNoLicense() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, null)));
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "NOASSERTION", "未声明", null, LicenseStatus.MISSING, null, null, null, "{}",
                "2026-11-01")));

        service.refresh(SKILL, VERSION);

        verify(repository, never()).putLicense(any());
    }

    @Test
    void recordingTheSameDeclarationAgainDoesNotRewriteTheRow() {
        service = serviceWith(new OfficialSourceAdapter());
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "MIT", "MIT", null, LicenseStatus.DECLARED_ONLY, null, null, null,
                "{\"declared\":\"MIT\"}", "2026-11-01")));

        service.record(SKILL, VERSION, """
                {"metadata":{"license":"MIT"}}
                """, true, "dev", "pdf-tools");

        verify(repository, never()).putLicense(any());
    }

    @Test
    void noLicenseAtTheSourceIsMissingWhenNothingWasDeclared() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, null)));
        when(repository.findLicense(VERSION)).thenReturn(Optional.empty());

        service.refresh(SKILL, VERSION);

        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.MISSING);
        assertThat(license.spdx()).isEqualTo("NOASSERTION");
        assertThat(license.name()).isEqualTo("未声明");
    }

    @Test
    void aFailedFetchKeepsTheDeclarationAndDoesNotRewriteTheLicense() {
        service = serviceWith(adapterReturning(new FetchedSource(null, "[]",
                SourceSnapshot.ERROR, null)));
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "MIT", "MIT", null, LicenseStatus.DECLARED_ONLY, null, null, null,
                "{\"declared\":\"MIT\"}", "2026-11-01")));

        service.refresh(SKILL, VERSION);

        verify(repository).putSnapshot(SOURCE, VERSION, null, "[]", SourceSnapshot.ERROR);
        verify(repository, never()).putLicense(any());
    }

    @Test
    void aFailedRefreshMarksAWorkingSnapshotStaleInsteadOfErasingIt() {
        service = serviceWith(adapterReturning(new FetchedSource(null, "[]",
                SourceSnapshot.ERROR, null)));
        when(repository.findSnapshot(SOURCE, VERSION)).thenReturn(Optional.of(new SourceSnapshot(
                SOURCE, VERSION, "8f3c2a1", "[{\"key\":\"stars\"}]", SourceSnapshot.OK,
                "2026-11-05T00:00:00Z")));

        service.refresh(SKILL, VERSION);

        // The row keeps its revision and signals (the SQL only rewrites them for a successful
        // fetch); the status is what tells the reader the numbers are old.
        verify(repository).putSnapshot(SOURCE, VERSION, null, "[]", SourceSnapshot.STALE);
    }

    @Test
    void aRepublishKeepsTheSnapshotAndLicenseTheVersionAlreadyHas() {
        service = serviceWith(new OfficialSourceAdapter());
        when(repository.findSnapshot(SOURCE, VERSION)).thenReturn(Optional.of(new SourceSnapshot(
                SOURCE, VERSION, "8f3c2a1", "[{\"key\":\"stars\"}]", SourceSnapshot.OK,
                "2026-11-05T00:00:00Z")));
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "MIT", "MIT License", null, LicenseStatus.OK, "text", "LICENSE", null,
                "{\"declared\":\"MIT\"}", "2026-11-05")));

        service.record(SKILL, VERSION, """
                {"metadata":{"source":{"kind":"official","locator":"dev/pdf-tools"},
                 "license":"MIT"}}
                """, true, "dev", "pdf-tools");

        // A version is immutable; recording it again must not send it back to pending or reset
        // what the page already shows.
        verify(repository, never()).putSnapshot(eq(SOURCE), eq(VERSION), any(), anyString(),
                anyString());
        verify(repository, never()).putLicense(any());
    }

    @Test
    void aBackfilledPlaceholderTakesTheVersionsDeclarationOnRepublish() {
        service = serviceWith(new OfficialSourceAdapter());
        when(repository.findSnapshot(SOURCE, VERSION)).thenReturn(Optional.of(new SourceSnapshot(
                SOURCE, VERSION, "8f3c2a1", "[]", SourceSnapshot.OK, "2026-11-05T00:00:00Z")));
        // V36 backfills pre-v49 official skills as `missing` without reading the frontmatter.
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "NOASSERTION", "未声明", null, LicenseStatus.MISSING, null, null, null, "{}",
                "2026-11-01")));

        service.record(SKILL, VERSION, """
                {"metadata":{"license":"MIT"}}
                """, true, "dev", "pdf-tools");

        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.DECLARED_ONLY);
        assertThat(license.spdx()).isEqualTo("MIT");
    }

    @Test
    void aSettledLicenseIsNotRewrittenByALaterSourceRead() {
        service = serviceWith(adapterReturning(new FetchedSource("head-moved", "[]",
                SourceSnapshot.OK, candidate("Apache-2.0"))));
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "MIT", "MIT License", null, LicenseStatus.OK, "text", "LICENSE", null,
                "{\"declared\":\"MIT\"}", "2026-11-05")));

        service.refresh(SKILL, VERSION);

        // The adapter reads upstream HEAD, not the commit this version came from. Once the source
        // answered, a later relicensing upstream must not rewrite an old version's license.
        verify(repository, never()).putLicense(any());
    }

    @Test
    void anAgreeingFetchedLicenseStillKeepsTheDeclaration() {
        service = serviceWith(adapterReturning(new FetchedSource("8f3c2a1", "[]",
                SourceSnapshot.OK, candidate("Apache-2.0"))));
        when(repository.findLicense(VERSION)).thenReturn(Optional.of(new SkillLicense(VERSION,
                "Apache-2.0", "Apache-2.0", null, LicenseStatus.DECLARED_ONLY, null, null, null,
                "{\"declared\":\"Apache-2.0\"}", "2026-11-01")));

        service.refresh(SKILL, VERSION);

        SkillLicense license = capturedLicense();
        assertThat(license.status()).isEqualTo(LicenseStatus.OK);
        // Kept on agreement too: otherwise there is nothing left to compare against if the source
        // later says something different.
        assertThat(license.detail()).contains("declared").contains("Apache-2.0");
    }

    private SourceService serviceWith(SourceAdapter adapter) {
        // The registry refuses two adapters claiming one kind, so the official adapter is added
        // only when the test's own adapter is not it.
        List<SourceAdapter> adapters = "official".equals(adapter.kind())
                ? List.of(adapter)
                : List.of(new OfficialSourceAdapter(), adapter);
        return new SourceService(repository, new SourceRegistry(adapters), JSON);
    }

    private SkillLicense capturedLicense() {
        ArgumentCaptor<SkillLicense> captor = ArgumentCaptor.forClass(SkillLicense.class);
        verify(repository).putLicense(captor.capture());
        return captor.getValue();
    }

    private static LicenseCandidate candidate(String spdx) {
        return new LicenseCandidate(spdx, spdx.equals("NOASSERTION") ? "Other" : "MIT License",
                "Copyright (c) 2025 Corey Haines", "Permission is hereby granted", "LICENSE",
                "https://example.test/LICENSE", LicenseStatus.OK.wire(), "{}");
    }

    private static SourceAdapter adapterReturning(FetchedSource fetched) {
        return new SourceAdapter() {
            @Override
            public String kind() {
                return "github";
            }

            @Override
            public SourceBinding parse(String locator, String path) {
                return new SourceBinding("github", locator, path, "GitHub", "https://github.com/x");
            }

            @Override
            public FetchedSource fetch(SourceBinding binding, String revisionHint) {
                return fetched;
            }
        };
    }
}
