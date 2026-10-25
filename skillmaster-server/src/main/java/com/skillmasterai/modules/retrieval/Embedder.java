package com.skillmasterai.modules.retrieval;

import java.util.List;

/**
 * Turns text into vectors. The one seam between this module and whatever model is in service.
 *
 * <p><strong>An interface because the model will change.</strong> The vectors' meaning changes with
 * the model, so a new one means re-embedding everything — which is why {@link #model()} names the
 * model and the table carries it in its key (v19 TD §数据模型). Nothing outside this module knows
 * which provider answered.
 *
 * <p><strong>Batch, because the provider's limit is 10 and our libraries are larger.</strong> The
 * caller hands over a list; the implementation is responsible for chunking and for the fact that the
 * returned list is in the same order and the same length.
 *
 * <p>Failures are {@link EmbeddingUnavailable}: embedding is an external call, and the callers treat
 * that one type in the two ways it can mean — roll the publish back, or degrade the search to
 * keyword.
 */
public interface Embedder {

    /**
     * The identity of the model and dimension in service, e.g. {@code dashscope:text-embedding-v4:1024}.
     *
     * <p>It is stored beside every vector. Two models' vectors are not comparable, so this string is
     * what keeps a half-finished re-embedding from being queried as if it were one corpus.
     */
    String model();

    /** @return one vector per input, in order; each has the width the table's column declares */
    List<float[]> embed(List<String> texts);
}
