package com.skillmasterai.modules.retrieval;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The write side of the index: "this skill has just been published, index its text".
 *
 * <p><strong>Computed inside the publishing transaction</strong> (decided 2026-10-25). There is no
 * outbox and no worker: a publish that commits has its vector, and a publish whose embedding fails
 * rolls back and does not happen. The shape is the one the SMS path already uses for its own
 * external call — see {@code SendVerificationCodeUseCase}, whose comment makes the same trade: a
 * message that was not sent must not look like one that was.
 *
 * <p>The trade, stated plainly: publishing now waits on the provider and fails when it is down. It
 * is affordable because publishing is a human action in the browser, not a hot path, and because
 * switching retrieval off restores it immediately. What it buys is that the index is never silently
 * missing a skill — the one failure a search product cannot explain to the people using it.
 *
 * <p><strong>A no-op when the skill has no published text</strong> — never published, or deleted.
 * There is nothing to embed, and that is not a failure.
 */
public final class EmbeddingIndex {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingIndex.class);

    private final EmbeddingRepository repository;
    private final EmbeddingTextSource source;
    private final Embedder embedder;

    public EmbeddingIndex(EmbeddingRepository repository, EmbeddingTextSource source,
            Embedder embedder) {
        this.repository = repository;
        this.source = source;
        this.embedder = embedder;
    }

    /**
     * Indexes the skill's current published text, replacing whatever vector it had.
     *
     * <p>Called after every publish whose pointer actually moved — including a rollback, which
     * moves the text back. Re-publishing what is already current changes no text and so needs no
     * work; the upsert would be a no-op statement either way.
     *
     * @throws EmbeddingUnavailable when the provider fails. It propagates out of the caller's
     *         transaction, which is what makes "published" and "indexed" one fact rather than two.
     */
    public void refresh(String skillId) {
        if (embedder == null) {
            return;
        }
        Optional<String> text = source.publishedText(skillId);
        if (text.isEmpty()) {
            return;
        }
        // The hash names the text, not the version: republishing identical content — which the
        // product makes easy, since a rollback then a re-publish is the normal way back — costs
        // one indexed lookup instead of a provider round trip.
        String hash = Sha256Hex.of(text.get().getBytes(StandardCharsets.UTF_8));
        if (repository.hasVectorFor(skillId, embedder.model(), hash)) {
            return;
        }
        // **The publish path's own cost line** (PRD #9). The provider logs the token count; this is
        // how long the publish held its row lock waiting for it, which is the number an operator
        // feels. Never the text itself.
        long started = System.nanoTime();
        List<float[]> vectors = embedder.embed(List.of(text.get()));
        LOG.info("indexed one skill in {} ms during its publish",
                (System.nanoTime() - started) / 1_000_000);
        if (vectors.size() != 1) {
            // The interface promises one vector per input. A provider that answers otherwise is
            // "unavailable", not a crash in the publish transaction.
            throw new EmbeddingUnavailable("the embedding service returned " + vectors.size()
                    + " vectors for one text", null);
        }
        float[] vector = vectors.getFirst();
        repository.remember(skillId, embedder.model(), hash, vector, Timestamps.now());
    }
}
