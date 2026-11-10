package com.skillmasterai.modules.retrieval;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import java.util.List;

/**
 * The write side of the private sentence vectors (v59 P2).
 *
 * <p><strong>One row per sentence.</strong> The caller's sentence often names two or three
 * situations; averaging them blurs the match. The caller splits at sentence punctuation and hands
 * the pieces here, and a query takes the best cosine over them.
 *
 * <p><strong>Computed when the note is written, not when it is searched.</strong> A search is the
 * hot path; one provider call at write time is the same trade the publish path already makes. A
 * provider outage must not take the note away — the caller of this class catches the failure and
 * keeps S1 working; the next write retries the vectors.
 */
public final class PersonalEmbeddingIndex {

    private final EmbeddingRepository repository;
    private final Embedder embedder;

    public PersonalEmbeddingIndex(EmbeddingRepository repository, Embedder embedder) {
        this.repository = repository;
        this.embedder = embedder;
    }

    /**
     * @throws EmbeddingUnavailable when the provider fails; the stored note is already committed
     *         and the caller decides whether that is fatal (it is not: S1 still works)
     */
    public void refresh(String userId, String skillId, String text, List<String> sentences,
            java.util.function.Supplier<String> currentText) {
        if (embedder == null) {
            // Retrieval is off, so no new vector can be written — but a vector from before the
            // switch would describe the sentence the caller is replacing. Remove it rather than
            // leave stale evidence for the day the provider comes back.
            repository.deletePersonalAnyModel(userId, skillId);
            return;
        }
        if (sentences.isEmpty()) {
            clear(userId, skillId);
            return;
        }
        // **Delete before embedding, not after.** If the provider fails halfway, the rows that
        // remain would describe the sentence the caller just replaced; S2 would keep promoting
        // the skill for words no longer on screen. With no rows, the worst case is S2 falling
        // back to S1/S3 until the next write repairs it.
        repository.deletePersonal(userId, skillId, embedder.model());
        List<float[]> vectors = embedder.embed(sentences);
        if (vectors.size() != sentences.size()) {
            throw new EmbeddingUnavailable("the embedding service returned " + vectors.size()
                    + " vectors for " + sentences.size() + " sentences", null);
        }
        // **Embedding is slow; the note can move under it.** A second save may have committed a
        // different sentence while this call waited on the provider. Writing these vectors then
        // would leave the old sentence's evidence beside the new text, and S2 would promote the
        // skill for words no longer on screen. Re-read the authoritative text after the call and
        // write only if it is still the one that was embedded.
        if (!java.util.Objects.equals(currentText.get(), text)) {
            repository.deletePersonal(userId, skillId, embedder.model());
            return;
        }
        repository.replacePersonal(userId, skillId, embedder.model(), vectors, Timestamps.now());
        // The window between the check above and this write is small but real. If the note moved
        // while the rows were being written, drop them: missing S2 evidence is safe, evidence for
        // a sentence the caller already replaced is not.
        if (!java.util.Objects.equals(currentText.get(), text)) {
            repository.deletePersonal(userId, skillId, embedder.model());
        }
    }

    public void clear(String userId, String skillId) {
        if (embedder == null) {
            repository.deletePersonalAnyModel(userId, skillId);
            return;
        }
        repository.deletePersonal(userId, skillId, embedder.model());
    }
}
