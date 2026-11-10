package com.skillmasterai.modules.retrieval;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The note can move while the provider call is in flight (v59, route-1 audit finding). */
class PersonalEmbeddingIndexTest {

    private static Embedder embedder() {
        Embedder embedder = mock(Embedder.class);
        when(embedder.model()).thenReturn("stub:1024");
        when(embedder.embed(anyList())).thenReturn(List.of(new float[1024]));
        return embedder;
    }

    @Test
    void vectorsForASentenceThatIsNoLongerStoredAreNotWritten() {
        EmbeddingRepository repository = mock(EmbeddingRepository.class);
        PersonalEmbeddingIndex index = new PersonalEmbeddingIndex(repository, embedder());

        index.refresh("u", "s", "新句子", List.of("新句子"), () -> "旧句子");

        verify(repository, atLeastOnce()).deletePersonal("u", "s", "stub:1024");
        verify(repository, never()).replacePersonal(anyString(), anyString(), anyString(),
                anyList(), anyString());
    }

    @Test
    void vectorsAreWrittenWhenTheStoredSentenceStillMatches() {
        EmbeddingRepository repository = mock(EmbeddingRepository.class);
        PersonalEmbeddingIndex index = new PersonalEmbeddingIndex(repository, embedder());

        index.refresh("u", "s", "同一句", List.of("同一句"), () -> "同一句");

        verify(repository).replacePersonal(anyString(), anyString(), anyString(), anyList(),
                anyString());
    }
}
