package com.skillmasterai.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.retrieval.EmbeddingIndex;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Publishing is a local transaction: it never asks the source platform anything (ADR 0161).
 *
 * <p>v49 refreshed the source once after a publish committed. For imported skills that was a repeat
 * of a read the draft had already made — and it put a third party on the publish path. This test is
 * the regression guard: the use case may record what the version declares, and must not refresh.
 */
class PublishNeverRefreshesSourceTest {

    @Test
    void publishingDoesNotReadTheSource() {
        NamespaceService namespaces = mock(NamespaceService.class);
        SkillVersionService versions = mock(SkillVersionService.class);
        SkillMetadataService metadata = mock(SkillMetadataService.class);
        AuditLog audit = mock(AuditLog.class);
        EmbeddingIndex embeddings = mock(EmbeddingIndex.class);
        SourceService sources = mock(SourceService.class);

        Namespace official = new Namespace("01M3HTGC79XSQK3TS3ZF0BG05F", "skillmaster", "官方",
                "01M3HTG7GDZGTDME9B136ZVAW4", "public", true);
        when(namespaces.bySlug("skillmaster")).thenReturn(Optional.of(official));
        when(namespaces.personalNamespaceOf("01M3HTG7GDZGTDME9B136ZVAW4")).thenReturn(official);
        Access access = new Access("skill-1", "01M3HTGC79XSQK3TS3ZF0BG05F", "x", "X", "", "{}", "public", null, null,
                null, null, null, null, true, null, "01M3HTG7GDZGTDME9B136ZVAW4", true);
        when(metadata.accessFor(eq("01M3HTGC79XSQK3TS3ZF0BG05F"), eq("x"), any(Caller.class)))
                .thenReturn(Optional.of(access));
        when(versions.publishVersion(eq(access), any(), any())).thenReturn(Optional.of(
                new PromotionOutcome("skill-1", "v-1", "1.0.0", "sync-abc", "digest", "2026-11-07",
                        true)));

        new PublishSkillVersionUseCase(namespaces, versions, metadata, audit, embeddings, sources)
                .publish("skillmaster", "x", new VersionPin.RowId("v-1"), "1.0.0",
                        AuthenticatedSubject.ofService("01M3HTG7GDZGTDME9B136ZVAW4"));

        verify(sources, never()).refresh(anyString(), anyString());
        verify(sources).record(eq("skill-1"), eq("v-1"), anyString(), eq(true), eq("skillmaster"),
                eq("x"), eq(false));
    }
}
